package com.sky.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sky.constant.JwtClaimsConstant;
import com.sky.constant.MessageConstant;
import com.sky.constant.RoleConstant;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.entity.Rider;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.RiderMapper;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 骑手端 R8（确认送达）/ R9（上线-下线）/ R10（当前骑手信息）的集成测试。
 *
 * 【为什么固定用两个骑手身份】
 * 这三个接口全是"按 token 认人"的，最容易出的错就是归属判断写在了别人身上 ——
 * 而这类 bug 手工测试永远发现不了：你登录自己的账号、操作自己的单，一切正常。
 * 所以固定用：
 *   ME    —— 当前登录的骑手（token 里的 id）
 *   OTHER —— "另一个骑手"，专门用来构造越权场景
 *
 * 【为什么 R9 的用例要连带验证"还能登录"】
 * status（账号启停）和 online（接单状态）以前是同一个字段，后果是
 * "骑手点一下下线就再也登不进自己的账号"。所以"下线之后还能登录"
 * 才是这个拆分做对了的直接证据 —— 只断言 online=0 是测不出来的。
 *
 * 【为什么不直接调 Service】
 * 走 MockMvc 让参数真的从 HTTP 字符串被绑定成对象。越权漏洞往往出在
 * "HTTP 参数绑定 -> DTO -> 没人覆盖"这条链上，直接 new 一个 DTO 调 Service
 * 会"自觉"地把 id 填对，测试永远是绿的。
 *
 * 【⚠️ 不要使用 Java 9+ 的 API（Map.of / List.of / var 等）】
 * pom 声明的 java.version 是 1.8，IDE 按语言级别 1.8 会直接报错。
 *
 * 【测试数据用 990011 / 990012 这种大 id】
 * 避开库里真实的骑手（1-4）和订单，@Transactional 结束后自动回滚。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Transactional
class RiderServiceTest {

    /** 当前登录的骑手 */
    private static final Long ME = 990011L;

    /** 另一个骑手，只用来造越权场景 */
    private static final Long OTHER = 990012L;

    /** 夹具骑手的登录密码（明文），库里存 MD5 */
    private static final String RAW_PASSWORD = "123456";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;

    @Autowired
    private RiderMapper riderMapper;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ========================================================================
    // R8 确认送达
    // ========================================================================

    @Test
    @DisplayName("送达：自己的单能送到，状态变已完成且写入 delivery_time")
    void completeOrder_shouldCompleteOwnOrder() throws Exception {
        Long orderId = insertRiderOrder(ME, Orders.DELIVERY_IN_PROGRESS);

        JSONObject res = putJson("/rider/order/complete/" + orderId, riderToken(ME));

        assertThat(res.getInteger("code")).isEqualTo(1);
        Orders after = orderMapper.getById(orderId);
        assertThat(after.getStatus()).isEqualTo(Orders.COMPLETED);
        assertThat(after.getDeliveryTime()).isNotNull();
    }

    @Test
    @DisplayName("送达：不能送达别人的单，且别人的单状态一动都不能动")
    void completeOrder_shouldRejectOthersOrder() throws Exception {
        Long othersOrder = insertRiderOrder(OTHER, Orders.DELIVERY_IN_PROGRESS);

        JSONObject res = putJson("/rider/order/complete/" + othersOrder, riderToken(ME));

        assertThat(res.getInteger("code")).isEqualTo(0);
        // 核心断言：别人的单必须原封不动（状态没变、也没有送达时间）
        Orders after = orderMapper.getById(othersOrder);
        assertThat(after.getStatus()).isEqualTo(Orders.DELIVERY_IN_PROGRESS);
        assertThat(after.getDeliveryTime()).isNull();
    }

    @Test
    @DisplayName("送达：连点两次，第二次失败且 delivery_time 不被覆盖")
    void completeOrder_shouldBeIdempotent() throws Exception {
        Long orderId = insertRiderOrder(ME, Orders.DELIVERY_IN_PROGRESS);

        JSONObject first = putJson("/rider/order/complete/" + orderId, riderToken(ME));
        assertThat(first.getInteger("code")).isEqualTo(1);
        LocalDateTime firstDeliveryTime = orderMapper.getById(orderId).getDeliveryTime();
        assertThat(firstDeliveryTime).isNotNull();

        JSONObject second = putJson("/rider/order/complete/" + orderId, riderToken(ME));

        assertThat(second.getInteger("code")).isEqualTo(0);
        assertThat(second.getString("msg")).isEqualTo(MessageConstant.ORDER_STATUS_ERROR);
        // 核心断言：第二次不能把送达时间改成新的。
        // 这不是理论风险：不用 CAS 而用普通 update 的话，第二次会把时间刷新，
        // 而"实际送达时间"是会拿来对账和算超时的。
        assertThat(orderMapper.getById(orderId).getDeliveryTime()).isEqualTo(firstDeliveryTime);
    }

    @Test
    @DisplayName("送达：别人的单和不存在的单，报的错必须一模一样")
    void completeOrder_shouldNotLeakOrderExistence() throws Exception {
        Long othersOrder = insertRiderOrder(OTHER, Orders.DELIVERY_IN_PROGRESS);

        String msgOfNotMine = putJson("/rider/order/complete/" + othersOrder, riderToken(ME))
                .getString("msg");
        String msgOfMissing = putJson("/rider/order/complete/999999999", riderToken(ME))
                .getString("msg");

        assertThat(msgOfNotMine).isEqualTo(MessageConstant.ORDER_NOT_FOUND);
        // 核心断言：两句话必须完全相同。只要不一样（比如别人那单报"订单状态错误"），
        // 就等于告诉调用方"这个订单确实存在，只是不是你的" —— 信息泄露。
        assertThat(msgOfNotMine).isEqualTo(msgOfMissing);
    }

    @Test
    @DisplayName("送达：单已被取消时报「订单已取消」，不是笼统的状态错误")
    void completeOrder_onCancelledOrder_shouldReportCancelled() throws Exception {
        Long cancelled = insertRiderOrder(ME, Orders.CANCELLED);

        JSONObject res = putJson("/rider/order/complete/" + cancelled, riderToken(ME));

        assertThat(res.getInteger("code")).isEqualTo(0);
        assertThat(res.getString("msg")).isEqualTo(MessageConstant.ORDER_ALREADY_CANCELLED);
    }

    // ========================================================================
    // R9 上线 / 下线
    // ========================================================================

    @Test
    @DisplayName("下线：只改 online，绝不碰 status")
    void switchOnline_shouldChangeOnlyOnline() throws Exception {
        String username = insertRider(ME, 1, 1);

        JSONObject res = putJson("/rider/status/0", riderToken(ME));

        assertThat(res.getInteger("code")).isEqualTo(1);
        Rider after = riderMapper.getById(ME);
        assertThat(after.getOnline()).isEqualTo(0);
        // 核心断言：账号状态必须原封不动。
        // 这两个值以前是同一个字段，把 status 一起改成 0 的话，
        // 这个骑手就再也不能登录了 —— 而他改回来必须先能登录。
        assertThat(after.getStatus()).isEqualTo(1);

        // 连带验证：下线之后【仍然能正常登录】。
        // 这是"两个字段拆开了"这件事最直接的证据。
        JSONObject login = postJsonNoToken("/login",
                body("username", username, "password", RAW_PASSWORD));
        assertThat(login.getInteger("code")).isEqualTo(1);
        assertThat(login.getJSONObject("data").getString("role")).isEqualTo(RoleConstant.RIDER);
    }

    @Test
    @DisplayName("上线：能把自己改成上线，且不影响别的骑手")
    void switchOnline_shouldOnlyAffectSelf() throws Exception {
        insertRider(ME, 1, 0);
        insertRider(OTHER, 1, 1);

        JSONObject res = putJson("/rider/status/1", riderToken(ME));

        assertThat(res.getInteger("code")).isEqualTo(1);
        assertThat(riderMapper.getById(ME).getOnline()).isEqualTo(1);
        // 核心断言：接口不接受任何骑手 id 参数，只能改到自己身上
        assertThat(riderMapper.getById(OTHER).getOnline()).isEqualTo(1);
    }

    @Test
    @DisplayName("上下线：0 / 1 之外的值必须被拒绝，且库里不动")
    void switchOnline_shouldRejectIllegalValue() throws Exception {
        insertRider(ME, 1, 0);

        JSONObject res = putJson("/rider/status/5", riderToken(ME));

        assertThat(res.getInteger("code")).isEqualTo(0);
        assertThat(res.getString("msg")).isEqualTo(MessageConstant.RIDER_ONLINE_STATUS_INVALID);
        assertThat(riderMapper.getById(ME).getOnline()).isEqualTo(0);
    }

    @Test
    @DisplayName("派单候选：离线的和账号停用的骑手都不能被挑中")
    void listAvailable_shouldExcludeOfflineAndDisabled() throws Exception {
        insertRider(ME, 1, 1);          // 启用 + 上线 → 可以被派单
        insertRider(OTHER, 1, 0);       // 启用 + 离线 → 不能
        Long disabled = 990013L;
        insertRider(disabled, 0, 1);    // 停用 + 上线 → 也不能

        List<Long> ids = riderMapper.listAvailable().stream()
                .map(Rider::getId)
                .collect(Collectors.toList());

        assertThat(ids).contains(ME);
        // 核心断言一：离线的不能被派单。
        // 修复前只按 status 筛，这一条会红 —— 那是"上线下线共用一个字段"留下的坑。
        assertThat(ids).doesNotContain(OTHER);
        // 核心断言二：账号被停用的即使在线上也不能被派单
        assertThat(ids).doesNotContain(disabled);
    }

    // ========================================================================
    // R10 当前骑手信息
    // ========================================================================

    @Test
    @DisplayName("当前骑手：返回自己的信息，且响应里绝不能出现 password")
    void currentRider_shouldReturnOwnInfoWithoutPassword() throws Exception {
        insertRider(ME, 1, 1);

        JSONObject res = getJson("/rider/me", riderToken(ME));

        assertThat(res.getInteger("code")).isEqualTo(1);
        JSONObject data = res.getJSONObject("data");
        assertThat(data.getLong("id")).isEqualTo(ME);
        assertThat(data.getInteger("status")).isEqualTo(1);
        assertThat(data.getInteger("online")).isEqualTo(1);
        // 从响应原文里查一遍，防止以后有人图省事改成直接返回 Rider 实体
        // （/admin/employee/page 就是这么把真实密码哈希发出去的）
        assertThat(data.toJSONString()).doesNotContain("password");
    }

    @Test
    @DisplayName("当前骑手：token 有效但人已经不存在 → 401，而不是 200 + code=0")
    void currentRider_whenRiderGone_shouldReturn401() throws Exception {
        // 签名合法、没过期的 token，只是库里没有这个骑手。
        // 必须回 401：前端对 401 会清 token 回登录页，对 200+code=0 只弹 toast，
        // 死 token 会一直留在本地、每个请求重复弹同一句话，状态不会自愈。
        mockMvc.perform(get("/rider/me")
                        .header(jwtProperties.getTokenName(), riderToken(999999999L)))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================================
    // 鉴权
    // ========================================================================

    @Test
    @DisplayName("不带 token 访问骑手端接口：应该 401")
    void requestWithoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(get("/rider/order/list?page=1&pageSize=10"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/rider/status/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("用 C 端用户的 token 访问骑手端接口：应该 403")
    void userTokenOnRiderApi_shouldReturn403() throws Exception {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.ID, 5L);
        claims.put(JwtClaimsConstant.ROLE, RoleConstant.USER);
        claims.put(JwtClaimsConstant.USERNAME, "13800000009");
        claims.put(JwtClaimsConstant.NAME, "test-user");
        String userToken = JwtUtil.createJWT(jwtProperties.getSecretKey(),
                jwtProperties.getTtl(), claims);

        mockMvc.perform(get("/rider/me")
                        .header(jwtProperties.getTokenName(), userToken))
                .andExpect(status().isForbidden());
    }

    // ========================================================================
    // 夹具与工具
    // ========================================================================

    /**
     * 造一个骑手，返回它的登录账号。
     *
     * 用 JdbcTemplate 直接插入而不是 riderMapper.insert：后者没有配
     * useGeneratedKeys（主键不回填）、也没有 online 列（靠数据库默认值），
     * 而 online 恰恰是这几个用例最关键的【自变量】，必须能随意指定。
     *
     * 账号带上 nanoTime：rider.username 上有唯一索引，固定名字在
     * "上次没回滚干净"的情况下会撞车，把测试失败伪装成接口 bug。
     */
    private String insertRider(Long id, int status, int online) {
        String username = "test-rider-" + id + "-" + System.nanoTime();
        jdbcTemplate.update(
                "insert into rider (id, username, password, name, phone, status, online, create_time, update_time) "
                        + "values (?, ?, ?, ?, ?, ?, ?, now(), now())",
                id, username,
                DigestUtils.md5DigestAsHex(RAW_PASSWORD.getBytes()),
                "rider-" + id, "13800009999", status, online);
        return username;
    }

    /**
     * 造一张【已派给某个骑手】的订单，返回订单 id。
     *
     * 分两步：先按正常方式 insert（orders 有 10 个 NOT NULL 列，全部填上），
     * 再补一条 update 绑上骑手 —— 因为 OrderMapper.insert 的列清单里没有 rider_id
     * （真实流程里 rider_id 是派单那条 CAS 单独写的，见 updateDeliveryOrder）。
     */
    private Long insertRiderOrder(Long riderId, Integer status) {
        Orders order = Orders.builder()
                .number("R" + System.nanoTime())        // number 有唯一索引
                .status(status)
                .userId(5L)                             // 下单用户，和骑手 id 不是一个体系
                .addressBookId(0L)
                .orderTime(LocalDateTime.now())
                .payMethod(1)
                .payStatus(Orders.PAID)
                .amount(new BigDecimal("10.00"))
                .deliveryStatus(1)
                .tablewareStatus(1)
                .tablewareNumber(1)
                .packAmount(0)
                .phone("13800000000")
                .address("test-address")
                .consignee("test-consignee")
                .build();
        orderMapper.insert(order);
        jdbcTemplate.update("update orders set rider_id = ? where id = ?", riderId, order.getId());
        return order.getId();
    }

    /** 造一条菜品明细（送达本身用不到，留给详情类的用例） */
    private void insertOrderDetail(Long orderId) {
        OrderDetail detail = OrderDetail.builder()
                .orderId(orderId)
                .name("test-dish")
                .number(1)
                .amount(new BigDecimal("6.00"))
                .image("test.png")
                .build();
        orderDetailMapper.insertBatch(Collections.singletonList(detail));
    }

    /**
     * 按骑手 id 造一个 RIDER 角色的 token。
     * claims 必须和 LoginServiceImpl 签发时一致，否则统一拦截器解析不出来。
     */
    private String riderToken(Long riderId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.ID, riderId);
        claims.put(JwtClaimsConstant.ROLE, RoleConstant.RIDER);
        claims.put(JwtClaimsConstant.USERNAME, "test-rider-" + riderId);
        claims.put(JwtClaimsConstant.NAME, "test-rider");
        return JwtUtil.createJWT(jwtProperties.getSecretKey(), jwtProperties.getTtl(), claims);
    }

    private JSONObject getJson(String url, String token) throws Exception {
        MvcResult result = mockMvc.perform(get(url).header(jwtProperties.getTokenName(), token))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.parseObject(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JSONObject putJson(String url, String token) throws Exception {
        return sendJson(put(url), token);
    }

    /** 不带 token 的 POST（登录接口用） */
    private JSONObject postJsonNoToken(String url, Object body) throws Exception {
        MvcResult result = mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.toJSONString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.parseObject(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JSONObject sendJson(MockHttpServletRequestBuilder builder, String token) throws Exception {
        MvcResult result = mockMvc.perform(builder
                        .header(jwtProperties.getTokenName(), token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.parseObject(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 构造请求体（成对的 key/value）—— 不用 Map.of，理由见类注释 */
    private static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
