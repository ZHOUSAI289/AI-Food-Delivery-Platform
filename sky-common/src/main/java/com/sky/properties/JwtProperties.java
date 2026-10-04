package com.sky.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sky.jwt")
@Data
public class JwtProperties {

    /**
     * 统一登录（三端共用）：一个密钥、一个请求头，身份靠 token 里的 role claim 区分
     */
    private String secretKey;
    private long ttl;
    private String tokenName;

    /**
     * 管理端员工生成jwt令牌相关配置
     * @deprecated 已由上面的统一登录配置取代，保留是为了兼容还没删掉的旧代码
     */
    private String adminSecretKey;
    private long adminTtl;
    private String adminTokenName;

    /**
     * 用户端微信用户生成jwt令牌相关配置
     */
    private String userSecretKey;
    private long userTtl;
    private String userTokenName;

}
