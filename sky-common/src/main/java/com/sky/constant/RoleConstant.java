package com.sky.constant;

/**
 * 登录角色常量。
 *
 * 三端（商家管理端 / 骑手端 / 用户端）共用同一个登录接口 POST /login，
 * 登录成功后把角色写进 JWT 的 role claim，后续靠它区分身份。
 *
 * 这个值同时是拦截器 JwtTokenInterceptor 校验「角色与接口路径前缀是否匹配」的依据：
 *   ADMIN -> /admin/**    RIDER -> /rider/**    USER -> /user/**
 * 所以命名必须和这三条路径前缀严格对应，改这里就要同步改拦截器里的映射。
 */
public class RoleConstant {

    /** 商家管理端 */
    public static final String ADMIN = "ADMIN";

    /** 骑手端 */
    public static final String RIDER = "RIDER";

    /** 用户端（C 端顾客） */
    public static final String USER = "USER";

}
