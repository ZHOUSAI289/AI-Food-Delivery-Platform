package com.sky.task;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时任务类 —— 现在只剩【一个】任务：超时未付款自动取消。
 *
 * 【原来还有一个：每天凌晨 1 点把"派送中超过 60 分钟"的订单直接改成已完成】
 * 它已经删除（骑手端接口设计文档 §2.3 的产品决定），三条理由：
 *   1. 骑手端上线之后，它会把【根本没送到】的单标记成已完成 ——
 *      用户投诉"我没收到但显示已完成"，而系统里查不出这单是自动改的、还是真送到了
 *   2. "送达"一旦有了明确的操作人（骑手端 R8 确认送达），就不该再存在一个自动的假送达
 *   3. 它当时用的是非 CAS 的 update（拿着 select 出来的整行快照写回所有非 null 字段），
 *      和 processTimeoutOrders 修掉的那个 bug 属于同一类：骑手正好在这个窗口里确认送达，
 *      delivery_time 会被快照里的 null 覆盖掉
 *
 * 需要兜底时走管理端的"管理员代确认送达"（有人按、有 CAS 限制、日志里看得见），
 * 而不是让定时任务在凌晨替所有人做决定。
 * 担心骑手忘记点送达的话，正确做法是"超时【告警】给人看"，属于下一期的运营需求 ——
 * 真要往这个类里加第二个定时任务，先看 OrderTaskTest 里那条断言。
 */
@Component
@Slf4j
public class OrderTask {

    /** 超时未付款自动取消时写入的取消原因 */
    private static final String CANCEL_REASON_TIMEOUT = "支付超时，取消订单";

    @Autowired
    private OrderMapper orderMapper;

    /**
     * 处理超时订单的方法
     */
    @Scheduled(cron = "0 * * * * ?") //每分钟触发一次
    public void processTimeoutOrders(){
        log.info("处理超时订单，{}", LocalDateTime.now());
        List<Orders> ordersList =
                orderMapper.getByStatusAndOrderTimeLT(Orders.PENDING_PAYMENT, LocalDateTime.now().plusMinutes(-15));

        if (ordersList != null && ordersList.size() > 0){
            int cancelled = 0;
            for (Orders orders : ordersList) {
                if (cancelIfStillPending(orders)) {
                    cancelled++;
                }
            }
            // 扫描数和实际取消数可能对不上：差的那部分，就是在"扫描"和"取消"之间
            // 被用户抢着付掉款的订单。这是正常且必须允许发生的，
            // 所以两条都记下来 —— 这个差值就是超时取消和用户支付撞车的次数，可直接观测。
            log.info("超时订单处理完成：扫描 {} 张，实际取消 {} 张", ordersList.size(), cancelled);
        }
    }

    /**
     * 把一张查询出来的超时订单改成「已取消」—— 但只有它【此刻仍然是待付款且未支付】时才改。
     *
     * 【为什么单独抽成一个方法】
     * 这一段是"读-判断-写"的临界区：查询在 processTimeoutOrders 里，写在本地。
     * 抽出来之后这个临界区就有了名字，也好针对它写测试 ——
     * 竞态没法在测试里真开两个线程去撞，只能靠暴露这个中间状态来确定性地复现。
     *
     * 【为什么不能直接把 staleOrder 交给 update】
     * staleOrder 是 select * 查出来的，里面带着 pay_status = 0、pay_method = 1 等一整排字段。
     * 而 OrderMapper.update 是动态 <set>，会把所有非 null 的字段一起写回去，
     * 结果就是【即便用户在这几毫秒里付款成功了，pay_status 也会被覆盖回 0】——
     * 钱收了、单取消了，账面上却显示"从没付过款"，连对账都发现不了。
     * 所以这里只把 id 和取消原因交给 SQL。
     *
     * @return 是否真的取消了（false = 这单已经被别人推进过状态了，不该再动它）
     */
    boolean cancelIfStillPending(Orders staleOrder) {
        Orders cancel = Orders.builder()
                .id(staleOrder.getId())
                .cancelReason(CANCEL_REASON_TIMEOUT)
                .build();

        // CAS：where 里带着 status = 1 and pay_status = 0，
        // 由数据库把"检查"和"更新"做成一个原子操作，没有留给我们的窗口。
        // 返回 0 就说明这单在扫描之后已经付款/被取消了 —— 什么都不做才是对的。
        return orderMapper.updateTimeoutCancel(cancel) == 1;
    }
}
