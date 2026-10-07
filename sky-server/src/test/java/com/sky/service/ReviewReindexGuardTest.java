package com.sky.service;

import com.sky.result.BulkResult;
import com.sky.client.EsHttpClient;
import com.sky.exception.ReviewBusinessException;
import com.sky.properties.EsProperties;
import com.sky.vo.ReviewReindexVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R2 护栏的回归网：全量重建【一条文档都没写进去】时，绝不允许把别名切到空索引上、
 * 更不允许顺手删掉旧索引。
 *
 * 【护栏要挡的是什么】
 * 循环 0 次迭代的原因有很多：days 逻辑写错、batch-size 配成 0 / 负数、SQL 回归、
 * 将来任何取数 bug。旧实现不管写进去几条，只要没抛异常就照切不误 →
 * 别名指向空索引 + 旧索引被删除（线上检索直接空掉，且不可恢复）。
 *
 * 【护栏不能写成什么】
 * 不能写成"indexed == 0 就中止"——"库里确实一条评价都没有"（首次重建 / 空库）
 * 是合法场景，必须放行。所以判断条件是【旧索引存在且有文档】。
 * 本类第 2 条测试就是钉这个反例的。
 *
 * 【为什么用 @MockitoBean EsHttpClient】
 * 本环境连不上虚拟机上的 ES（192.168.100.129:9200），真调必然假红。把客户端整体
 * 打桩之后，被测的就是"AdminReviewServiceImpl 在什么情况下才肯切别名"这段判断逻辑本身，
 * 而 MySQL 那一侧仍然是真实的（review 表当前 0 行 → 游标第一轮就是空批 → indexed = 0）。
 * （注：原来用的是 Boot 3.5 已标记 deprecated 的 @MockBean，已换成官方替代 @MockitoBean，
 *  语义相同，同时消掉编译告警。）
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReviewReindexGuardTest {

    @Autowired
    private AdminReviewService adminReviewService;
    @Autowired
    private EsProperties esProperties;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EsHttpClient esHttpClient;

    @Test
    @DisplayName("R2：一条都没写进去 + 旧索引有数据 → 抛业务异常，绝不切别名、绝不删旧索引")
    void emptyReindex_shouldAbortInsteadOfSwitchingAlias() {
        String alias = esProperties.getAlias();
        String target = "review_v9";
        String old = "review_v1";

        // 真实 MySQL 的 review 表必须是 0 行，循环才会 0 次迭代、indexed 才会是 0。
        // 显式断言这个前提：环境里真有历史评价时，失败信息要能一眼看出是环境问题。
        assertReviewTableIsEmpty();

        when(esHttpClient.mappingJson()).thenReturn("{\"mappings\":{}}");
        when(esHttpClient.resolveTargetIndex(alias)).thenReturn(target);
        when(esHttpClient.currentIndex(alias)).thenReturn(old);      // 有旧索引
        when(esHttpClient.createIndex(anyString(), any())).thenReturn(true);
        when(esHttpClient.bulk(anyString(), anyList())).thenReturn(bulkOk()); // 用不到：空批直接 break
        when(esHttpClient.count(old)).thenReturn(1L);               // 旧索引里有 1 条文档
        // 故意让"切别名"会成功：这样一旦护栏失效，红的就是下面那两条 never() ——
        // 直接指向"别名被切到空索引 + 旧索引被删"这个危险行为本身，而不是被别的失败掩盖。
        when(esHttpClient.switchAlias(any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> adminReviewService.reindex())
                .as("旧索引有数据、本次却 0 条 → 必须拒绝切换并抛业务异常")
                .isInstanceOf(ReviewBusinessException.class)
                .hasMessageContaining("未写入任何文档")
                .hasMessageContaining("旧索引与线上查询未受影响");

        // ★ 安全底线：既没切别名（线上查询还指向旧索引），也没删旧索引
        verify(esHttpClient, never()).switchAlias(any(), any(), any());
        verify(esHttpClient, never()).deleteIndex(old);
        // I4：中止路径上"本次刚建好的目标索引"要删掉（没被切换、数据也不完整，留着就是垃圾）
        verify(esHttpClient).deleteIndex(target);
    }

    @Test
    @DisplayName("R2 反例：旧别名为空（首次重建）+ 0 条 → 必须允许切别名，不是一刀切")
    void emptyReindex_onFirstBuild_shouldStillSwitchAlias() {
        String alias = esProperties.getAlias();
        String target = "review_v1";

        assertReviewTableIsEmpty();

        when(esHttpClient.mappingJson()).thenReturn("{\"mappings\":{}}");
        when(esHttpClient.resolveTargetIndex(alias)).thenReturn(target);
        when(esHttpClient.currentIndex(alias)).thenReturn(null);     // 首次重建：别名确实不存在
        when(esHttpClient.createIndex(anyString(), any())).thenReturn(true);
        when(esHttpClient.switchAlias(any(), any(), any())).thenReturn(true);
        // I4 清扫：没有任何残留索引（空列表 = 确实没有，不是"没查到"）
        when(esHttpClient.listIndices(alias)).thenReturn(Collections.emptyList());

        ReviewReindexVO vo = adminReviewService.reindex();

        assertThat(vo.getIndexed()).as("库里确实没评价 → 合法场景，indexed = 0 也算成功").isZero();
        assertThat(vo.getFailed()).isZero();
        // 只发 add（old = null）；连"旧索引有几条"都不需要问 → 证明护栏不是"indexed == 0 就中止"
        verify(esHttpClient).switchAlias(alias, target, null);
        verify(esHttpClient, never()).count(anyString());
        // I4 之后，旧索引（这里 old = null）的删除改由"切换成功后的清扫"统一完成：
        // 服务层不再直接调 deleteIndex(old)。首次重建没有任何残留索引 → 一次删除都不该发生。
        // （客户端 deleteIndex(null) 直接 return 的契约仍然保留，只是服务层不再依赖它。）
        verify(esHttpClient, never()).deleteIndex(any());
        verify(esHttpClient, never()).deleteIndex(anyString());
    }

    /** 本类的两个用例都依赖"库里没有评价"这个事实（否则 indexed 不会是 0） */
    private void assertReviewTableIsEmpty() {
        Long rows = jdbcTemplate.queryForObject("select count(*) from review", Long.class);
        assertThat(rows)
                .as("本用例依赖 review 表为 0 行（循环才会 0 次迭代）；若环境里有历史评价，"
                        + "失败的是这个前提而不是 R2 护栏")
                .isZero();
    }

    /** I2：bulk 的返回值由 boolean 变成 BulkResult，"一条都没失败"用 failed == 0 表达 */
    private BulkResult bulkOk() {
        return BulkResult.builder().indexed(0).failed(0).firstErrors(Collections.emptyList()).build();
    }
}
