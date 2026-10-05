package com.sky.config;

import com.sky.interceptor.JwtTokenInterceptor;
import com.sky.json.JacksonObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;

import java.util.List;

/**
 * 配置类，注册web层相关组件
 */
@Configuration
@Slf4j
public class WebMvcConfiguration extends WebMvcConfigurationSupport {

    @Autowired
    private JwtTokenInterceptor jwtTokenInterceptor;

    /**
     * 注册统一 JWT 拦截器。
     *
     * 策略是「默认拒绝」：拦截 /** ，只有下面显式排除的公开接口才免鉴权。
     * 这样以后新增 /warehouse/** 之类的路径，即使忘了配角色映射，
     * 请求也会被 JwtTokenInterceptor 拒绝，而不是裸奔。
     *
     * （原来这里是两个拦截器各管一个前缀，安全性靠密钥不同，
     *   新增一端就得再抄一个拦截器，容易漏。）
     */
    protected void addInterceptors(InterceptorRegistry registry) {
        log.info("开始注册统一 JWT 拦截器（默认拒绝，公开接口显式排除）...");
        registry.addInterceptor(jwtTokenInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        // 三端统一登录入口，登录时当然还没有 token
                        "/login",
                        // C 端店铺营业状态：顾客在登录前就要能看到"营业中/已打烊"，
                        // 改造前它就是公开接口，这里保持原样，不要顺手收紧
                        "/user/shop/status",
                        // 微信支付结果回调：由微信服务器调用，不可能带我们的 token
                        "/notify/**",
                        // Spring Boot 默认错误页，不能让 401 把真正的报错盖掉
                        "/error",
                        // knife4j / springdoc 接口文档相关，下面 addResourceHandlers 里的静态资源
                        // 会因为不是 HandlerMethod 而自动放行，但 springdoc 自己的接口需要显式排除
                        //
                        // ⚠️ 必须写成 /v3/api-docs/**，只写 /v3/api-docs 盖不住分组地址
                        //    （/v3/api-docs/管理端接口 这种带子路径的会 401）。
                        //    springfox 时代的 /v2/api-docs、/swagger-resources/** 已随旧内核一起删除。
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/webjars/**",
                        "/doc.html",
                        "/favicon.ico"
                );
    }

    /**
     * 接口文档分组（springdoc）。
     *
     * 【为什么从 Docket 换成 GroupedOpenApi】
     * springfox 3.0.0 基于 javax，在 Boot 3 下无法启动，且早已停止维护，
     * 所以整套换成 springdoc（由 Knife4j 4.x 传递引入）。
     * 分组语义和原来一一对应，四个组一个都不能少 —— 少了骑手端那组，
     * /rider/** 的接口在 /doc.html 里就看不见了。
     *
     * @return 管理端接口分组
     */
    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder().group("管理端接口")
                .packagesToScan("com.sky.controller.admin").build();
    }

    /**
     * 接口文档分组：用户端（C 端）
     *
     * @return 用户端接口分组
     */
    @Bean
    public GroupedOpenApi userApi() {
        return GroupedOpenApi.builder().group("用户端接口")
                .packagesToScan("com.sky.controller.user").build();
    }

    /**
     * 接口文档分组：公共接口（三端统一登录）
     *
     * packagesToScan 只能粗到 com.sky.controller，所以用 pathsToMatch 精确限定到 /login，
     * 否则会把管理端、用户端的接口也重复收进这个分组。
     *
     * @return 公共接口分组
     */
    @Bean
    public GroupedOpenApi commonApi() {
        return GroupedOpenApi.builder().group("公共接口")
                .packagesToScan("com.sky.controller")
                .pathsToMatch("/login").build();
    }

    /**
     * 接口文档分组：骑手端
     *
     * 【为什么必须单独一组】
     * 前面三组的 packagesToScan 分别是 controller.admin、controller.user，
     * 以及 controller（但 pathsToMatch 只放行 /login）—— 骑手端接口（com.sky.controller.rider）
     * 一组都覆盖不到。少了这组，/rider/** 下的接口在 /doc.html 里一个都看不到。
     * 骑手端本来就有自己的一套前缀和角色，单独成组也和拦截器那张 PATH_ROLE_MAP 对得上。
     *
     * @return 骑手端接口分组
     */
    @Bean
    public GroupedOpenApi riderApi() {
        return GroupedOpenApi.builder().group("骑手端接口")
                .packagesToScan("com.sky.controller.rider").build();
    }

    /**
     * 设置静态资源映射
     * @param registry
     */
    protected void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/doc.html").addResourceLocations("classpath:/META-INF/resources/");
        registry.addResourceHandler("/webjars/**").addResourceLocations("classpath:/META-INF/resources/webjars/");
    }

    /**
     * 扩展Spring消息转换器:对数据，类似日期进行格式处理
     * @param converters
     */
    protected void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        log.info("扩展消息转换器...");
        //创建消息转换器对象
        MappingJackson2HttpMessageConverter Converter = new MappingJackson2HttpMessageConverter();
        //设置对象转换器，底层使用Jackson将Java对象转为json
        Converter.setObjectMapper(new JacksonObjectMapper());
        //将上面的消息转换器对象追加到mvc框架的转换器集合中
        converters.add(0,Converter); //参数：索引(索引0表示排在转换器的第一位，优先使用的)，追加的位置
        //仅诊断用：springdoc 的 /v3/api-docs 返回 byte[]，自定义 Jackson 转换器排在第 0 位会把它
        //序列化成 base64 字符串，Knife4j 界面因此初始化失败（官方 FAQ / springdoc issue 2143）。
        converters.add(0, new org.springframework.http.converter.ByteArrayHttpMessageConverter());
    }
}
