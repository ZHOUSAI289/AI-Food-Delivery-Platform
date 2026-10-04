package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一登录返回结果。
 *
 * 前端拿 role 决定：跳哪个首页、侧边栏显示哪些菜单。
 * 拿 token 后续放进请求头 token 里。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 各端自己表里的主键（employee.id / rider.id / user.id） */
    private Long id;

    /** 登录账号 */
    private String username;

    /** 姓名 */
    private String name;

    /** 角色：ADMIN / RIDER / USER */
    private String role;

    /** JWT 令牌 */
    private String token;

}
