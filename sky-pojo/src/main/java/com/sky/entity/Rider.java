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
public class Rider implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    // 登录账号（唯一），统一登录时用它认人
    private String username;

    // 登录密码，存 MD5 十六进制（和 employee 表一致）
    private String password;

    private String name;

    private String phone;

    // 账号状态：1 启用 / 0 停用。只有管理员能改（R4），登录时校验的就是它
    private Integer status;

    // 接单状态：1 上线 / 0 离线。只有骑手本人能改（R9），派单时按它筛人
    //
    // 【为什么必须和 status 分开】
    // 这两件事以前挤在 status 一个字段上，直接后果是"骑手点一下下线就再也登不进
    // 自己的账号"：下线把 status 写成 0，而登录又拦 status=0（报"账号被锁定"），
    // 他想改回来必须先能登录 —— 把自己锁在门外且无法自救。见接口文档 §2.1。
    private Integer online;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
