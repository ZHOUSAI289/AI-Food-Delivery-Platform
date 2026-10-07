package com.sky.service;

import com.sky.entity.ReviewIndexDoc;
import com.sky.mapper.UserReviewMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全量重建的【取数链路】测试：直接走真实的 UserReviewMapper.pageForIndex。
 *
 * 【为什么必须单独有这一条】
 * 之前唯一碰过 ReviewIndexDoc 的测试是 EsHttpClientTest，而它用 ReviewIndexDoc.builder()
 * 【手工造】文档，一条用例都没走 mapper；而 review 表在本机是 0 行，手工点一次
 * reindex 只会 indexed=0 成功返回，循环体一次都不进。于是"取数"这段唯一的真实路径
 * 在交付时是零覆盖的，下面这两个缺陷就藏在那里面：
 *
 *   1. SQL 的列别名曾经是 dishIdStr / dishNameStr，和实体字段 dishIds / dishNames 对不上。
 *      ReviewIndexDoc 又只有 @Builder 生成的全参构造（没有无参构造），MyBatis 因此
 *      metaType.hasDefaultConstructor() == false → 走"按构造参数下标"的映射：
 *      匹配不到列名的字段会退回去接【同一列下标的列】。实测结果是把 content 接进了
 *      dishIds、把 createTime 接进了 dishNames —— 然后 EsHttpClientImpl.toDocJson 对
 *      dishIds 调 Long.valueOf("鱼很新鲜，就是有点咸") → NumberFormatException → HTTP 500。
 *   2. group_concat 的顺序由 SQL 里的 order by 决定（driving 列分别是 od.dish_id 和 od.name），
 *      断言必须按这个顺序，否则改错了 order by 也看不出来。
 *
 * 所以本类的核心断言就是：一条评价里 dishIds / dishNames 必须是两个 group_concat 的结果，
 * 且【各自按自己的 order by 排序】。
 *
 * 【这条测试确实会红 —— 已实测，不是推测】
 *   只把 SQL 别名改回 dishIdStr / dishNameStr（保留无参构造）后重跑本类：
 *     [ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
 *     expected: "4,9"  but was: null
 *   两个缺陷都没修时（无参构造也没有）失败形态更糟：dishIds 拿到的是 content 的内容（串列）。
 *
 * 数据全部用 jdbcTemplate 直接造，并且整个类在一个事务里、跑完自动回滚，不污染真实数据。
 *
 * 【为什么是 RANDOM_PORT 而不是默认的 MOCK】
 * 本项目的 WebSocketConfiguration 里有一个 ServerEndpointExporter，它需要一个真实的
 * Servlet 容器；默认的 MOCK 环境没有 ServerContainer，上下文直接起不来
 * （IllegalStateException: jakarta.websocket.server.ServerContainer not available）。
 * 所以这里和 ReviewAdminPageTest / UserReviewTest 保持一致，用 RANDOM_PORT。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional
class ReviewIndexQueryTest {

    /** 合成订单 id：review.order_id 上有唯一索引，这里避开库里真实订单和其它测试用的 8800xx */
    private static final Long ORDER_ID = 880101L;
    private static final Long ME = 5L;

    /** 两条明细刻意让"按 dish_id 排"和"按 name 排"的顺序不同，以便发现两个 order by 被写混 */
    private static final long DISH_ID_HIGH = 9L;
    private static final String DISH_NAME_HIGH = "BraisedFish";
    private static final long DISH_ID_LOW = 4L;
    private static final String DISH_NAME_LOW = "ApplePie";

    @Autowired
    private UserReviewMapper userReviewMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("取数：pageForIndex 必须按列名映射（dishIds/dishNames 是 group_concat 的结果，不是 content/createTime）")
    void pageForIndex_shouldMapColumnsByName() {
        // 1 条评价 + 同一订单的 2 条明细（菜名 / dish_id 各不同）
        insertReview(ORDER_ID, 4, "鱼很新鲜，就是有点咸", "2026-10-06 18:32:12");
        insertOrderDetail(ORDER_ID, DISH_ID_HIGH, DISH_NAME_HIGH, new BigDecimal("88.00"));
        insertOrderDetail(ORDER_ID, DISH_ID_LOW, DISH_NAME_LOW, new BigDecimal("2.00"));
        Long reviewId = jdbcTemplate.queryForObject(
                "select id from review where order_id = ?", Long.class, ORDER_ID);

        List<ReviewIndexDoc> batch = userReviewMapper.pageForIndex(0L, null, 10);
        ReviewIndexDoc doc = batch.stream()
                .filter(d -> ORDER_ID.equals(d.getOrderId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("pageForIndex 没取到 order_id=" + ORDER_ID + " 的评价"));

        // 普通字段：按列名映射，必须正确
        assertThat(doc.getReviewId()).isEqualTo(reviewId);
        assertThat(doc.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(doc.getScore()).isEqualTo(4);
        assertThat(doc.getContent()).isEqualTo("鱼很新鲜，就是有点咸");
        assertThat(doc.getCreateTime()).isEqualTo("2026-10-06 18:32:12");

        // 【本测试的重点】两个 group_concat 的结果，各自按 SQL 里的 order by 排序：
        //   dishIds   = group_concat(... od.dish_id order by od.dish_id) → 按 dish_id 升序
        //   dishNames = group_concat(... od.name    order by od.name)    → 按菜名升序
        assertThat(doc.getDishIds())
                .as("dishIds 必须是两个 dish_id 的逗号串（曾经这里是 content 的值）")
                .isEqualTo(DISH_ID_LOW + "," + DISH_ID_HIGH);
        assertThat(doc.getDishNames())
                .as("dishNames 必须是两个菜名的逗号串，按 od.name 升序（曾经这里是 createTime 的值）")
                .isEqualTo(DISH_NAME_LOW + "," + DISH_NAME_HIGH);
    }

    // ======================= 测试工具 =======================

    /** 直接插一条评价（order_id 用合成 id，不依赖库里已有的订单） */
    private void insertReview(Long orderId, int score, String content, String createTime) {
        jdbcTemplate.update(
                "insert into review (order_id, user_id, score, content, create_time) values (?, ?, ?, ?, ?)",
                orderId, ME, score, content, createTime);
    }

    /** 给订单插一条菜品明细（order_detail 的 NOT NULL 列：order_id / number / amount） */
    private void insertOrderDetail(Long orderId, Long dishId, String name, BigDecimal amount) {
        jdbcTemplate.update(
                "insert into order_detail (order_id, dish_id, name, image, number, amount) values (?, ?, ?, ?, 1, ?)",
                orderId, dishId, name, "test.png", amount);
    }
}
