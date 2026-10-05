package com.sky.interceptor;

import com.sky.constant.JwtClaimsConstant;
import com.sky.constant.RoleConstant;
import com.sky.context.BaseContext;
import com.sky.properties.JwtProperties;
import com.sky.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一 JWT 校验拦截器（三端共用，取代原来的 JwtTokenAdminInterceptor / JwtTokenUserInterceptor）。
 *
 * 校验三件事：
 *   1. token 能不能用统一密钥解析出来（签名对不对、过期没有）
 *   2. token 里的角色和要访问的接口前缀对不对得上
 *   3. 通过后把 id 和 role 放进 ThreadLocal，供 Service 层取用
 *
 * 为什么合并成一个：原来两个拦截器各自绑一个路径前缀、各自一把密钥，
 * 安全性来自"密钥不同"，一旦新增一端（骑手端）就要再抄一个拦截器，容易漏。
 * 现在改成"一个密钥 + role claim"，安全性集中在下面 PATH_ROLE_MAP 这一张表上，
 * 只需要审计这一处。
 *
 * 注意：注册路径是 /**（默认拒绝），公开接口在 WebMvcConfiguration 里显式排除。
 */
@Component
@Slf4j
public class JwtTokenInterceptor implements HandlerInterceptor {

    /**
     * 接口路径前缀 -> 允许访问的角色。
     *
     * 这是整套权限控制唯一的防线：访问 /admin/** 的人，token 里的 role 必须是 ADMIN。
     * 新增一个端（比如 /warehouse/**）时必须同时在这里加映射，
     * 否则该路径下的所有请求都会因为"匹配不到角色"而被拒绝——宁可不放行，也不能裸奔。
     */
    private static final Map<String, String> PATH_ROLE_MAP = new LinkedHashMap<>();

    static {
        PATH_ROLE_MAP.put("/admin", RoleConstant.ADMIN);
        PATH_ROLE_MAP.put("/rider", RoleConstant.RIDER);
        PATH_ROLE_MAP.put("/user", RoleConstant.USER);
    }

    /** token 无效 / 缺失 / 过期 */
    private static final int UNAUTHORIZED = 401;

    /** token 有效但角色不对 */
    private static final int FORBIDDEN = 403;

    @Autowired
    private JwtProperties jwtProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        // 拦截到静态资源等非 Controller 方法时直接放行（doc.html、webjars 都走这里）
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        String uri = request.getRequestURI();
        String token = request.getHeader(jwtProperties.getTokenName());

        Long id;
        String role;
        try {
            Claims claims = JwtUtil.parseJWT(jwtProperties.getSecretKey(), token);
            role = claims.get(JwtClaimsConstant.ROLE) == null
                    ? null
                    : claims.get(JwtClaimsConstant.ROLE).toString();
            id = Long.valueOf(claims.get(JwtClaimsConstant.ID).toString());
        } catch (Exception ex) {
            // 签名不对、token 过期、token 缺失，或者是个"老格式"没有 id claim 的 token
            log.warn("令牌校验失败，拒绝访问 {}：{}", uri, ex.getMessage());
            response.setStatus(UNAUTHORIZED);
            return false;
        }

        // 这张表决定每个前缀要什么角色；查不到就说明是个没纳入管控的路径，一律拒绝
        String requiredRole = matchRequiredRole(uri);
        if (requiredRole == null) {
            log.warn("拒绝访问未纳入角色映射的路径：{}", uri);
            response.setStatus(FORBIDDEN);
            return false;
        }
        if (!requiredRole.equals(role)) {
            // 用 403 而不是 401：token 本身没问题，是权限不够。
            // 前端收到 403 不能清 token 跳登录，否则用户会莫名其妙被踢出去。
            log.warn("越权访问：{}端(id={})尝试访问 {}，该路径需要{}端", role, id, uri, requiredRole);
            response.setStatus(FORBIDDEN);
            return false;
        }

        BaseContext.setCurrentId(id);
        BaseContext.setCurrentRole(role);
        return true;
    }

    /**
     * 请求结束时清理 ThreadLocal。
     *
     * 不清理的话，Tomcat 线程池复用线程时会把上一个请求的身份带给下一个请求——
     * 这是很典型的"串号"事故。（原代码里 removeCurrentId 定义了但从来没被调用过）
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        BaseContext.removeCurrentId();
        BaseContext.removeCurrentRole();
    }

    /**
     * 按路径前缀找需要的角色。
     * 用 equals(前缀) 或 startsWith(前缀 + "/") 匹配，避免 /administrator 这种被误判成 /admin。
     */
    private String matchRequiredRole(String uri) {
        for (Map.Entry<String, String> entry : PATH_ROLE_MAP.entrySet()) {
            String prefix = entry.getKey();
            if (uri.equals(prefix) || uri.startsWith(prefix + "/")) {
                return entry.getValue();
            }
        }
        return null;
    }

}
