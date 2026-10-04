package com.sky.service;

import com.sky.dto.OrdersPageQueryDTO;
import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.result.PageResult;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import com.sky.vo.RepetitionVO;

public interface OrderServiceUser {

    /**
     * 用户下单
     * @param ordersSubmitDTO
     * @return
     * */
    OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO);

    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception;

    /**
     * 历史订单分页查询
     * @param ordersPageQueryDTO
     * @return
     */
    PageResult pageOrder(OrdersPageQueryDTO ordersPageQueryDTO);

    /**
     * 订单详情
     * @param id
     * @return
     */
    OrderVO details(Long id);

    /**
     * 用户取消订单
     * @param id
     */
    String cancelOrder(Long id);

    /**
     * 客户催单
     *
     * @param id
     */
    void reminder(Long id);

    /**
     * 再来一单：把历史订单里的商品重新加入购物车（只加购，不修改订单本身）
     * @param id 订单 id
     * @return 成功加入的明细行数 + 因下架/删除被跳过的商品名
     */
    RepetitionVO repetition(Long id);
}
