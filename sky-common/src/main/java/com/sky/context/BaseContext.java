package com.sky.context;

/**
 * 当前登录人的线程上下文。
 *
 * 拦截器 JwtTokenInterceptor 校验完 token 后，把 id 和 role 存进来，
 * 这样 Service 层不用层层传参就能知道「当前是谁在操作」。
 *
 * 注意：用的是 ThreadLocal，必须在请求结束时清理（拦截器的 afterCompletion），
 * 否则 Tomcat 线程池复用线程时会把上一个请求的身份带给下一个请求。
 */
public class BaseContext {

    /** 当前登录人的 id（employee.id / rider.id / user.id） */
    public static ThreadLocal<Long> threadLocal = new ThreadLocal<>();

    /** 当前登录人的角色（RoleConstant.ADMIN / RIDER / USER） */
    public static ThreadLocal<String> roleThreadLocal = new ThreadLocal<>();

    public static void setCurrentId(Long id) {
        threadLocal.set(id);
    }

    public static Long getCurrentId() {
        return threadLocal.get();
    }

    public static void removeCurrentId() {
        threadLocal.remove();
    }

    public static void setCurrentRole(String role) {
        roleThreadLocal.set(role);
    }

    public static String getCurrentRole() {
        return roleThreadLocal.get();
    }

    public static void removeCurrentRole() {
        roleThreadLocal.remove();
    }

}
