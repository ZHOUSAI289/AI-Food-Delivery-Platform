package com.sky.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sky.constant.JwtClaimsConstant;
import com.sky.constant.RoleConstant;
import com.sky.properties.JwtProperties;
import com.sky.utils.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端「员工管理」的回归测试。
 *
 * 【为什么单独给这个模块建一个测试类】
 * 它此前【完全没测过】，而恰好带着两个很典型的坑：
 *   1. Mapper XML 里 `concat` 写成了 `contact` —— 一带 name 参数就 500。
 *      也就是说"按姓名搜索"这条最常用的路径，从来没被跑通过一次。
 *   2. 分页接口直接返回 `Employee` 实体（还配了 `select *`），把真实的密码 MD5 发给了前端。
 * 两个都不是"逻辑写错"，而是"没人验证过" —— 所以这里按【接口行为】把它钉住。
 *
 * 【为什么断言里查的是"响应原文里没有 password"】
 * 这类泄漏靠"记得别 set"是防不住的：加个 @JsonIgnore、或在 Service 里 setNull，
 * 都只是补丁（原来的 getById 就是把 password 回显成 "****"）。
 * 真正的修法是返回一个【类型上就没有 password】的 VO，
 * 所以这里必须断言响应原文里连这个字段名都不出现 —— 只有这样才能证明用的是后者。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Transactional
class EmployeeServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("分页查询：按姓名搜索要能真正命中（concat 曾被写成 contact，一带 name 就 500）")
    void pageQuery_withName_shouldMatch() throws Exception {
        // 自己插一条，避免依赖库里现有员工的姓名 —— 也顺便证明 LIKE 真的在过滤，
        // 而不只是"没报错"。name 用 ASCII，省得和 URL 编码纠缠。
        String suffix = String.valueOf(System.nanoTime());
        String name = "test-emp-" + suffix;
        jdbcTemplate.update(
                "insert into employee (name, username, password, phone, sex, id_number, status,"
                        + " create_time, update_time, create_user, update_user)"
                        + " values (?, ?, ?, ?, 1, ?, 1, now(), now(), 1, 1)",
                name, "test-emp-user-" + suffix,
                "e10adc3949ba59abbe56e057f20f883e", "13800000000", "110101199001010000");

        JSONObject res = JSON.parseObject(rawGet(
                "/admin/employee/page?page=1&pageSize=10&name=" + name));

        assertThat(res.getInteger("code")).isEqualTo(1);
        assertThat(res.getJSONObject("data").getJSONArray("records"))
                .as("按姓名应该能搜到刚插进去的那条")
                .isNotEmpty();
    }

    @Test
    @DisplayName("分页查询：不带 name 也要照常能查（防止修 name 那条路径时把原路径弄坏）")
    void pageQuery_withoutName_shouldWork() throws Exception {
        JSONObject res = JSON.parseObject(rawGet("/admin/employee/page?page=1&pageSize=10"));

        assertThat(res.getInteger("code")).isEqualTo(1);
        assertThat(res.getJSONObject("data").getInteger("total")).isGreaterThan(0);
    }

    @Test
    @DisplayName("分页查询：响应原文里不能出现 password（这里曾经带着真实 MD5）")
    void pageQuery_shouldNotLeakPassword() throws Exception {
        String raw = rawGet("/admin/employee/page?page=1&pageSize=10");

        assertThat(raw).doesNotContain("password");
        // 再按 MD5 的形状查一遍：换个字段名继续漏也会被这条抓到（e10adc… 就是 123456 的 MD5）
        assertThat(raw).doesNotContain("e10adc3949ba59abbe56e057f20f883e");
    }

    @Test
    @DisplayName("按 id 查员工：同样不能带 password（原来是回显成 ****）")
    void getById_shouldNotLeakPassword() throws Exception {
        Long id = jdbcTemplate.queryForObject(
                "select id from employee where username = 'admin'", Long.class);

        String raw = rawGet("/admin/employee/" + id);
        JSONObject res = JSON.parseObject(raw);

        assertThat(res.getInteger("code")).isEqualTo(1);
        // 数据本身要还在（别为了防漏把接口改成不返回东西）
        assertThat(res.getJSONObject("data").getString("username")).isEqualTo("admin");
        assertThat(raw).doesNotContain("password");
    }

    /**
     * 用 ADMIN 角色的 token 发 GET，返回【响应原文】。
     * 之所以不用现成的 getJson 那套直接返回 JSONObject —— 只有原文才能断言"字段名没出现"。
     */
    private String rawGet(String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url)
                        .header(jwtProperties.getTokenName(), adminToken()))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** claims 必须和 LoginServiceImpl 签发时一致，否则统一拦截器解析不出来 */
    private String adminToken() {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.ID, 1L);
        claims.put(JwtClaimsConstant.ROLE, RoleConstant.ADMIN);
        claims.put(JwtClaimsConstant.USERNAME, "admin");
        claims.put(JwtClaimsConstant.NAME, "管理员");
        return JwtUtil.createJWT(jwtProperties.getSecretKey(), jwtProperties.getTtl(), claims);
    }
}
