package com.sky.service;

import com.sky.dto.*;
import com.sky.result.PageResult;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderVO;

public interface OrderService {

//    /**
//     * 用户下单
//     *
//     * @param ordersSubmitDTO
//     * @return
//     */
//    OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO);
//
//    /**
//     * 订单支付
//     *
//     * @param ordersPaymentDTO
//     * @return
//     */
//    OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception;
//
//    /**
//     * 支付成功，修改订单状态
//     *
//     * @param outTradeNo
//     */
//    void paySuccess(String outTradeNo);
//
//    /**
//     * 客户催单
//     *
//     * @param id
//     */
//    void reminder(Long id);
//

    /**
     * 历史订单查询
     * @param ordersPageQueryDTO
     * @return
     */
    PageResult pageQueryOrder(OrdersPageQueryDTO ordersPageQueryDTO);

    /**
     * 各状态的订单查询
     * @return
     */
    OrderStatisticsVO getOrderStatusStatistics();

    /**
     * 订单细节查询
     * @param id
     * @return
     */
    OrderVO details(Long id);

    /**
     * 确认订单
     * @param id
     */
    void orderConfirm(Long id);

    /**
     * 取消订单
     * @param ordersCancelDTO
     * @return 需要提示给用户的信息；无需提示时返回 null
     */
    String orderCancel(OrdersCancelDTO ordersCancelDTO);

    /**
     * 拒绝订单
     * @param ordersRejectionDTO
     * @return 需要提示给用户的信息；无需提示时返回 null
     */
    String orderReject(OrdersRejectionDTO ordersRejectionDTO);

    /**
     * 派送订单
     * @param id
     */
    String orderSend(Long id);

    /**
     * 完成订单
     * @param id
     * @return
     */
    void orderComplete(Long id);

//    /***
//     * 获取订单详情
//     * @param id
//     * @return
//     */
//    OrderVO details(Long id);
}
