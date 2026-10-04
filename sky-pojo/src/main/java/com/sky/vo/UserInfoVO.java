package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class UserInfoVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    // 登录账号（唯一），统一登录时用它认人
    private String username;

    //昵称
    private String name;

    //手机号
    private String phone;

    //头像
    private String avatar;

    //性别 0 女 1 男
    private String sex;
}
