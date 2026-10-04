package com.sky.controller;

import com.sky.dto.LoginDTO;
import com.sky.result.Result;
import com.sky.service.LoginService;
import com.sky.vo.LoginVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一登录接口。
 *
 * 三端（商家管理端 / 骑手端 / 用户端）共用这一个入口，前端只传账号密码，
 * 后端自动识别身份并在 token 里带上 role。
 *
 * 路径是 /login 而不是 /admin/xxx/login：它不属于任何一个端，
 * 所以既不在 /admin/** 也不在 /rider/** 或 /user/** 的拦截范围内，天然免鉴权。
 */
@RestController
@RequestMapping("/login")
@Api(tags = "统一登录接口")
@Slf4j
public class LoginController {

    @Autowired
    private LoginService loginService;

    /**
     * 三端统一登录
     *
     * @param loginDTO 账号 + 明文密码
     * @return id、姓名、账号、角色、token
     */
    @PostMapping
    @ApiOperation("三端统一登录")
    public Result<LoginVO> login(@RequestBody LoginDTO loginDTO) {
        log.info("统一登录，账号：{}", loginDTO.getUsername());
        return Result.success(loginService.login(loginDTO));
    }

}
