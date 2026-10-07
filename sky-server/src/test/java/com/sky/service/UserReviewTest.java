package com.sky.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sky.constant.JwtClaimsConstant;
import com.sky.constant.MessageConstant;
import com.sky.constant.RoleConstant;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.properties.JwtProperties;
import com.sky.utils.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 提交评价（POST /user/review）的接口测试。
 *
 * 【为什么这 7 条 —— 每一条都对应用户能感受到的一个坑】
 *   1. 正常提交：这是唯一"应该成功"的路径
 *   2. 订单不存在：曾经直接 NPE → 500（忘记判 null）
 *   3. 别人的订单：必须给和"不存在"完全一样的提示，否则能从提示差异里探出哪些订单真实存在
 *   4. 未完成的订单：不能评（待付款、派送中、已取消都不行）
 *   5. 同一单评两次：要给人话（"这单已经评价过了"），不能是数据库原始报错、更不能 500
 *   6. content 全是空格：必须落成 NULL —— 阶段③靠"content 是否为空"决定要不要发 MQ 消息，
 *      存成 "   "（或 ""）会让消费者拿着空格去调 LLM，白花钱
 *   7. score 越界：0 星/6 星要在查库之前就被拒
 *
 * 【为什么断言 HTTP 200】
 * 本项目的约定是"业务失败 = HTTP 200 + code=0"，所以 code=0 的用例也要先断言状态码是 200 ——
 * 一旦有人把它写成 400/500，前端会显示"服务异常"，这条断言就是那道防线。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Transactional
class UserReviewTest {

    /** C 端测试用户（13800000009）的 id；不新建用户，直接用它的身份签发 token */
    private static final Long ME = 5L;
    /** 用来造"别人的订单"，这是个大 id，避开库里真实用户 */
    private static final Long OTHER = 990001L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtProperties jwtProperties;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private OrderMapper orderMapper;

    @Test
    @DisplayName("正常提交：已完成的自己的订单 → code=1，库里 user_id 来自 token、content 是传进去的那句话")
    void submit_shouldSucceed() throws Exception {
        Long orderId = insertOrder(ME, 5);
        String content = "鱼很新鲜，就是有点咸";

        JSONObject res = submit(ME, orderId, 4, content);

        assertThat(res.getInteger("code")).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select user_id, score, content from review where order_id = ?", orderId);
        assertThat(row.get("user_id")).isEqualTo(ME);          // 身份来自 token，不是前端传的
        assertThat(row.get("score")).isEqualTo(4);
        assertThat(row.get("content")).isEqualTo(content);
    }

    @Test
    @DisplayName("订单不存在 → code=0 + 订单不存在（不是 500）")
    void unknownOrder_shouldReturnOrderNotFound() throws Exception {
        JSONObject res = submit(ME, 999999999L, 5, "ghost order");

        assertThat(res.getInteger("code")).isEqualTo(0);
        assertThat(res.getString("msg")).isEqualTo(MessageConstant.ORDER_NOT_FOUND);
    }

    @Test
    @DisplayName("别人的订单 → 和'不存在'给同一句提示（不泄漏存在性）")
    void othersOrder_shouldLookLikeNotFound() throws Exception {
        Long othersOrderId = insertOrder(OTHER, 5);

        JSONObject res = submit(ME, othersOrderId, 5, "not my order");

        assertThat(res.getInteger("code")).isEqualTo(0);
        assertThat(res.getString("msg")).isEqualTo(MessageConstant.ORDER_NOT_FOUND);
    }

    @Test
    @DisplayName("未完成的订单（待付款）→ code=0，且提示不是'订单不存在'")
    void unfinishedOrder_shouldBeRejected() throws Exception {
        Long orderId = insertOrder(ME, 1);   // 1 = 待付款

        JSONObject res = submit(ME, orderId, 5, "too early");

        assertThat(res.getInteger("code")).isEqualTo(0);
        assertThat(res.getString("msg")).isNotBlank();
        assertThat(res.getString("msg")).isNotEqualTo(MessageConstant.ORDER_NOT_FOUND);
    }

    @Test
    @DisplayName("同一单评两次 → 第二次 code=0 + '这单已经评价过了'")
    void duplicateReview_shouldBeRejected() throws Exception {
        Long orderId = insertOrder(ME, 5);

        assertThat(submit(ME, orderId, 5, "first").getInteger("code")).isEqualTo(1);
        JSONObject second = submit(ME, orderId, 1, "second");

        assertThat(second.getInteger("code")).isEqualTo(0);
        assertThat(second.getString("msg")).isEqualTo(MessageConstant.REVIEW_ALREADY_EXISTS);
        // 确认没有写进去第二条
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from review where order_id = ?", Integer.class, orderId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("content 全是空格 → code=1，且库里必须是 NULL（不是空串、更不是空格）")
    void blankContent_shouldBeStoredAsNull() throws Exception {
        Long orderId = insertOrder(ME, 5);

        JSONObject res = submit(ME, orderId, 3, "   ");

        assertThat(res.getInteger("code")).isEqualTo(1);
        String stored = jdbcTemplate.queryForObject(
                "select content from review where order_id = ?", String.class, orderId);
        // 阶段③靠"content 为空"决定要不要发 MQ 消息，所以这里必须是 NULL
        assertThat(stored).isNull();
    }

    @Test
    @DisplayName("score 越界（0）→ code=0 + 打分提示")
    void invalidScore_shouldBeRejected() throws Exception {
        Long orderId = insertOrder(ME, 5);

        JSONObject res = submit(ME, orderId, 0, "bad score");

        assertThat(res.getInteger("code")).isEqualTo(0);
        assertThat(res.getString("msg")).isEqualTo(MessageConstant.REVIEW_SCORE_INVALID);
    }

    @Test
    @DisplayName("content 长度边界：501 字被拒且不落库，500 字放行")
    void contentLengthBoundary() throws Exception {
        // 501 字：拒绝
        Long rejectOrderId = insertOrder(ME, 5);
        JSONObject rejected = submit(ME, rejectOrderId, 5, "x".repeat(501));

        assertThat(rejected.getInteger("code")).isEqualTo(0);
        assertThat(rejected.getString("msg")).isEqualTo(MessageConstant.REVIEW_CONTENT_TOO_LONG);
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from review where order_id = ?", Integer.class, rejectOrderId);
        assertThat(count).as("被拒的评价不能落库").isZero();

        // 正好 500 字：必须放行（边界不能差一个）
        Long acceptOrderId = insertOrder(ME, 5);
        JSONObject accepted = submit(ME, acceptOrderId, 5, "x".repeat(500));

        assertThat(accepted.getInteger("code")).isEqualTo(1);
    }

    // ==================== 查询某单的评价（GET /user/review/order/{id}） ====================

    @Test
    @DisplayName("查自己评过的单 → code=1，字段正确，且响应原文里没有 userId/sentiment/tags")
    void search_mine_shouldReturnReview() throws Exception {
        Long orderId = insertOrder(ME, 5);
        assertThat(submit(ME, orderId, 4, "很新鲜").getInteger("code")).isEqualTo(1);

        String raw = getReviewRaw(ME, orderId);
        JSONObject res = JSON.parseObject(raw);
        JSONObject data = res.getJSONObject("data");

        assertThat(res.getInteger("code")).isEqualTo(1);
        assertThat(data).isNotNull();
        assertThat(data.getLong("orderId")).isEqualTo(orderId);
        assertThat(data.getInteger("score")).isEqualTo(4);
        assertThat(data.getString("content")).isEqualTo("很新鲜");
        // 契约：这个接口只暴露 5 个字段。实体上带着的 userId/sentiment/tags 一个都不许出现 ——
        // 这条断言把"必须返回 VO 而不是直接返回 Review 实体"钉死在测试里。
        assertThat(raw).doesNotContain("userId");
        assertThat(raw).doesNotContain("sentiment");
        assertThat(raw).doesNotContain("tags");
    }

    @Test
    @DisplayName("查自己没评过的单 → code=1 且 data 为 null（不是 404，也不是 code=0）")
    void search_notReviewed_shouldReturnNullData() throws Exception {
        Long orderId = insertOrder(ME, 5);

        JSONObject res = JSON.parseObject(getReviewRaw(ME, orderId));

        assertThat(res.getInteger("code")).isEqualTo(1);
        assertThat(res.get("data")).isNull();
    }

    @Test
    @DisplayName("查别人评过的单 → code=1 且 data 为 null（不泄漏'这单有评价但不是你的'）")
    void search_othersReview_shouldReturnNullData() throws Exception {
        Long othersOrderId = insertOrder(OTHER, 5);
        // 绕过接口直接给 OTHER 插一条评价，模拟"别人已经评过"
        jdbcTemplate.update(
                "insert into review (order_id, user_id, score, content, create_time) values (?, ?, ?, ?, now())",
                othersOrderId, OTHER, 5, "别人的评价");

        JSONObject res = JSON.parseObject(getReviewRaw(ME, othersOrderId));

        assertThat(res.getInteger("code")).isEqualTo(1);
        assertThat(res.get("data")).isNull();
    }

    @Test
    @DisplayName("订单 id 根本不存在 → code=1 且 data 为 null")
    void search_unknownOrder_shouldReturnNullData() throws Exception {
        JSONObject res = JSON.parseObject(getReviewRaw(ME, 999999999L));

        assertThat(res.getInteger("code")).isEqualTo(1);
        assertThat(res.get("data")).isNull();
    }

    // ======================= 测试工具 =======================

    /** 以某个用户身份查某单的评价，返回【响应原文】—— 只有原文才能断言字段有没有泄漏 */
    private String getReviewRaw(Long userId, Long orderId) throws Exception {
        MvcResult result = mockMvc.perform(get("/user/review/order/" + orderId)
                        .header(jwtProperties.getTokenName(), userToken(userId)))
                .andExpect(status().isOk())      // 查不到也必须是 200 + code=1 + data=null
                .andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 以某个用户身份提交评价，返回响应 JSON */
    private JSONObject submit(Long userId, Long orderId, Integer score, String content) throws Exception {
        JSONObject body = new JSONObject();
        body.put("orderId", orderId);
        body.put("score", score);
        body.put("content", content);

        MvcResult result = mockMvc.perform(post("/user/review")
                        .header(jwtProperties.getTokenName(), userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toJSONString().getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())      // 业务失败也必须是 200 + code=0
                .andReturn();
        return JSON.parseObject(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** claims 必须和 LoginServiceImpl 签发时一致，否则统一拦截器解析不出角色 */
    private String userToken(Long userId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.ID, userId);
        claims.put(JwtClaimsConstant.ROLE, RoleConstant.USER);
        claims.put(JwtClaimsConstant.USERNAME, "13800000009");
        claims.put(JwtClaimsConstant.NAME, "测试用户");
        return JwtUtil.createJWT(jwtProperties.getSecretKey(), jwtProperties.getTtl(), claims);
    }

    /** orders 有 10 个 NOT NULL 列，全部填上；status 由调用方指定 */
    private Long insertOrder(Long userId, int status) {
        Orders order = Orders.builder()
                .number("R" + System.nanoTime() + userId)      // number 有唯一索引
                .status(status)
                .userId(userId)
                .addressBookId(0L)
                .orderTime(LocalDateTime.now())
                .payMethod(1)
                .payStatus(0)
                .amount(new BigDecimal("10.00"))
                .deliveryStatus(1)
                .tablewareStatus(1)
                .tablewareNumber(1)
                .packAmount(0)
                .phone("13800000000")
                .address("review-test-address")
                .consignee("review-test")
                .build();
        // 复用项目已有的 OrderMapper（省得手写 10 列的 insert；它会回填自增 id）
        orderMapper.insert(order);
        return order.getId();
    }
}
