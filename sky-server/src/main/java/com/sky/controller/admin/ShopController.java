package com.sky.controller.admin;

import com.sky.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

@RestController("adminShopController") //指定类在Bean里面的名字
@Slf4j
@RequestMapping("/admin/shop")
@Tag(name = "店铺相关接口")
public class ShopController {

    @Autowired
    private RedisTemplate redisTemplate;

    /**
     * 设置店铺营业状态
     * @param status
     * @return
     */
    @PutMapping("/{status}")
    @Operation(summary = "设置店铺状态")
    public Result setStatus(@PathVariable Integer status){
        log.info("设置店铺营业状态：{}",status == 1 ? "营业中" : "打烊中");
        redisTemplate.opsForValue().set("SHOP_STATUS",status);
        return Result.success();
    }

    /**
     * 获取店铺营业状态
     * @return
     */
    @GetMapping("/status")
    @Operation(summary = "获取店铺的营业状态")
    public Result<Integer> getStatus(){
        Integer status = (Integer) redisTemplate.opsForValue().get("SHOP_STATUS");
        log.info("获取到店铺的营业状态为：{}",status == 1 ? "营业中" : "打烊中");
        return Result.success(status);
    }
}
