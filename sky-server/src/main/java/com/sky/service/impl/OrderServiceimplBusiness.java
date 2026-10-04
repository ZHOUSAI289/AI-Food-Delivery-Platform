package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.dto.OrdersCancelDTO;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.dto.OrdersRejectionDTO;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.entity.Rider;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.RiderMapper;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.service.OrderSupport;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static com.sky.entity.Orders.*;

/**
 * 商家端订单业务实现
 *
 * 状态流转：1 待付款 -> 2 待接单 -> 3 已接单 -> 4 派送中 -> 5 已完成
 *           2 / 3 -> 6 已取消（拒单 / 取消）
 *
 * 所有状态流转都使用「带状态条件的更新（CAS）」：
 *   update ... where id = ? and status = 期望状态
 * 然后判断影响行数：
 *   1 → 更新成功
 *   0 → 状态已被别人改变（并发冲突）→ 抛「订单状态错误」
 * 这样并发时只有一个请求能成功，且不需要任何分布式锁。
 *
 * 退款逻辑和 CAS 失败分类在 OrderSupport 里，管理端和 C 端共用同一份实现。
 */
@Service
@Slf4j
public class OrderServiceimplBusiness implements OrderService {
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private RiderMapper riderMapper;
    @Autowired
    private OrderSupport orderSupport;

    /**
     * 订单查询
     * @param ordersPageQueryDTO
     * @return
     */
    public PageResult pageQueryOrder(OrdersPageQueryDTO ordersPageQueryDTO){
        //设置分页
        PageHelper.startPage(ordersPageQueryDTO.getPage(),ordersPageQueryDTO.getPageSize());
        List<OrderVO> list = new ArrayList<>();

        Page<OrderVO> page = orderMapper.pageQuery(ordersPageQueryDTO);

        if (page != null && page.size() > 0){
            for (OrderVO orders : page) {
                Long orderId = orders.getId();
                List<OrderDetail> orderDetailList = orderMapper.getByOrderId(orderId);

                orders.setOrderDetailList(orderDetailList);

                StringBuilder orderDish = new StringBuilder();
                for (OrderDetail orderDetail : orderDetailList) {
                    orderDish.append(orderDetail.getName())
                            .append("*")
                            .append(orderDetail.getNumber())
                            .append(";");
                }

                String orderDishs = orderDish.toString();
                orders.setOrderDishes(orderDishs);
                list.add(orders);
            }
        }
        return new PageResult(page.getTotal(),list);
    }

    /**
     * 各状态的订单查询
     * @return
     */
    public OrderStatisticsVO getOrderStatusStatistics() {
        OrderStatisticsVO orderStatisticsVO = new OrderStatisticsVO();
        orderStatisticsVO.setToBeConfirmed(orderMapper.countStatus(TO_BE_CONFIRMED));
        orderStatisticsVO.setConfirmed(orderMapper.countStatus(CONFIRMED));
        orderStatisticsVO.setDeliveryInProgress(orderMapper.countStatus(DELIVERY_IN_PROGRESS));
        return orderStatisticsVO;
    }

    /**
     * 订单细节查询
     * @param id
     * @return
     */
    public OrderVO details(Long id) {
        Orders orders = orderMapper.getById(id);
        if(orders == null){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        List<OrderDetail> orderDetailList = orderMapper.getByOrderId(orders.getId());
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders,orderVO);
        orderVO.setOrderDetailList(orderDetailList);
        return orderVO;
    }

    /**
     * 接单（待接单 -> 已接单）
     * 直接用 CAS 更新，不再先查订单：正常路径只有 1 条 SQL
     * @param id
     */
    public void orderConfirm(Long id) {
        Orders updateOrder = Orders.builder()
                .id(id)
                .status(CONFIRMED)
                .build();
        if (orderMapper.updateConfirmOrder(updateOrder) == 0) {
            orderSupport.throwCasFailure(id);
        }
    }

    /**
     * 取消订单（已接单 -> 已取消）
     * 顺序：先退款（靠"退款单号=订单号"的幂等性防重复退款），再 CAS 改状态
     * @param ordersCancelDTO
     * @return 需要提示给用户的信息；无需提示时返回 null
     */
    public String orderCancel(OrdersCancelDTO ordersCancelDTO) {
        // 这次查询是必须的：退款需要订单号、金额、支付状态
        Orders orders = orderMapper.getById(ordersCancelDTO.getId());
        if(orders == null){
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        // 提前挡一下，避免对一个不该取消的订单发起退款
        if(!CONFIRMED.equals(orders.getStatus())){
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        // ① 先退款
        String tip = orderSupport.refundIfNeeded(orders);

        // ② 再 CAS 改状态：并发时只有一个请求能改成功
        Orders updateOrder = Orders.builder()
                .id(orders.getId())
                .status(CANCELLED)
                .cancelReason(ordersCancelDTO.getCancelReason())
                .cancelTime(LocalDateTime.now())
                .build();
        if (orderMapper.updateCancelOrder(updateOrder) == 0) {
            orderSupport.throwCasFailure(orders.getId());
        }
        return tip;
    }

    /**
     * 拒绝订单（待接单 -> 已取消）
     * 顺序同取消订单：先退款，再 CAS 改状态
     * @param ordersRejectionDTO
     * @return 需要提示给用户的信息；无需提示时返回 null
     */
    public String orderReject(OrdersRejectionDTO ordersRejectionDTO) {
        Orders orders = orderMapper.getById(ordersRejectionDTO.getId());
        if (orders == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }
        if (!TO_BE_CONFIRMED.equals(orders.getStatus())) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        // ① 先退款
        String tip = orderSupport.refundIfNeeded(orders);

        // ② 再 CAS 改状态
        Orders updateOrder = Orders.builder()
                .id(orders.getId())
                .status(CANCELLED)
                .rejectionReason(ordersRejectionDTO.getRejectionReason())
                .cancelTime(LocalDateTime.now())
                .build();
        if (orderMapper.updateRejectOrder(updateOrder) == 0) {
            orderSupport.throwCasFailure(orders.getId());
        }
        return tip;
    }

    /**
     * 派送订单（已接单 -> 派送中），同时随机分配一名可接单的骑手
     *
     * 顺序说明：先选骑手（纯内存操作，没有副作用），再一条 CAS SQL 同时改状态并绑定骑手。
     * 如果 CAS 影响行数为 0（订单已被别人派走），选好的骑手不会被写进数据库，没有任何副作用。
     *
     * 【挑人的条件是"账号启用【且】已经上线"】
     * 以前这里只按 status 筛，纯粹是因为"上线"和"启用"当时共用一个字段；
     * 现在拆成 status / online 两列了，只按 status 就会把所有离线的骑手一起派上单。
     * 两个条件写死在 RiderMapper.listAvailable 里，调用方没有把筛选条件写歪的余地。
     *
     * @param id
     * @return 提示字符串，形如 "已派送给骑手张三（13800000001）"
     */
    public String orderSend(Long id) {
        List<Rider> riders = riderMapper.listAvailable();
        if (riders == null || riders.isEmpty()) {
            throw new OrderBusinessException(MessageConstant.RIDER_NOT_FOUND);
        }

        // 随机挑一个骑手
        Rider rider = riders.get(ThreadLocalRandom.current().nextInt(riders.size()));

        Orders updateOrder = Orders.builder()
                .id(id)
                .status(DELIVERY_IN_PROGRESS)
                .estimatedDeliveryTime(LocalDateTime.now().plusHours(1))
                .riderId(rider.getId())
                .riderName(rider.getName())
                .riderPhone(rider.getPhone())
                .build();
        if (orderMapper.updateDeliveryOrder(updateOrder) == 0) {
            orderSupport.throwCasFailure(id);
        }

        log.info("订单{}已派送给骑手{}（{}）", id, rider.getName(), rider.getPhone());
        return "已派送给骑手" + rider.getName() + "（" + rider.getPhone() + "）";
    }

    /**
     * 管理员代确认送达（派送中 -> 已完成）—— 【异常兜底】，不是正常路径。
     *
     * 【正常路径是骑手端的 R8：PUT /rider/order/complete/{id}】
     * 这个入口保留下来是当后路用的：骑手忘了点、手机没电、账号被停用的时候，
     * 如果管理端也没有入口，一单进了"派送中"就永远卡在那里，用户那边一直显示派送中，
     * 而全系统没有任何界面能救它（凌晨那个"自动完成"已经按文档 §2.3 删掉了）。
     *
     * 【为什么它和 R8 是两条不同的 SQL，不能合并】
     * R8 的 where 里有 rider_id = 当前骑手（归属校验），管理员没有骑手身份，
     * 拿不到这个条件 —— 硬合并只会把归属校验删掉，那是更大的问题。
     *
     * 【已知不足：没有操作留痕】
     * 谁在什么时候代确认的，库里没记（orders 没有对应字段）。要做到可审计需要加列，
     * 建议和"骑手离职时的订单改派"一起排期，不塞进这一期。
     *
     * 状态条件由 CAS 保证：updateCompleteOrder 的 where 是 status = 4，
     * 所以它只能对"派送中"的单生效，不会把别的状态的单推着走。
     *
     * @param id
     */
    public void orderComplete(Long id) {
        Orders updateOrder = Orders.builder()
                .id(id)
                .status(COMPLETED)
                .deliveryTime(LocalDateTime.now())
                .build();
        if (orderMapper.updateCompleteOrder(updateOrder) == 0) {
            orderSupport.throwCasFailure(id);
        }
    }

}
