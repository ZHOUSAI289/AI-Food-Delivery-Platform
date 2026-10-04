package com.sky.controller.rider;

import com.sky.result.Result;
import com.sky.service.RiderService;
import com.sky.vo.RiderVO;
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
 * 骑手端的账号接口：上线 / 下线（R9）、当前骑手信息（R10）。
 *
 * 【为什么和 OrderController 分成两个类】
 * 这两个接口动的是"骑手自己的账号状态"，跟订单无关。放在 /rider/order 下面
 * 会变成 /rider/order/status，语义不对；分成两个类之后路径正好是
 * /rider/status/{status} 和 /rider/me。
 *
 * 【bean 名为什么可以不写】
 * 这里用默认的 riderController。虽然 admin/RiderController 同名，但那个已经
 * 显式命名成 adminRiderController 了，所以不会冲突（显式命名只在默认名真的
 * 撞车时才必须）。
 *
 * 路径挂在 /rider 下，权限由 JwtTokenInterceptor 的 PATH_ROLE_MAP 保证。
 */
@RestController
@Slf4j
@RequestMapping("/rider")
@Api(tags = "骑手端账号接口")
public class RiderController {

    @Autowired
    private RiderService riderService;

    /**
     * 上线 / 下线（R9）
     *
     * 用法：PUT /rider/status/1
     * status 走路径参数（1 上线 / 0 离线），和管理端的 /admin/.../status/{status} 一致。
     *
     * ⚠️ 这是【接单状态】，不是"账号启停"：
     *   下线 → 只是不接新单，账号照样能登录（骑手可以自己再点上线）
     *   停用 → 是管理员在 R4 干的，那才会让人登不进来
     * 两者以前共用一个字段，是"骑手一下线就登不进自己账号"的根因，现在拆开了。
     *
     * 骑手只能改自己的：id 从 token 取，接口不接受任何骑手 id 参数。
     *
     * @param status 1 上线 / 0 离线
     */
    @PutMapping("/status/{status}")
    @ApiOperation("骑手上线/下线")
    public Result switchOnline(@PathVariable Integer status){
        log.info("骑手上线/下线：status={}", status);
        riderService.switchOnline(status);
        return Result.success();
    }

    /**
     * 当前骑手信息（R10）
     *
     * 用途：骑手端顶栏显示"张三 · 上线中"，并据此渲染上线/下线开关的初始状态。
     * 返回里没有 password（RiderVO 从类型上就没有这个字段）。
     */
    @GetMapping("/me")
    @ApiOperation("当前骑手信息")
    public Result<RiderVO> currentRider(){
        return Result.success(riderService.currentRider());
    }
}
