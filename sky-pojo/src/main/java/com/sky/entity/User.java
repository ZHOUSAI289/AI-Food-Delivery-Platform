package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    // 登录账号（唯一），统一登录时用它认人
    private String username;

    // 登录密码，存 MD5 十六进制（和 employee 表一致）
    private String password;

    //微信用户唯一标识（小程序登录已停用，字段保留）
    private String openid;

    //姓名
    private String name;

    //手机号
    private String phone;

    //性别 0 女 1 男
    private String sex;

    //身份证号
    private String idNumber;

    //头像
    private String avatar;

    //状态 0禁用 1启用
    private Integer status;

    //注册时间
    private LocalDateTime createTime;
}
