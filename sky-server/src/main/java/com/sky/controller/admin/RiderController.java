package com.sky.controller.admin;

import com.sky.dto.RiderDTO;
import com.sky.dto.RiderPageQueryDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.RiderServiceBusiness;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 骑手管理（管理端）。接口清单见 docs/superpowers/specs/2026-09-26-rider-api-design.md §5.1。
 * 目前实现了 R1（分页查询）、R2（新增）、R3（编辑）、R4（启停）。
 *
 * 【Bean 名为什么必须显式写成 adminRiderController】
 * 骑手端还会有 controller/rider/RiderController（R9 上线/下线、R10 当前骑手信息）。
 * 两个同名类在 Spring 里的默认 Bean 名都是 riderController，会抛
 * ConflictingBeanDefinitionException，**应用直接起不来**。
 * 项目里碰到同名类都是显式命名（user/CategoryController → "userCategoryController"、
 * admin/ShopController → "adminShopController"），这里沿用。
 *
 * 路径挂在 /admin/rider 下：JwtTokenInterceptor 的 PATH_ROLE_MAP 里 /admin → ADMIN，
 * 所以权限自动生效，不需要额外配置。
 */
@Slf4j
@RestController("adminRiderController")
@RequestMapping("/admin/rider")
@Api(tags = "管理端的骑手管理接口")
public class RiderController {

    @Autowired
    private RiderServiceBusiness riderServiceBusiness;

    /**
     * 骑手分页查询
     *
     * 用法：GET /admin/rider/page?page=1&pageSize=10&name=张
     *
     * 分页参数走 query string（GET + 无注解参数，Spring 直接绑定到 DTO 字段），
     * 与管理端另外四个分页接口（employee / category / dish / setmeal）写法一致。
     *
     * 入参用 @ApiOperation 标出来是为了 Knife4j 文档里能填参数；
     * 出参是 PageResult { total, records: RiderVO[] }，RiderVO 里没有 password。
     *
     * @param riderPageQueryDTO
     * @return
     */
    @GetMapping("/page")
    @ApiOperation("骑手的分页查询")
    public Result<PageResult> page(RiderPageQueryDTO riderPageQueryDTO) {
        log.info("骑手分页查询，参数：{}", riderPageQueryDTO);
        PageResult pageResult = riderServiceBusiness.pageQuery(riderPageQueryDTO);
        return Result.success(pageResult);
    }

    /**
     * 新增骑手
     * @param riderDTO
     * @return
     */
    @PostMapping
    @ApiOperation("新增骑手")
    public Result addRider(@RequestBody RiderDTO riderDTO){
        log.info("新增骑手：{}",riderDTO);
        riderServiceBusiness.add(riderDTO);
        return Result.success();
    }

    /**
     * 编辑骑手
     * @param riderDTO
     * @return
     */
    @PutMapping
    @ApiOperation("编辑骑手")
    public Result updateRider(@RequestBody RiderDTO riderDTO){
        log.info("编辑骑手：{}",riderDTO);
        riderServiceBusiness.update(riderDTO);
        return Result.success();
    }

    /**
     * 启用 / 停用骑手账号
     *
     * 用法：POST /admin/rider/status/0?id=5
     * status 走路径参数（1 启用 / 0 停用），id 走 query 参数 ——
     * 和 /admin/employee/status/{status}、/admin/category/status/{status} 完全一致。
     *
     * ⚠️ 这是【账号启停】，不是"骑手上线/下线"：
     *   停用  → 该骑手不能再登录，也不会被派单（管理员操作）
     *   下线  → 只是不接新单，仍能登录（骑手自己在 R9 的 /rider/status/{status} 里操作）
     * 两者以前共用一个字段，是"骑手一下线就登不进自己账号"的根因，现在拆开了。
     *
     * @param status
     * @param id
     * @return
     */
    @PostMapping("/status/{status}")
    @ApiOperation("启用 / 停用骑手账号")
    public Result startOrStop(@PathVariable Integer status, Long id){
        log.info("启用/停用骑手账号：status={}，id={}", status, id);
        riderServiceBusiness.startOrStop(status, id);
        return Result.success();
    }
}
