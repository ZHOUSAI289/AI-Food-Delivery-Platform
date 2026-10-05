package com.sky.service;

import com.sky.entity.Orders;
import com.sky.entity.ShoppingCart;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.ShoppingCartMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static com.sky.entity.Orders.CANCELLED;
import static com.sky.entity.Orders.PAID;
import static com.sky.entity.Orders.PENDING_PAYMENT;
import static com.sky.entity.Orders.TO_BE_CONFIRMED;
import static com.sky.entity.Orders.UN_PAID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「SQL 里少了本该强制的条件」这一类 bug 的回归测试。
 *
 * 【为什么把这两个不相干的功能放在一个类里】
 * 它们的根因是同一个：**WHERE 里的条件写成了"传了才生效"**。
 *   · 取消订单的 CAS 只判状态、不判 pay_status
 *   · 购物车 list() 的四个条件全是可选的（`<if>`），全不传时 SQL 里【一个 WHERE 都没有】
 * 共同后果也一样：**动了不该动的数据** —— 取消了一张已经付过钱的单、或者把所有人的购物车查出来。
 *
 * 这个项目已经因为同类问题栽过一次（超时取消任务用非 CAS 的 update，
 * 把用户刚付过款的订单改成了已取消），所以这里把它当成一类 bug 钉住，而不是零散地补两处。
 *
 * 【为什么用"陈旧快照"来复现取消订单的竞态】
 * 不需要开两个线程。Service 手里那条从 select 拿到的快照，就等价于竞态发生之后的状态：
 * 测试先取快照、再模拟用户付款、最后拿快照去执行取消。真实竞态里 Service 拿到的是同一个东西。
 * （OrderTaskTest 用的也是这个手法。）
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
class OrderDataGuardTest {

    /** 造数据用的大 id，避开库里真实的 user_id = 5 */
    private static final Long ME = 990001L;
    private static final Long OTHER = 990002L;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private ShoppingCartMapper shoppingCartMapper;

    // ========================================================================
    // 取消订单：CAS 必须把 pay_status 一起判了
    // ========================================================================

    @Test
    @DisplayName("取消订单：在【读取之后、取消之前】付了款的单，不能被取消掉")
    void cancelOrder_shouldNotCancelOrderPaidInTheWindow() {
        // 1) 待付款、未支付 —— 用户点"取消"时，Service 读到的就是这份快照
        Long orderId = insertOrder(ME, PENDING_PAYMENT);

        // 2) 就在"读完、还没执行取消"的空档里，用户付款成功了
        assertThat(orderMapper.updateStatus(Orders.builder().id(orderId).build()))
                .as("这一步就是用户的支付动作")
                .isEqualTo(1);

        // 3) 取消动作拿着【陈旧快照】去执行，快照里 pay_status 还是"未支付"
        Orders stale = Orders.builder()
                .id(orderId)
                .status(CANCELLED)
                .payStatus(UN_PAID)                 // ← 快照里的支付状态，就是这次判断的依据
                .cancelReason("用户主动取消")
                .cancelTime(LocalDateTime.now())
                .build();
        int rows = orderMapper.updateUserStatus(stale);

        // 4) 底线。
        //    少了 pay_status 这个条件，这里会返回 1：订单被取消，但钱已经收了 ——
        //    而且【不会退】，因为"要不要退款"看的正是快照里的 pay_status（快照说是未支付，于是跳过）。
        //    结果就是"钱收了、单没了、账上还显示已支付"，对账都看不出来。
        assertThat(rows).as("已经付过钱的订单不能被取消").isEqualTo(0);

        Orders after = orderMapper.getById(orderId);
        assertThat(after.getStatus()).as("订单必须还停在待接单").isEqualTo(TO_BE_CONFIRMED);
        assertThat(after.getPayStatus()).as("付款记录不能被抹掉").isEqualTo(PAID);
    }

    @Test
    @DisplayName("取消订单：仍然待付款未支付的单，要能正常取消（防止把 bug 修成功能失效）")
    void cancelOrder_shouldStillCancelUnpaidOrder() {
        Long orderId = insertOrder(ME, PENDING_PAYMENT);

        Orders cancel = Orders.builder()
                .id(orderId)
                .status(CANCELLED)
                .payStatus(UN_PAID)
                .cancelReason("用户主动取消")
                .cancelTime(LocalDateTime.now())
                .build();

        assertThat(orderMapper.updateUserStatus(cancel)).isEqualTo(1);
        assertThat(orderMapper.getById(orderId).getStatus()).isEqualTo(CANCELLED);
    }

    // ========================================================================
    // 购物车 list()：user_id 必须是强制条件
    // ========================================================================

    @Test
    @DisplayName("购物车 list()：什么条件都不给时，不能把所有人的购物车都查出来")
    void cartList_withoutAnyCondition_shouldNotReturnEveryoneCart() {
        insertCartRow(ME, 990001L);
        insertCartRow(OTHER, 990002L);

        // 攻击姿势：构造一个所有字段都为 null 的查询条件（等价于任何地方漏设了字段）
        List<ShoppingCart> rows = shoppingCartMapper.list(ShoppingCart.builder().build());

        // 原来的 SQL 四个条件全是 <if>，全为 null 时会退化成 "select * from shopping_cart"，
        // 于是这里会返回【两个用户】的购物车行 —— 谁调用了它、再 .get(0) 拿一条去改，
        // 改的就是别人的购物车。
        assertThat(rows).as("没有任何条件时必须是空结果，而不是全表").isEmpty();
    }

    @Test
    @DisplayName("购物车 list()：给了 userId 仍然只查这个人的（防止改坏正常路径）")
    void cartList_withUserId_shouldStillFilter() {
        insertCartRow(ME, 990001L);
        insertCartRow(OTHER, 990002L);

        List<ShoppingCart> mine = shoppingCartMapper.list(
                ShoppingCart.builder().userId(ME).build());

        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).getUserId()).isEqualTo(ME);
    }

    // ========================================================================
    // 测试工具
    // ========================================================================

    /** orders 有 10 个 NOT NULL 列，这里全部填上，否则插入会失败 */
    private Long insertOrder(Long userId, Integer status) {
        Orders order = Orders.builder()
                .number("G" + System.nanoTime() + userId)     // number 有唯一索引
                .status(status)
                .userId(userId)
                .addressBookId(0L)
                .orderTime(LocalDateTime.now())
                .payMethod(1)
                .payStatus(UN_PAID)
                .amount(new BigDecimal("10.00"))
                .deliveryStatus(1)
                .tablewareStatus(1)
                .tablewareNumber(1)
                .packAmount(0)
                .phone("13800000000")
                .address("test-address")
                .consignee("test-consignee")
                .build();
        orderMapper.insert(order);
        return order.getId();
    }

    /** 造一条购物车行（insert 是 upsert，这里每个用户只插一件，不会撞唯一键） */
    private void insertCartRow(Long userId, Long dishId) {
        shoppingCartMapper.insert(ShoppingCart.builder()
                .userId(userId)
                .dishId(dishId)
                .name("test-dish-" + dishId)
                .image("test.png")
                .number(1)
                .amount(new BigDecimal("10.00"))
                .createTime(LocalDateTime.now())
                .build());
    }
}
