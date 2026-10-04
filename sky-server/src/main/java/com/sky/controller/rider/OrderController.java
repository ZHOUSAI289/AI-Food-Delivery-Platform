package com.sky.controller.rider;

import com.sky.dto.RiderOrderPageDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.RiderService;
import com.sky.vo.OrderVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 骑手端的订单接口：我的配送单（R6）、配送单详情（R7）、确认送达（R8）。
 *
 * 【类名为什么从 RiderController 改成 OrderController】
 * 这个类管的是"订单"。原来的名字会和 admin/RiderController（管理端骑手管理）、
 * 以及 R9/R10 的骑手账号接口（/rider/status、/rider/me）混成一团 ——
 * 三个都叫"骑手"，但它们分别是管理员的、骑手本人的订单、和骑手本人的账号。
 *
 * 【bean 名为什么必须显式写成 riderOrderController】
 * admin/OrderController 是裸 @RestController，默认 bean 名就是 orderController。
 * 不显式命名的话两个同名类会撞成 ConflictingBeanDefinitionException，**应用起不来**。
 * 项目里同名类都是显式命名的（userOrderController、adminShopController）。
 *
 * 路径挂在 /rider/order 下：JwtTokenInterceptor 的 PATH_ROLE_MAP 里 /rider → RIDER，
 * 所以权限自动生效，这里不需要任何额外配置。用 ADMIN 的 token 打会拿到 403。
 */
@RestController("riderOrderController")
@Slf4j
@RequestMapping("/rider/order")
@Api(tags = "骑手端订单接口")
public class OrderController {

    @Autowired
    private RiderService riderService;

    /**
     * 我的配送单列表（R6）
     *
     * 分页参数走 query string（GET + 无注解参数，Spring 直接绑定到 DTO 字段），
     * 和管理端那几个分页接口写法一致。status 不传 = 全部指派给我的单。
     *
     * @param riderOrderPageDTO page / pageSize / status
     */
    @GetMapping("/list")
    @ApiOperation("骑手配送单列表")
    public Result<PageResult> riderOrderList(RiderOrderPageDTO riderOrderPageDTO){
        log.info("骑手配送单列表，参数：{}", riderOrderPageDTO);
        return Result.success(riderService.pageQueryOrderList(riderOrderPageDTO));
    }

    /**
     * 配送单详情（R7）
     *
     * ⚠️ @PathVariable 不能漏：{id} 只有标了它才会被注入。漏了的话这个简单的
     * Long 参数会被当成【查询参数】(?id=) 处理，路径里的 337 永远进不来，
     * 表现是"任何订单都提示订单不存在"。
     *
     * @param id 订单 id
     */
    @GetMapping("/detail/{id}")
    @ApiOperation("配送单详情")
    public Result<OrderVO> getOrderDetail(@PathVariable Long id){
        return Result.success(riderService.getOrderDetail(id));
    }

    /**
     * 确认送达（R8）：4 派送中 → 5 已完成
     *
     * 幂等由 Service 层的 CAS 保证：重复点第二次会失败，且不会覆盖 delivery_time。
     *
     * @param id 订单 id
     */
    @PutMapping("/complete/{id}")
    @ApiOperation("确认送达")
    public Result completeOrder(@PathVariable Long id){
        log.info("骑手确认送达，订单ID：{}", id);
        riderService.completeOrder(id);
        return Result.success();
    }
}
