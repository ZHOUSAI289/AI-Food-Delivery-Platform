package com.sky.controller.user;

import com.sky.dto.OrdersPageQueryDTO;
import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.OrderService;
import com.sky.service.OrderServiceUser;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import com.sky.vo.RepetitionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

@RestController("userOrderController")
@Slf4j
@Tag(name = "C端订单接口")
@RequestMapping("/user/order")
public class OrderController {

    @Autowired
    private OrderServiceUser orderServiceUser;

    /**
     * 用户下单
     * @param ordersSubmitDTO
     * @return
     */
    @PostMapping("/submit")
    @Operation(summary = "用户下单")
    public Result<OrderSubmitVO> submit(@RequestBody OrdersSubmitDTO ordersSubmitDTO){
        log.info("用户下单,参数为：{}", ordersSubmitDTO);
        OrderSubmitVO orderSubmitVO = orderServiceUser.submitOrder(ordersSubmitDTO);
        return Result.success(orderSubmitVO);
    }

    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    @PutMapping("/payment")
    @Operation(summary = "订单支付")
    public Result<OrderPaymentVO> payment(@RequestBody OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        log.info("订单支付：{}", ordersPaymentDTO);
        OrderPaymentVO orderPaymentVO = orderServiceUser.payment(ordersPaymentDTO);
        log.info("生成预支付交易单：{}", orderPaymentVO);
        return Result.success(orderPaymentVO);
    }

    /**
     * 客户催单
     * @param id
     * @return
     */
    @GetMapping("/reminder/{id}")
    @Operation(summary = "客户催单")
    public Result reminder(@PathVariable("id") Long id){
        orderServiceUser.reminder(id);
        return Result.success();
    }

    /**
     * 历史订单查询
     *
     * @param ordersPageQueryDTO
     * @return
     */
    @GetMapping("/historyOrders")
    @Operation(summary = "历史订单查询")
    public Result<PageResult> page(OrdersPageQueryDTO ordersPageQueryDTO) {
        PageResult pageResult = orderServiceUser.pageOrder(ordersPageQueryDTO);
        return Result.success(pageResult);
    }

    /**
     * 查询订单详情
     * @param id
     * @return
     */
    @GetMapping("/orderDetail/{id}")
    @Operation(summary = "查询订单详情")
    public Result<OrderVO> details(@PathVariable Long id){
        OrderVO orderVO = orderServiceUser.details(id);
        return Result.success(orderVO);
    }

    /**
     * 用户取消订单
     * @param id
     * @return
     */
    @PutMapping("/cancel/{id}")
    @Operation(summary = "用户取消订单")
    public Result orderCancel(@PathVariable Long id){

        String tip = orderServiceUser.cancelOrder(id);
        return Result.success(tip);
    }

    /**
     * 再来一单：把历史订单里的商品重新加入购物车
     * @param id
     * @return
     */
    @PostMapping("/repetition/{id}")
    @Operation(summary = "再来一单")
    public Result<RepetitionVO> repetition(@PathVariable Long id){
        log.info("再来一单：{}", id);
        RepetitionVO repetitionVO = orderServiceUser.repetition(id);
        return Result.success(repetitionVO);
    }
}
