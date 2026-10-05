package com.sky.controller.user;

import com.sky.result.Result;
import com.sky.service.UserService;
import com.sky.vo.UserInfoVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/user")
@Slf4j
@Tag(name = "C端的用户接口")
public class UserController {

    @Autowired
    private UserService userService;

    /**
     * 当前用户信息
     * @return
     */
    @GetMapping("/me")
    @Operation(summary = "当前用户信息")
    public Result<UserInfoVO> userMessage(){
        UserInfoVO userInfoVO = userService.getUserInfo();
        return Result.success(userInfoVO);
    }
}
