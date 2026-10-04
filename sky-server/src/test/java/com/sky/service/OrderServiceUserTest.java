package com.sky.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sky.constant.JwtClaimsConstant;
import com.sky.constant.MessageConstant;
import com.sky.constant.RoleConstant;
import com.sky.entity.AddressBook;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.entity.ShoppingCart;
import com.sky.mapper.AddressBookMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.ShoppingCartMapper;
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
 * C 端订单模块的集成测试。
 *
 * 【为什么这个类存在】
 * 订单模块最容易出的不是"功能不对"，而是"数据越权"——接口返回了不属于当前用户的数据。
 * 而越权 bug 有一个共同特征：**手工测试永远发现不了**。因为你登录自己的账号、看自己的数据，
 * 一切正常；只有当请求里被塞进"别人的 id"时才会暴露。
 *
 * 所以这个类固定用两个身份：
 *   ME    —— 当前登录用户（token 里带的 id）
 *   OTHER —— "别人"，专门用来构造越权场景
 * 每个用例都断言「结果里不能出现 OTHER 的数据」。
 *
 * 【两个关键技术点】
 * 1. @Transactional：每个测试方法结束后自动回滚，测试造的数据不会留在库里，
 *    所以可以放心插"别人的订单""别人的地址"。
 * 2. 一律走 MockMvc，不直接调 Service：
 *    越权漏洞出在「HTTP 参数绑定 -> DTO -> 无人覆盖」这条链上。
 *    如果直接 new 一个 DTO 调 Service，你会"自觉"地把 userId 填对，测试永远是绿的，
 *    等于什么都没验证。必须让参数真的从 HTTP 字符串被绑定成对象。
 *
 * 【为什么用 990001 / 990002 这种大 id】
 * 库里已有的测试数据是 user_id = 5 等小 id，用大数值能避开干扰，
 * 而且 orders.user_id 没有外键约束，不需要真的建用户行。
 *
 * 【为什么 webEnvironment 要用 RANDOM_PORT】
 * @SpringBootTest 默认是 MOCK 环境，用的是模拟的 ServletContext，**没有真实的 WebSocket 容器**。
 * 本项目的 WebSocketConfiguration 里注册了 ServerEndpointExporter，它在 MOCK 环境下会直接报
 *   "javax.websocket.server.ServerContainer not available"
 * 导致整个 ApplicationContext 加载失败（所有用例一起 ERROR，看起来像测试写错了，其实是环境问题）。
 * 指定 RANDOM_PORT 会启动一个真实的嵌入式 Tomcat，WebSocket 容器就有了。
 *
 * 注意：即使用了 RANDOM_PORT，MockMvc 仍然是"进程内直接调 DispatcherServlet"、不经过网络，
 * 所以请求还是跑在测试线程上，@Transactional 的回滚依然有效。
 *
 * 【⚠️ 不要使用 Java 9+ 的 API（Map.of / List.of / var 等）】
 * 本项目 pom 继承 spring-boot-starter-parent 2.7.3，声明的 java.version 是 1.8。
 * 而 Maven 用的是 -source/-target 1.8（不是 --release 1.8），这两者**只限制语法和字节码版本、
 * 不限制类库** —— 编译器仍然拿 JDK 21 的 rt，所以 Map.of 能编过。
 * 但：IDE 按语言级别 1.8 检查会直接报错，换成 JDK 8 编译也会失败。
 * 用下面的 body(...) 工具方法代替 Map.of(...)。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Transactional
class OrderServiceUserTest {

    /** 当前登录用户（token 里带的 id） */
    private static final Long ME = 990001L;

    /** "别人"，只用来造越权场景 */
    private static final Long OTHER = 990002L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;

    @Autowired
    private AddressBookMapper addressBookMapper;

    @Autowired
    private JwtProperties jwtProperties;

    // ========================================================================
    // 越权用例（本类的核心）
    // ========================================================================

    @Test
    @DisplayName("查历史订单：只返回自己的订单，不含别人的")
    void historyOrders_shouldOnlyReturnOwnOrders() throws Exception {
        Long mine1 = insertOrder(ME, Orders.COMPLETED);
        Long mine2 = insertOrder(ME, Orders.CANCELLED);
        Long others = insertOrder(OTHER, Orders.COMPLETED);

        List<Long> ids = orderIdsOf(getJson("/user/order/historyOrders?page=1&pageSize=10", ME));

        assertThat(ids).contains(mine1, mine2);
        // 核心断言：别人的订单绝不能出现
        assertThat(ids).doesNotContain(others);
    }

    @Test
    @DisplayName("查历史订单：伪造别人的 userId 也看不到别人的订单")
    void historyOrders_shouldIgnoreSpoofedUserId() throws Exception {
        Long mine = insertOrder(ME, Orders.COMPLETED);
        Long others = insertOrder(OTHER, Orders.COMPLETED);

        // 攻击姿势：URL 里直接带上别人的 userId
        List<Long> ids = orderIdsOf(
                getJson("/user/order/historyOrders?page=1&pageSize=10&userId=" + OTHER, ME));

        assertThat(ids).contains(mine);
        // 核心断言：服务端必须用 token 里的 id 覆盖参数里的 userId
        assertThat(ids).doesNotContain(others);
    }

    @Test
    @DisplayName("查历史订单：不传 userId 时也不能退化成查全表")
    void historyOrders_shouldNotFallBackToFullTable() throws Exception {
        insertOrder(ME, Orders.COMPLETED);
        Long others = insertOrder(OTHER, Orders.CANCELLED);

        // 攻击姿势：干脆不传 userId，赌动态 SQL 的条件不生效
        List<Long> ids = orderIdsOf(getJson("/user/order/historyOrders?page=1&pageSize=10", ME));

        assertThat(ids).doesNotContain(others);
    }

    @Test
    @DisplayName("支付：不能支付别人的订单")
    void payment_shouldRejectOthersOrder() throws Exception {
        Long othersOrder = insertOrder(OTHER, Orders.PENDING_PAYMENT);
        String othersNumber = orderMapper.getById(othersOrder).getNumber();

        JSONObject res = putJson("/user/order/payment", ME,
                body("orderNumber", othersNumber, "payMethod", 1));

        // 业务失败：code = 0
        assertThat(res.getInteger("code")).isEqualTo(0);
        // 而且必须是【因为归属不对】被拒，不是别的原因碰巧失败
        assertThat(res.getString("msg")).isEqualTo(MessageConstant.ORDER_NOT_FOUND);
        // 核心断言：别人的订单状态不能被改动
        assertThat(orderMapper.getById(othersOrder).getStatus())
                .isEqualTo(Orders.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("下单：不能用别人的收货地址")
    void submitOrder_shouldRejectOthersAddress() throws Exception {
        Long othersAddress = insertAddress(OTHER);

        JSONObject res = postJson("/user/order/submit", ME,
                body("addressBookId", othersAddress, "payMethod", 1));

        assertThat(res.getInteger("code")).isEqualTo(0);
        // 【关键】只断言 code=0 是不够的：
        // submitOrder 里地址校验之后还有购物车校验，测试环境购物车是空的，
        // 所以即使地址归属的校验被删掉，请求也会因为"购物车为空"而返回 code=0 —— 测试假绿。
        // 必须断言具体的失败原因，才能证明真的是【地址归属】这一层拦住的。
        assertThat(res.getString("msg")).isEqualTo(MessageConstant.ADDRESS_BOOK_IS_NULL);
    }

    @Test
    @DisplayName("订单详情：能查到自己订单的详情，且带菜品明细")
    void orderDetail_shouldReturnOwnOrderWithDetails() throws Exception {
        Long mine = insertOrder(ME, Orders.COMPLETED);
        insertOrderDetail(mine);

        JSONObject res = getJson("/user/order/orderDetail/" + mine, ME);

        assertThat(res.getInteger("code")).isEqualTo(1);
        JSONObject data = res.getJSONObject("data");
        assertThat(data.getLong("id")).isEqualTo(mine);
        // 明细必须补齐
        assertThat(data.getJSONArray("orderDetailList")).isNotEmpty();
    }

    @Test
    @DisplayName("订单详情：别人的订单应该被拒绝，且不能暴露订单是否存在")
    void orderDetail_shouldRejectOthersOrder() throws Exception {
        Long others = insertOrder(OTHER, Orders.COMPLETED);

        JSONObject res = getJson("/user/order/orderDetail/" + others, ME);

        assertThat(res.getInteger("code")).isEqualTo(0);
        // 【关键】错误消息必须和"订单不存在"完全一致。
        // 如果这里返回的是别的消息（比如"无权限""该订单不属于你"），
        // 就等于告诉了调用方"这个订单确实存在，只是不是你的"—— 这是信息泄露。
        assertThat(res.getString("msg")).isEqualTo(MessageConstant.ORDER_NOT_FOUND);
    }

    @Test
    @DisplayName("订单详情：不存在的订单 id 应该被拒绝")
    void orderDetail_shouldRejectMissingOrder() throws Exception {
        JSONObject res = getJson("/user/order/orderDetail/999999999", ME);

        assertThat(res.getInteger("code")).isEqualTo(0);
        assertThat(res.getString("msg")).isEqualTo(MessageConstant.ORDER_NOT_FOUND);
    }

    // ========================================================================
    // 鉴权
    // ========================================================================

    @Test
    @DisplayName("不带 token 访问 C 端接口：应该 401")
    void requestWithoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(get("/user/order/historyOrders?page=1&pageSize=10"))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================================
    // 再来一单
    // ========================================================================

    @Autowired
    private ShoppingCartMapper shoppingCartMapper;

    /**
     * 用来造测试数据（dish / setmeal）。
     *
     * 【为什么这里直接用 SQL，而不是注入 DishMapper 调 insert】
     * DishMapper.insert 上面标了 @AutoFill(INSERT)，那个切面会去读
     * BaseContext.getCurrentId() 来填 create_user / update_user。
     * 测试线程里没有登录态，这个值是 null —— 要么 NPE，要么填出脏数据，
     * 都会把"测试失败"伪装成"接口有 bug"。造夹具用裸 SQL 更可控：
     * status 想填几就填几，这是这几个用例最关键的自变量。
     *
     * 【为什么每次都用 System.nanoTime() 造名字】
     * dish.name 和 setmeal.name 上都有 UNIQUE KEY（idx_dish_name / idx_setmeal_name），
     * 名字撞了会插入失败。虽然 @Transactional 会回滚，但同一批用例里
     * 名字仍然可能重复，用纳秒后缀最省心。
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("再来一单：加进购物车的金额必须是当前菜价，不能是订单里的历史价")
    void repetition_shouldUseCurrentPriceNotOrderSnapshot() throws Exception {
        String dishName = "test-dish-" + System.nanoTime();
        // 当前菜价 45.00
        Long dishId = insertDish(dishName, "45.00", 1);

        Long orderId = insertOrder(ME, Orders.COMPLETED);
        // 订单明细里的 amount 是【下单那一刻的快照】6.00，这里故意让它和当前价不同
        insertOrderDetailWithDish(orderId, dishId, dishName, 3);

        JSONObject res = postJson("/user/order/repetition/" + orderId, ME, body());

        assertThat(res.getInteger("code")).isEqualTo(1);
        JSONObject data = res.getJSONObject("data");
        assertThat(data.getInteger("addedCount")).isEqualTo(1);
        assertThat(data.getJSONArray("skippedNames")).isEmpty();

        List<ShoppingCart> cart = cartOf(ME);
        assertThat(cart).hasSize(1);
        ShoppingCart item = cart.get(0);
        assertThat(item.getDishId()).isEqualTo(dishId);
        // 份数来自订单
        assertThat(item.getNumber()).isEqualTo(3);
        // 【核心断言】金额来自当前菜品表，不是 order_detail 里的 6.00。
        // 如果实现里用了 BeanUtils.copyProperties(orderDetail, shoppingCart)，
        // 这里就会是 6.00 —— 等于用户按三个月前的价格下单。
        assertThat(item.getAmount()).isEqualByComparingTo(new BigDecimal("45.00"));
    }

    @Test
    @DisplayName("再来一单：重复调用应该在原数量上叠加，而不是覆盖成固定值")
    void repetition_shouldAccumulateNumberOnSecondCall() throws Exception {
        String dishName = "test-dish-" + System.nanoTime();
        Long dishId = insertDish(dishName, "15.00", 1);
        Long orderId = insertOrder(ME, Orders.COMPLETED);
        insertOrderDetailWithDish(orderId, dishId, dishName, 2);

        postJson("/user/order/repetition/" + orderId, ME, body());
        postJson("/user/order/repetition/" + orderId, ME, body());

        List<ShoppingCart> cart = cartOf(ME);
        assertThat(cart).hasSize(1);
        // 【核心断言】2 + 2 = 4。
        // 这个数字是刻意选的：订单份数是 2 而不是 1，
        //   - upsert 若写成 number = number + 1  -> 3（丢了一份）
        //   - 若在 Java 侧读旧值再 setNumber 覆盖 -> 2（丢了更新）
        // 只有 number = number + #{number} 才会得到 4。
        // 如果订单份数是 1，"+1" 和 "+N" 结果一样，这个 bug 就测不出来。
        assertThat(cart.get(0).getNumber()).isEqualTo(4);
    }

    @Test
    @DisplayName("再来一单：菜品已停售时跳过，不进购物车，并记入 skippedNames")
    void repetition_shouldSkipDisabledDish() throws Exception {
        String dishName = "test-dish-" + System.nanoTime();
        // status = 0 停售
        Long dishId = insertDish(dishName, "20.00", 0);
        Long orderId = insertOrder(ME, Orders.COMPLETED);
        insertOrderDetailWithDish(orderId, dishId, dishName, 1);

        JSONObject res = postJson("/user/order/repetition/" + orderId, ME, body());

        // 不是错误，是"部分成功"：仍然 code=1，由前端根据 addedCount 决定要不要跳购物车
        assertThat(res.getInteger("code")).isEqualTo(1);
        JSONObject data = res.getJSONObject("data");
        assertThat(data.getInteger("addedCount")).isEqualTo(0);
        assertThat(data.getJSONArray("skippedNames")).containsExactly(dishName);
        assertThat(cartOf(ME)).isEmpty();
    }

    @Test
    @DisplayName("再来一单：菜品已被物理删除时不能 500，应该跳过")
    void repetition_shouldSkipDeletedDish() throws Exception {
        Long orderId = insertOrder(ME, Orders.COMPLETED);
        // dishId 指向一道不存在的菜。
        // 这不是纯理论场景：DishServiceImpl.deleteByIds 只挡"起售中"和"被套餐关联"，
        // 并不检查订单关联 —— 菜品停售后就能被真删掉，历史订单里却还留着这个 dishId。
        insertOrderDetailWithDish(orderId, 999999999L, "deleted-dish", 1);

        JSONObject res = postJson("/user/order/repetition/" + orderId, ME, body());

        // 关键是不能 500：dishMapper.getById 返回 null 时必须判掉
        assertThat(res.getInteger("code")).isEqualTo(1);
        assertThat(res.getJSONObject("data").getInteger("addedCount")).isEqualTo(0);
        assertThat(res.getJSONObject("data").getJSONArray("skippedNames"))
                .containsExactly("deleted-dish");
        assertThat(cartOf(ME)).isEmpty();
    }

    @Test
    @DisplayName("再来一单：不能用别人的订单，且自己的购物车不能被塞进东西")
    void repetition_shouldRejectOthersOrder() throws Exception {
        String dishName = "test-dish-" + System.nanoTime();
        Long dishId = insertDish(dishName, "15.00", 1);
        Long othersOrder = insertOrder(OTHER, Orders.COMPLETED);
        insertOrderDetailWithDish(othersOrder, dishId, dishName, 1);

        JSONObject res = postJson("/user/order/repetition/" + othersOrder, ME, body());

        assertThat(res.getInteger("code")).isEqualTo(0);
        assertThat(res.getString("msg")).isEqualTo(MessageConstant.ORDER_NOT_FOUND);
        // 【核心断言】归属校验必须发生在写购物车【之前】。
        // 如果实现里先加购再校验（或者压根不校验），别人的订单内容就会跑进我的购物车。
        assertThat(cartOf(ME)).isEmpty();
    }

    @Test
    @DisplayName("再来一单：不存在的订单 id 应该被拒绝")
    void repetition_shouldRejectMissingOrder() throws Exception {
        JSONObject res = postJson("/user/order/repetition/999999999", ME, body());

        assertThat(res.getInteger("code")).isEqualTo(0);
        assertThat(res.getString("msg")).isEqualTo(MessageConstant.ORDER_NOT_FOUND);
        assertThat(cartOf(ME)).isEmpty();
    }

    // ========================================================================
    // 当前用户信息（3.8）
    // ========================================================================

    @Test
    @DisplayName("当前用户信息：能查到自己，且响应里不能出现密码类字段")
    void userInfo_shouldReturnSelfWithoutPassword() throws Exception {
        // 用库里真实存在的 user.id = 5（13800000009）
        JSONObject res = getJson("/user/user/me", 5L);

        assertThat(res.getInteger("code")).isEqualTo(1);
        JSONObject data = res.getJSONObject("data");
        assertThat(data.getLong("id")).isEqualTo(5L);
        // User 实体是带 password 的，靠 UserInfoVO 做白名单拷贝挡住；
        // 这里从响应原文里查一遍，防止以后有人图省事改成直接返回 User
        String raw = data.toJSONString();
        assertThat(raw).doesNotContain("password");
        assertThat(raw).doesNotContain("openid");
        assertThat(raw).doesNotContain("id_number");
    }

    @Test
    @DisplayName("当前用户信息：token 有效但用户行不存在时应该 401，否则前端不会清 token")
    void userInfo_shouldReturn401WhenUserRowMissing() throws Exception {
        // 这是一个【签名合法、没过期】的 token，只是库里没有这个用户。
        // 这种"登录态已经失效"的情况必须回 401：
        // 走业务异常的话是 HTTP 200 + code=0，而前端对 code=0 只弹 toast、不清 token 不跳转，
        // 结果就是死 token 一直留在 localStorage，每个请求重复弹同一句话，用户没有出路。
        mockMvc.perform(get("/user/user/me")
                        .header(jwtProperties.getTokenName(), tokenOf(999999999L)))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================================
    // 购物车加减（3.5）
    // ========================================================================

    @Test
    @DisplayName("加购：正常在售菜品能加进购物车（确认下面的守卫没有误伤正常流程）")
    void addShoppingCart_shouldStillWorkForEnabledDish() throws Exception {
        String dishName = "test-dish-" + System.nanoTime();
        Long dishId = insertDish(dishName, "12.50", 1);

        JSONObject res = postJson("/user/shoppingCart/add", ME, body("dishId", dishId));

        assertThat(res.getInteger("code")).isEqualTo(1);
        List<ShoppingCart> cart = cartOf(ME);
        assertThat(cart).hasSize(1);
        assertThat(cart.get(0).getDishId()).isEqualTo(dishId);
        assertThat(cart.get(0).getNumber()).isEqualTo(1);
        assertThat(cart.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("12.50"));
    }

    @Test
    @DisplayName("加购：{ } 空请求体不能改到用户没点的商品")
    void addShoppingCart_shouldRejectRequestWithoutGoodsId() throws Exception {
        // 先真的放一件商品进购物车，制造"list() 能查到行"的前提 ——
        // 这正是这个 bug 的危害所在：两个 id 都为 null 时，list() 的动态 SQL
        // 会退化成 "where user_id = ?"，返回全部购物车行，然后 list.get(0) 改到它。
        String dishName = "test-dish-" + System.nanoTime();
        Long dishId = insertDish(dishName, "10.00", 1);
        postJson("/user/shoppingCart/add", ME, body("dishId", dishId));

        JSONObject res = postJson("/user/shoppingCart/add", ME, body());

        assertThat(res.getInteger("code")).isEqualTo(0);
        assertThat(res.getString("msg")).isEqualTo(MessageConstant.SHOPPING_CART_PARAM_ERROR);
        // 核心断言：原来那件商品的数量必须原封不动
        List<ShoppingCart> cart = cartOf(ME);
        assertThat(cart).hasSize(1);
        assertThat(cart.get(0).getNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("加购：菜品已被物理删除时应该是业务错误，不能 500")
    void addShoppingCart_shouldNotCrashWhenDishDeleted() throws Exception {
        // dishMapper.getById 返回 null 时不能直接 dish.getName()。
        // 这个场景是可达的：DishServiceImpl.deleteByIds 只挡"起售中"和"被套餐关联"，
        // 不检查别的关联，所以停售的菜品可以被真删掉，
        // 而用户手上那个页面还留着旧的 dishId（商家停售后删除，用户此时点加购）。
        JSONObject res = postJson("/user/shoppingCart/add", ME, body("dishId", 999999999L));

        assertThat(res.getInteger("code")).isEqualTo(0);
        assertThat(res.getString("msg")).isEqualTo(MessageConstant.GOODS_NOT_AVAILABLE);
        assertThat(cartOf(ME)).isEmpty();
    }

    @Test
    @DisplayName("减一：{ } 空请求体不能误删购物车里的商品")
    void subShoppingCart_shouldRejectRequestWithoutGoodsId() throws Exception {
        String dishName = "test-dish-" + System.nanoTime();
        Long dishId = insertDish(dishName, "10.00", 1);
        postJson("/user/shoppingCart/add", ME, body("dishId", dishId));

        JSONObject res = postJson("/user/shoppingCart/sub", ME, body());

        assertThat(res.getInteger("code")).isEqualTo(0);
        assertThat(res.getString("msg")).isEqualTo(MessageConstant.SHOPPING_CART_PARAM_ERROR);
        // 核心断言：商品必须还在。
        // 修复前这里会 list.get(0) 拿到那件商品，因为 number == 1，
        // 走 else 分支直接 deleteById —— 用户发了个空请求，购物车里的商品就没了。
        assertThat(cartOf(ME)).hasSize(1);
    }

    // ========================================================================
    // 下单时的商品可用性（3.1）
    // ========================================================================

    @Test
    @DisplayName("下单：购物车里的菜品已被停售时必须拒绝整单，且不能清空购物车")
    void submitOrder_shouldRejectDisabledDishInCart() throws Exception {
        String dishName = "test-dish-" + System.nanoTime();
        Long dishId = insertDish(dishName, "20.00", 1);
        // 先在【在售】状态下加进购物车，这一步是合法的
        postJson("/user/shoppingCart/add", ME, body("dishId", dishId));
        // 商家随后把它停售了 —— 关键就在这：商品已经躺在购物车里了，
        // 只在 addShoppingCart 那一层拦是拦不住它的，下单这一刻必须再查一次
        jdbcTemplate.update("update dish set status = 0 where id = ?", dishId);

        Long addressId = insertAddress(ME);
        Integer before = orderCountOf(ME);

        JSONObject res = postJson("/user/order/submit", ME,
                body("addressBookId", addressId, "payMethod", 1, "packAmount", 0));

        assertThat(res.getInteger("code")).isEqualTo(0);
        // 提示里必须带上商品名，用户才知道该去购物车删哪一个
        assertThat(res.getString("msg")).contains(dishName);
        // 核心断言一：不能生成订单
        assertThat(orderCountOf(ME)).isEqualTo(before);
        // 核心断言二：购物车不能被清空。清空了用户就再也看不到问题商品，
        // 只会反复看到"已失效"却不知道该删什么。
        assertThat(cartOf(ME)).hasSize(1);
    }

    @Test
    @DisplayName("下单：购物车里的菜品已被删除时必须拒绝整单，不能 500")
    void submitOrder_shouldRejectDeletedDishInCart() throws Exception {
        String dishName = "test-dish-" + System.nanoTime();
        Long dishId = insertDish(dishName, "20.00", 1);
        postJson("/user/shoppingCart/add", ME, body("dishId", dishId));
        // 直接删掉菜品，购物车行里还留着这个 dishId
        jdbcTemplate.update("delete from dish where id = ?", dishId);

        Long addressId = insertAddress(ME);
        Integer before = orderCountOf(ME);

        JSONObject res = postJson("/user/order/submit", ME,
                body("addressBookId", addressId, "payMethod", 1, "packAmount", 0));

        assertThat(res.getInteger("code")).isEqualTo(0);
        assertThat(res.getString("msg")).contains(dishName);
        assertThat(orderCountOf(ME)).isEqualTo(before);
        assertThat(cartOf(ME)).hasSize(1);
    }

    @Test
    @DisplayName("下单：全部在售时能正常下单，且下单后购物车被清空")
    void submitOrder_shouldStillWorkForEnabledDish() throws Exception {
        String dishName = "test-dish-" + System.nanoTime();
        Long dishId = insertDish(dishName, "20.00", 1);
        postJson("/user/shoppingCart/add", ME, body("dishId", dishId));

        Long addressId = insertAddress(ME);
        Integer before = orderCountOf(ME);

        JSONObject res = postJson("/user/order/submit", ME,
                body("addressBookId", addressId, "payMethod", 1, "packAmount", 0));

        assertThat(res.getInteger("code")).isEqualTo(1);
        assertThat(res.getJSONObject("data").getLong("id")).isNotNull();
        assertThat(orderCountOf(ME)).isEqualTo(before + 1);
        assertThat(cartOf(ME)).isEmpty();
    }

    // ========================================================================
    // 价格口径：一律以菜单当前价为准（乙方案）
    //
    // 背景：shopping_cart.amount 是"加购那一刻"写进去的价。商家之后调价，
    // 这一行不会自己变。所以凡是"要拿价格做判断"的地方，都必须回头问菜单，
    // 不能信购物车里存的那个数 —— 否则商家涨价后，用户能按旧价成交。
    // ========================================================================

    @Test
    @DisplayName("下单：必须按菜单当前价结算，不能按加购时存进购物车里的旧价")
    void submitOrder_shouldSettleAtCurrentPriceNotCartSnapshot() throws Exception {
        String dishName = "test-dish-" + System.nanoTime();
        Long dishId = insertDish(dishName, "20.00", 1);

        // 用户按 20.00 加进购物车
        postJson("/user/shoppingCart/add", ME, body("dishId", dishId));
        // 商家随后涨价到 35.00
        jdbcTemplate.update("update dish set price = ? where id = ?",
                new BigDecimal("35.00"), dishId);

        Long addressId = insertAddress(ME);
        JSONObject res = postJson("/user/order/submit", ME,
                body("addressBookId", addressId, "payMethod", 1, "packAmount", 0));

        assertThat(res.getInteger("code")).isEqualTo(1);
        Long orderId = res.getJSONObject("data").getLong("id");
        // packAmount = 0，所以订单金额就等于菜品现价
        assertThat(jdbcTemplate.queryForObject(
                "select amount from orders where id = ?", BigDecimal.class, orderId))
                .as("应该按涨价后的 35.00 结算，而不是购物车里的 20.00")
                .isEqualByComparingTo(new BigDecimal("35.00"));
    }

    @Test
    @DisplayName("购物车列表：显示的必须是菜单当前价，否则页面报 20、实际扣 35")
    void shoppingCartList_shouldShowCurrentPriceNotStoredSnapshot() throws Exception {
        String dishName = "test-dish-" + System.nanoTime();
        Long dishId = insertDish(dishName, "20.00", 1);
        postJson("/user/shoppingCart/add", ME, body("dishId", dishId));
        // 商家涨价
        jdbcTemplate.update("update dish set price = ? where id = ?",
                new BigDecimal("35.00"), dishId);

        JSONObject res = getJson("/user/shoppingCart/list", ME);

        assertThat(res.getInteger("code")).isEqualTo(1);
        JSONArray items = res.getJSONArray("data");
        assertThat(items).hasSize(1);
        // 前端在结算页会重新拉一次购物车来对齐价格，
        // 所以这个接口返回的必须是现价 —— 它是不让用户"被惊喜扣款"的最后一道保障
        assertThat(((JSONObject) items.get(0)).getBigDecimal("amount"))
                .as("购物车接口应该返回菜单现价 35.00")
                .isEqualByComparingTo(new BigDecimal("35.00"));
    }

    // ========================================================================
    // 测试工具
    // ========================================================================

    /**
     * 造一道菜，返回菜品 id。
     * status：0 停售 / 1 起售 —— 这是再来一单那几个用例最重要的自变量。
     */
    private Long insertDish(String name, String price, Integer status) {
        jdbcTemplate.update(
                "insert into dish (name, category_id, price, image, status, create_time, update_time) "
                        + "values (?, ?, ?, ?, ?, now(), now())",
                name, 1L, new BigDecimal(price), "test.png", status);
        return jdbcTemplate.queryForObject("select id from dish where name = ?", Long.class, name);
    }

    /** 给订单造一条【关联真实菜品】的明细 */
    private void insertOrderDetailWithDish(Long orderId, Long dishId, String name, Integer number) {
        OrderDetail detail = OrderDetail.builder()
                .orderId(orderId)
                .dishId(dishId)
                .name(name)
                .number(number)
                .amount(new BigDecimal("6.00"))   // 故意和当前菜价不同，用来验证金额取值来源
                .image("test.png")
                .build();
        orderDetailMapper.insertBatch(Collections.singletonList(detail));
    }

    /** 查某个用户当前的购物车 */
    private List<ShoppingCart> cartOf(Long userId) {
        return shoppingCartMapper.list(ShoppingCart.builder().userId(userId).build());
    }

    /** 数某个用户有多少张订单。用来断言"这次请求没有生成订单" */
    private Integer orderCountOf(Long userId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from orders where user_id = ?", Integer.class, userId);
    }

    /**
     * 构造请求体（成对的 key/value）。
     *
     * 刻意不用 Map.of(...)，两个原因：
     *   1. Map.of 是 Java 9+ 的 API，本项目声明的是 Java 1.8（见类注释）
     *   2. Map.of 不接受 null 值，传了 null 会抛 NPE。排查时容易误以为是 Map 的问题，
     *      其实是"某个值没取到"——用 HashMap 的话，null 会被照常放进去，问题更直观。
     */
    private static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /**
     * 造一张订单，返回订单 id（自增主键已被 MyBatis 回填）。
     * orders 有 10 个 NOT NULL 列，这里全部填上，否则插入会失败。
     */
    private Long insertOrder(Long userId, Integer status) {
        Orders order = Orders.builder()
                .number("T" + System.nanoTime() + userId)   // number 有唯一索引，必须唯一
                .status(status)
                .userId(userId)
                .addressBookId(0L)                          // 没有外键约束，占位即可
                .orderTime(LocalDateTime.now())
                .payMethod(1)
                .payStatus(Orders.UN_PAID)
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
        return order.getId();
    }

    /** 给订单造一条菜品明细 */
    private void insertOrderDetail(Long orderId) {
        OrderDetail detail = OrderDetail.builder()
                .orderId(orderId)
                .name("test-dish")
                .number(2)
                .amount(new BigDecimal("6.00"))
                .image("test.png")
                .build();
        orderDetailMapper.insertBatch(Collections.singletonList(detail));
    }

    /** 造一条属于某个用户的收货地址，返回地址 id */
    private Long insertAddress(Long userId) {
        AddressBook addressBook = AddressBook.builder()
                .userId(userId)
                .consignee("other-consignee")
                .phone("13900000000")
                .provinceName("P")
                .cityName("C")
                .districtName("D")
                .detail("test-detail")
                .isDefault(0)
                .build();
        addressBookMapper.insert(addressBook);
        // 注意：AddressBookMapper.insert 用的是 @Insert 注解、没有配 useGeneratedKeys，
        // 主键不会回填到对象上（OrderMapper.insert 配了，所以订单那边能直接拿到 id）。
        // 这里按 userId 查一次把 id 取出来。
        return addressBookMapper.list(AddressBook.builder().userId(userId).build())
                .get(0)
                .getId();
    }

    /**
     * 按 userId 造一个 USER 角色的 token。
     * claims 必须和 LoginServiceImpl 签发时保持一致，否则统一拦截器解析不出来。
     */
    private String tokenOf(Long userId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.ID, userId);
        claims.put(JwtClaimsConstant.ROLE, RoleConstant.USER);
        claims.put(JwtClaimsConstant.USERNAME, "test-" + userId);
        claims.put(JwtClaimsConstant.NAME, "test-user");
        return JwtUtil.createJWT(jwtProperties.getSecretKey(), jwtProperties.getTtl(), claims);
    }

    private JSONObject getJson(String url, Long asUser) throws Exception {
        MvcResult result = mockMvc.perform(
                        get(url).header(jwtProperties.getTokenName(), tokenOf(asUser)))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.parseObject(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JSONObject postJson(String url, Long asUser, Object body) throws Exception {
        return sendJson(post(url), asUser, body);
    }

    private JSONObject putJson(String url, Long asUser, Object body) throws Exception {
        return sendJson(put(url), asUser, body);
    }

    private JSONObject sendJson(MockHttpServletRequestBuilder builder,
                                Long asUser, Object body) throws Exception {
        MvcResult result = mockMvc.perform(builder
                        .header(jwtProperties.getTokenName(), tokenOf(asUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.toJSONString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.parseObject(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 从分页响应里取出所有订单 id */
    private List<Long> orderIdsOf(JSONObject response) {
        JSONArray records = response.getJSONObject("data").getJSONArray("records");
        if (records == null) {
            // 不能用 List.of()：同样是 Java 9+ 的 API
            return Collections.emptyList();
        }
        return records.stream()
                .map(item -> ((JSONObject) item).getLong("id"))
                .collect(Collectors.toList());
    }
}
