package com.sky.controller.admin;

import com.sky.dto.OrdersCancelDTO;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.dto.OrdersRejectionDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.OrderService;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/order")
@Slf4j
@Tag(name = "商家订单管理相关接口")
public class OrderController {

    @Autowired
    private OrderService orderService;

    /**
     * 订单查询
     * @param ordersPageQueryDTO
     * @return
     */
    @GetMapping("/conditionsearch")
    @Operation(summary = "订单搜索")
    public Result<PageResult> searchOrder(OrdersPageQueryDTO ordersPageQueryDTO){
        log.info("订单分页查询开始");
        PageResult pageResult = orderService.pageQueryOrder(ordersPageQueryDTO);
        return Result.success(pageResult);
    }

    /**
     * 各状态的订单统计
     * @return
     */
    @GetMapping("/statistics")
    @Operation(summary = "各状态的订单统计")
    public Result<OrderStatisticsVO> orderStatusStatistics(){
        log.info("统计各状态的订单数量");
        OrderStatisticsVO orderStatisticsVO = orderService.getOrderStatusStatistics();
        return Result.success(orderStatisticsVO);
    }

    /**
     * 订单细节查询
     * @param id
     * @return
     */
    @GetMapping("/details/{id}")
    @Operation(summary = "查询订单详情")
    public Result orderDetails(@PathVariable Long id){
        OrderVO orderDetailsVO = orderService.details(id);
        return Result.success(orderDetailsVO);
    }

    /**
     * 确认订单
     * @return
     */
    @PutMapping("/confirm/{id}")
    @Operation(summary = "确认订单")
    public Result orderConfirm(@PathVariable Long id){
        orderService.orderConfirm(id);
        return Result.success();
    }

    /**
     * 拒单
     */
    @PutMapping("/rejection")
    @Operation(summary = "拒绝订单")
    public Result orderReject(@RequestBody OrdersRejectionDTO ordersRejectionDTO){
        log.info("拒绝订单：{}",ordersRejectionDTO);
        String tip = orderService.orderReject(ordersRejectionDTO);
        return Result.success(tip);
    }

    /**
     * 取消订单
     */
    @PutMapping("/cancel")
    @Operation(summary = "取消订单")
    public Result orderCancel(@RequestBody OrdersCancelDTO ordersCancelDTO){
        log.info("取消订单：{}",ordersCancelDTO);
        String tip = orderService.orderCancel(ordersCancelDTO);
        return Result.success(tip);
    }

    /**
     * 派送订单
     */
    @PutMapping("/delivery/{id}")
    @Operation(summary = "派送订单")
    public Result orderSend(@PathVariable Long id){
        log.info("派送订单，订单ID:{}",id);
        String tip = orderService.orderSend(id);
        return Result.success(tip);
    }

    /**
     * 管理员代确认送达（派送中 -> 已完成）—— 异常兜底
     *
     * 正常路径是骑手端的 PUT /rider/order/complete/{id}。
     * 这个入口留着的理由见 OrderServiceimplBusiness.orderComplete 的注释：
     * "骑手无法操作时，订单不至于永远卡在派送中"。
     */
    @PutMapping("/complete/{id}")
    @Operation(summary = "管理员代确认送达（兜底）")
    public Result orderComplete(@PathVariable Long id){
        log.info("管理员代确认送达，订单ID：{}",id);
        orderService.orderComplete(id);
        return Result.success();
    }
}
