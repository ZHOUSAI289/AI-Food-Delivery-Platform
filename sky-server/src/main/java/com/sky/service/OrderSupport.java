package com.sky.service;

import com.sky.constant.MessageConstant;
import com.sky.entity.Orders;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.OrderMapper;
import com.sky.properties.WeChatProperties;
import com.sky.utils.WeChatPayUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.sky.constant.MessageConstant.REFUND_FAILED;
import static com.sky.constant.MessageConstant.REFUND_NOT_CONFIGURED;
import static com.sky.entity.Orders.CANCELLED;
import static com.sky.entity.Orders.PAID;

/**
 * 订单模块的公共支撑逻辑 —— 管理端（OrderServiceimplBusiness）和 C 端（OrderServiceImplUser）共用。
 *
 * 【为什么要单独抽一个类】
 * 这两个方法原本在管理端和 C 端各写了一份（一字不差），一共三处调用：
 *   - 管理端取消订单
 *   - 管理端拒单
 *   - C 端用户取消订单
 *
 * 复制两份的代价在【普通逻辑】上是"改一处漏一处"；但在【资金逻辑】上是
 * "漏掉的那一处会把钱算错" —— 所以必须只有一份实现，只有一个地方需要测。
 *
 * 注意这里是 @Component 而不是"接口 + Impl"：
 * 项目里 Service 用接口是为了让不同端有不同实现（OrderService / OrderServiceUser），
 * 而这个类两边【共用同一份实现】，多写一个接口只是噪音。
 */
@Component
@Slf4j
public class OrderSupport {

    @Autowired
    private WeChatPayUtil weChatPayUtil;

    @Autowired
    private WeChatProperties weChatProperties;

    @Autowired
    private OrderMapper orderMapper;

    /**
     * 已支付则调用微信退款。
     *
     * 如果微信退款所需的配置没配齐（见 application-dev.yml 的 sky.wechat），
     * 则跳过退款、记录警告日志，并返回一条提示给用户，保证取消/拒单功能仍然可用。
     *
     * 【调用方必须把它放在 CAS 改状态之前】
     * 顺序反过来（先改状态、后退款）的话，一旦退款失败，订单会显示"已取消"但钱没退 ——
     * 用户来投诉，而系统状态看起来是"完成"的，最容易漏掉。
     *
     * @return 需要提示给用户的信息；无需提示时返回 null
     */
    public String refundIfNeeded(Orders orders) {
        // 未支付，不需要退款
        if (!PAID.equals(orders.getPayStatus())) {
            return null;
        }

        // TODO 退款配置缺失，跳过退款（否则调用会直接抛 NPE）
        if (!isRefundConfigured()) {
            log.warn("【需人工处理】微信退款配置缺失，已跳过退款。订单号={}，退款金额={}元",
                    orders.getNumber(), orders.getAmount());
            return REFUND_NOT_CONFIGURED;
        }

        try {
            // 退款单号直接用订单号：微信会对重复的退款单号做幂等，
            // 因此并发重复调用也只会退一次钱
            weChatPayUtil.refund(orders.getNumber(), orders.getNumber(),
                    orders.getAmount(), orders.getAmount());
            return null;
        } catch (Exception e) {
            log.error("退款失败，订单号：{}", orders.getNumber(), e);
            throw new OrderBusinessException(REFUND_FAILED);
        }
    }

    /**
     * 判断微信退款所需的配置是否齐全
     * 对应 WeChatPayUtil.getClient() 与 refund() 实际用到的字段
     */
    private boolean isRefundConfigured() {
        return StringUtils.isNotBlank(weChatProperties.getMchid())
                && StringUtils.isNotBlank(weChatProperties.getMchSerialNo())
                && StringUtils.isNotBlank(weChatProperties.getPrivateKeyFilePath())
                && StringUtils.isNotBlank(weChatProperties.getWeChatPayCertFilePath())
                && StringUtils.isNotBlank(weChatProperties.getRefundNotifyUrl());
    }

    /**
     * CAS 更新影响行数为 0 时的统一处理。
     *
     * 之所以还要再查一次订单：影响行数为 0 有两种原因 ——
     *   ① 订单不存在
     *   ② 订单状态已被别人改变（并发冲突）
     * 这两种情况要给用户不同的提示。
     *
     * 注意：这次查询只用来决定"报哪个错"，不参与任何业务判断，
     *      正确性完全由 CAS 的 where 条件保证。
     */
    public void throwCasFailure(Long id) {
        Orders order = orderMapper.getById(id);
        if (order == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        // status = 6 说明这单已经被取消（用户取消 / 商家拒单 / 超时取消）。
        // 不区分是谁取消的：对调用方来说结论都一样 —— 这单不能再操作了。
        if (CANCELLED.equals(order.getStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_ALREADY_CANCELLED);
        }
        throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
    }

    /**
     * 骑手端 CAS 失败的处理（R8 确认送达专用）
     *
     * 【为什么不能直接用上面那个 throwCasFailure】
     * 上面回查用的是 getById —— 不带归属条件。对骑手端来说那是一个信息泄露口子：
     *   骑手 A 拿骑手 B 的订单 id 点"送达" → CAS 影响 0 行 → 回查却能查到这单
     *   （status=4，没被取消）→ 报"订单状态错误"；
     *   而一个根本不存在的 id → 回查是 null → 报"订单不存在"。
     * 两句话不一样，等于告诉对方"这个订单确实存在"。
     *
     * 换成带 rider_id 的回查之后，"不是他的单"和"不存在"都得到 null、
     * 都是同一句"订单不存在" —— 和 R7 详情的口径保持一致。
     *
     * 注意：这次查询只用来决定"报哪个错"，正确性完全由 CAS 的 where 条件保证。
     */
    public void throwRiderCasFailure(Long id, Long riderId) {
        Orders order = orderMapper.getByIdAndRiderId(id, riderId);
        if (order == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        // 和 throwCasFailure 一样：不区分是谁取消的，对调用方来说结论都一样
        if (CANCELLED.equals(order.getStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_ALREADY_CANCELLED);
        }
        throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
    }

}
