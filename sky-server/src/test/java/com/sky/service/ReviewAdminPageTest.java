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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端评价分页（GET /admin/review/page）的接口测试。
 *
 * 【为什么和 UserReviewTest 分开】
 * 那一个测的是 C 端（提交 / 查某单），这一个测管理端（分页 + 脱敏 + 聚合）。
 * 两者的身份、断言重点、数据造法都不一样，混在一起会越来越难读。
 *
 * 【这两条是这个接口最容易错的地方，也是本类的重点】
 *   1. left join order_detail 会让【一条评价变成多行】（一个订单 3 道菜 → 3 行），
 *      如果 PageHelper 生成的 count 数的是 join 之后的行数，total 就会偏大。
 *      所以第 1 条测试专门断言 total = 评价条数，而不是行数。
 *   2. user.phone 是隐私。接口只允许返回脱敏后的展示名（138****0009），
 *      所以第 2 条测试既断言脱敏结果，也断言【响应原文里搜不到完整手机号】。
 *
 * 数据全部用 jdbcTemplate 直接造（绕过接口，才能自己指定 create_time、才能造多道菜的订单），
 * 并且全在一个事务里、跑完自动回滚，不会污染你库里的真实数据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Transactional
class ReviewAdminPageTest {

    private static final Long ME = 5L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtProperties jwtProperties;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("分页：total 必须等于评价条数，而不是 left join 之后的行数")
    void total_shouldCountReviewsNotJoinedRows() throws Exception {
        // 评价 A 的订单里有 2 道菜 → join 之后这条评价会变成 2 行
        insertReview(880001L, 4, "a", "2026-10-01 12:00:00");
        insertOrderDetail(880001L, "蜀味水煮草鱼", new BigDecimal("38.00"));
        insertOrderDetail(880001L, "米饭", new BigDecimal("2.00"));
        // 评价 B 的订单没有明细 → 仍是 1 行
        insertReview(880002L, 5, "b", "2026-10-02 12:00:00");

        JSONObject data = page("1", "10").getJSONObject("data");

        assertThat(data.getInteger("total")).as("应该是 2 条评价，不是 3 行 join 结果").isEqualTo(2);
        assertThat(data.getJSONArray("records")).hasSize(2);
    }

    @Test
    @DisplayName("分页：dishes 是一次查出来的（多道菜用顿号连接）")
    void dishes_shouldBeAggregatedInOneQuery() throws Exception {
        insertReview(880003L, 5, "鱼不错", "2026-10-01 12:00:00");
        insertOrderDetail(880003L, "蜀味水煮草鱼", new BigDecimal("38.00"));
        insertOrderDetail(880003L, "米饭", new BigDecimal("2.00"));

        JSONObject first = page("1", "10").getJSONObject("data").getJSONArray("records").getJSONObject(0);

        assertThat(first.getString("dishes")).contains("蜀味水煮草鱼").contains("米饭").contains("、");
    }

    @Test
    @DisplayName("分页：手机号必须脱敏，且响应原文里搜不到完整号码")
    void phone_shouldBeMasked() throws Exception {
        // 清空昵称，逼 SQL 走"脱敏手机号"那个分支（user 5 的 phone = 13800000009）
        jdbcTemplate.update("update user set name = '' where id = ?", ME);
        insertReview(880004L, 3, "还行", "2026-10-03 12:00:00");

        String raw = pageRaw("1", "10");
        JSONObject first = JSON.parseObject(raw).getJSONObject("data").getJSONArray("records").getJSONObject(0);

        assertThat(first.getString("userName")).isEqualTo("138****0009");
        assertThat(raw).as("响应里不能出现完整手机号").doesNotContain("13800000009");
    }

    @Test
    @DisplayName("分页：score 筛选只回差评")
    void score_shouldFilter() throws Exception {
        insertReview(880005L, 1, "太咸了", "2026-10-01 12:00:00");
        insertReview(880006L, 5, "很好吃", "2026-10-02 12:00:00");

        JSONObject data = page("1", "10", "score", "1").getJSONObject("data");

        assertThat(data.getInteger("total")).isEqualTo(1);
        assertThat(data.getJSONArray("records").getJSONObject(0).getInteger("score")).isEqualTo(1);
    }

    @Test
    @DisplayName("分页：keyword 只命中包含它的那条")
    void keyword_shouldFilter() throws Exception {
        insertReview(880007L, 1, "有点咸", "2026-10-01 12:00:00");
        insertReview(880008L, 5, "非常新鲜", "2026-10-02 12:00:00");

        JSONObject data = page("1", "10", "keyword", "咸").getJSONObject("data");

        assertThat(data.getInteger("total")).isEqualTo(1);
        assertThat(data.getJSONArray("records").getJSONObject(0).getString("content")).isEqualTo("有点咸");
    }

    @Test
    @DisplayName("分页：时间范围把范围外的评价过滤掉")
    void timeRange_shouldFilter() throws Exception {
        insertReview(880009L, 5, "范围外", "2026-10-01 12:00:00");
        insertReview(880010L, 5, "范围内", "2026-10-05 12:00:00");

        JSONObject data = page("1", "10", "beginTime", "2026-10-04 00:00:00").getJSONObject("data");

        assertThat(data.getInteger("total")).isEqualTo(1);
        assertThat(data.getJSONArray("records").getJSONObject(0).getString("content")).isEqualTo("范围内");
    }

    // ======================= 测试工具 =======================

    /** 直接插一条评价（order_id 用合成 id，不依赖库里已有的订单） */
    private void insertReview(Long orderId, int score, String content, String createTime) {
        jdbcTemplate.update(
                "insert into review (order_id, user_id, score, content, create_time) values (?, ?, ?, ?, ?)",
                orderId, ME, score, content, createTime);
    }

    /** 给订单插一条菜品明细（order_detail 的 NOT NULL 列：order_id / number / amount） */
    private void insertOrderDetail(Long orderId, String name, BigDecimal amount) {
        jdbcTemplate.update(
                "insert into order_detail (order_id, name, image, number, amount) values (?, ?, ?, 1, ?)",
                orderId, name, "test.png", amount);
    }

    private JSONObject page(String... params) throws Exception {
        return JSON.parseObject(pageRaw(params));
    }

    /** 返回【响应原文】—— 脱敏那条必须看原文才断言得准 */
    private String pageRaw(String... params) throws Exception {
        MockHttpServletRequestBuilder req = get("/admin/review/page")
                .header(jwtProperties.getTokenName(), adminToken())
                .param("page", "1")
                .param("pageSize", "10");
        for (int i = 0; i + 1 < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        MvcResult result = mockMvc.perform(req).andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** ADMIM 角色 token（claims 必须和 LoginServiceImpl 签发的一致） */
    private String adminToken() {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.ID, 1L);
        claims.put(JwtClaimsConstant.ROLE, RoleConstant.ADMIN);
        claims.put(JwtClaimsConstant.USERNAME, "admin");
        claims.put(JwtClaimsConstant.NAME, "管理员");
        return JwtUtil.createJWT(jwtProperties.getSecretKey(), jwtProperties.getTtl(), claims);
    }
}
