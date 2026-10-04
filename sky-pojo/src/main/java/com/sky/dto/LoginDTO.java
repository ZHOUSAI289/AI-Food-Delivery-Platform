package com.sky.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一登录入参。
 *
 * 三端共用这一个 DTO：前端只传账号密码，不需要告诉后端自己是什么身份，
 * 由后端去 employee / rider / user 三张表里查出来。
 */
@Data
public class LoginDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 登录账号 */
    private String username;

    /** 明文密码（后端会转成 MD5 再和库里的比） */
    private String password;

}
