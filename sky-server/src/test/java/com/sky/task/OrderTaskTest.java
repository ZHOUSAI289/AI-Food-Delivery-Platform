package com.sky.task;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时订单定时任务的测试。
 *
 * 【为什么单独一个测试类，不塞进 OrderServiceUserTest】
 * 那个类测的是 C 端 HTTP 接口（越权、参数绑定）；这里测的是【后台定时任务】，
 * 没有 HTTP、没有 token，出事的形式也完全不同 —— 它是在和用户操作抢同一条订单记录。
 *
 * 【为什么这个类必须存在】
 * 超时取消和用户支付会撞在一起：任务先把待付款订单 SELECT 出来，
 * 在它执行 UPDATE 之前用户付款成功了。这个窗口极窄，手工测试永远撞不上，
 * 但后果是"钱收了、单没了、账上还显示没付过"—— 对账都发现不了。
 * 自动化测试可以在毫秒级把这条交错确定性地摆出来。
 *
 * 【怎么在没有真并发的情况下复现竞态】
 * 不需要开两个线程。定时任务手里那条从 select 拿到的【陈旧快照】，
 * 就等价于竞态发生之后的结果：测试自己先取一份快照，再把订单改成已付款，
 * 最后把那份快照交给任务的处理方法。真实竞态里任务拿到的是一模一样的对象。
 *
 * 【为什么 webEnvironment 用 RANDOM_PORT】
 * 和 OrderServiceUserTest 同理：@SpringBootTest 默认的 MOCK 环境没有真实 WebSocket 容器，
 * WebSocketConfiguration 里的 ServerEndpointExporter 会让整个 ApplicationContext 起不来，
 * 表现成"所有用例一起报环境错误"，很容易误判成测试写错了。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
class OrderTaskTest {

    /** 用来造数据的大 id，避开库里已有的 user_id = 5 等真实数据 */
    private static final Long ME = 990001L;

    @Autowired
    private OrderTask orderTask;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ========================================================================
    // 竞态（本类的核心）
    // ========================================================================

    @Test
    @DisplayName("超时取消：不能碰已经付款成功的订单，更不能把 pay_status 抹回未支付")
    void timeoutCancel_shouldNotClobberPaidOrder() {
        // 1. 一张 20 分钟前的待付款订单，已经超过 15 分钟的取消阈值
        Long orderId = insertPendingOrder(LocalDateTime.now().minusMinutes(20));

        // 2. 定时任务先 SELECT 出这批订单 —— 此刻它手里是一条【快照】
        List<Orders> snapshotList = orderMapper.getByStatusAndOrderTimeLT(
                Orders.PENDING_PAYMENT, LocalDateTime.now().minusMinutes(15));
        Orders staleOrder = findById(snapshotList, orderId);
        assertThat(staleOrder).as("快照里应该有这张待付款订单").isNotNull();

        // 3. 就在 SELECT 之后、UPDATE 之前的这个空档里，用户付款成功了
        assertThat(orderMapper.updateStatus(Orders.builder().id(orderId).build()))
                .as("这一步就是用户的支付动作")
                .isEqualTo(1);

        // 4. 定时任务接着处理【那条陈旧快照】
        orderTask.cancelIfStillPending(staleOrder);

        // 5. 底线。用软断言：这里被破坏的其实是【两个】字段，
        //    普通断言会在第一个就停下，只能看到一个症状。
        Orders after = orderMapper.getById(orderId);
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(after.getStatus())
                .as("已经付款的订单不能被超时任务取消")
                .isEqualTo(Orders.TO_BE_CONFIRMED);
        softly.assertThat(after.getPayStatus())
                .as("付款记录不能被抹回未支付")
                .isEqualTo(Orders.PAID);
        softly.assertAll();
    }

    // ========================================================================
    // 正常路径（防止把 bug 修成功能失效）
    // ========================================================================

    @Test
    @DisplayName("超时取消：仍然待付款的订单要正常被取消")
    void timeoutCancel_shouldStillCancelPendingOrder() {
        Long orderId = insertPendingOrder(LocalDateTime.now().minusMinutes(20));

        orderTask.processTimeoutOrders();

        Orders after = orderMapper.getById(orderId);
        assertThat(after.getStatus()).isEqualTo(Orders.CANCELLED);
        assertThat(after.getCancelReason()).isEqualTo("支付超时，取消订单");
        assertThat(after.getCancelTime()).isNotNull();
        // 本来就没人付过钱，退款流程不该被牵动
        assertThat(after.getPayStatus()).isEqualTo(Orders.UN_PAID);
    }

    @Test
    @DisplayName("超时取消：还没到 15 分钟的待付款订单不能被误取消")
    void timeoutCancel_shouldLeaveFreshOrderAlone() {
        Long orderId = insertPendingOrder(LocalDateTime.now().minusMinutes(5));

        orderTask.processTimeoutOrders();

        assertThat(orderMapper.getById(orderId).getStatus())
                .as("只下了 5 分钟的单不该被超时任务取消")
                .isEqualTo(Orders.PENDING_PAYMENT);
    }

    // ========================================================================
    // 产品决定的护栏
    // ========================================================================

    @Test
    @DisplayName("OrderTask 只允许有「超时取消」一个定时任务（凌晨自动完成已按 §2.3 删除）")
    void onlyTimeoutTaskShouldBeScheduled() {
        // 【为什么用反射写这个断言，而不是"调用一下看效果"】
        // 那个被删掉的任务每天凌晨 1 点才触发，测试里根本调不到它；而它一旦被加回来，
        // 后果是"根本没送到的单被标记成已完成"——这种事不能靠"记得别加"来避免。
        // 所以把这条产品决定钉成一条会失败的断言：谁要往 OrderTask 里加第二个定时任务，
        // 就必须先来读 OrderTask 类注释里那段理由。
        List<String> scheduledMethods = Arrays.stream(OrderTask.class.getDeclaredMethods())
                .filter(m -> m.getAnnotation(Scheduled.class) != null)
                .map(Method::getName)
                .collect(Collectors.toList());

        assertThat(scheduledMethods).containsExactly("processTimeoutOrders");
    }

    // ========================================================================
    // 测试工具
    // ========================================================================

    /** 造一张指定下单时间的待付款未支付订单，返回订单 id */
    private Long insertPendingOrder(LocalDateTime orderTime) {
        String number = "TT" + System.nanoTime();
        jdbcTemplate.update(
                "insert into orders (number, status, user_id, address_book_id, order_time, pay_method,"
                        + " pay_status, amount, delivery_status, tableware_status)"
                        + " values (?, ?, ?, ?, ?, 1, 0, ?, 1, 1)",
                number, Orders.PENDING_PAYMENT, ME, 0L, orderTime, new BigDecimal("10.00"));
        return jdbcTemplate.queryForObject("select id from orders where number = ?", Long.class, number);
    }

    private Orders findById(List<Orders> list, Long id) {
        if (list == null) {
            return null;
        }
        for (Orders o : list) {
            if (id.equals(o.getId())) {
                return o;
            }
        }
        return null;
    }
}
