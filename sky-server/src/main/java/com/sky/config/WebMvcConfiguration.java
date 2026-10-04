package com.sky.config;

import com.sky.interceptor.JwtTokenInterceptor;
import com.sky.json.JacksonObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;

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
                        // knife4j / springfox 接口文档相关，下面 addResourceHandlers 里的静态资源
                        // 会因为不是 HandlerMethod 而自动放行，但 springfox 自己的接口需要显式排除
                        "/doc.html",
                        "/webjars/**",
                        "/swagger-resources",
                        "/swagger-resources/**",
                        "/v2/api-docs",
                        "/v3/api-docs",
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/configuration/ui",
                        "/configuration/security",
                        "/favicon.ico"
                );
    }

    /**
     * 通过knife4j生成接口文档：管理端
     * @return
     */
    @Bean
    public Docket docket1() {
        ApiInfo apiInfo = new ApiInfoBuilder()
                .title("苍穹外卖项目接口文档")
                .version("2.0")
                .description("苍穹外卖项目接口文档")
                .build();
        Docket docket = new Docket(DocumentationType.SWAGGER_2)
                .groupName("管理端接口")
                .apiInfo(apiInfo)
                .select()
                .apis(RequestHandlerSelectors.basePackage("com.sky.controller.admin"))
                .paths(PathSelectors.any())
                .build();
        return docket;
    }

    /**
     * 通过knife4j生成接口文档：用户端
     * @return
     */
    @Bean
    public Docket docket2() {
        ApiInfo apiInfo = new ApiInfoBuilder()
                .title("苍穹外卖项目接口文档")
                .version("2.0")
                .description("苍穹外卖项目接口文档")
                .build();
        Docket docket = new Docket(DocumentationType.SWAGGER_2)
                .groupName("用户端接口")
                .apiInfo(apiInfo)
                .select()
                .apis(RequestHandlerSelectors.basePackage("com.sky.controller.user"))
                .paths(PathSelectors.any())
                .build();
        return docket;
    }

    /**
     * 通过knife4j生成接口文档：公共接口（三端统一登录）
     *
     * basePackage 只能粗到 com.sky.controller，所以用 paths 精确限定到 /login，
     * 否则会把管理端、用户端的接口也重复收进这个分组。
     *
     * @return
     */
    @Bean
    public Docket docket3() {
        ApiInfo apiInfo = new ApiInfoBuilder()
                .title("苍穹外卖项目接口文档")
                .version("2.0")
                .description("苍穹外卖项目接口文档")
                .build();
        Docket docket = new Docket(DocumentationType.SWAGGER_2)
                .groupName("公共接口")
                .apiInfo(apiInfo)
                .select()
                .apis(RequestHandlerSelectors.basePackage("com.sky.controller"))
                .paths(PathSelectors.ant("/login"))
                .build();
        return docket;
    }

    /**
     * 通过knife4j生成接口文档：骑手端
     *
     * 【为什么必须单独一组】
     * 前面三组的 basePackage 分别是 controller.admin、controller.user，
     * 以及 controller（但 paths 只放行 /login）—— 骑手端接口（com.sky.controller.rider）
     * 一组都覆盖不到。少了这组，/rider/** 下的接口在 /doc.html 里一个都看不到。
     * 骑手端本来就有自己的一套前缀和角色，单独成组也和拦截器那张 PATH_ROLE_MAP 对得上。
     *
     * @return
     */
    @Bean
    public Docket docket4() {
        ApiInfo apiInfo = new ApiInfoBuilder()
                .title("苍穹外卖项目接口文档")
                .version("2.0")
                .description("苍穹外卖项目接口文档")
                .build();
        Docket docket = new Docket(DocumentationType.SWAGGER_2)
                .groupName("骑手端接口")
                .apiInfo(apiInfo)
                .select()
                .apis(RequestHandlerSelectors.basePackage("com.sky.controller.rider"))
                .paths(PathSelectors.any())
                .build();
        return docket;
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
    }
}
