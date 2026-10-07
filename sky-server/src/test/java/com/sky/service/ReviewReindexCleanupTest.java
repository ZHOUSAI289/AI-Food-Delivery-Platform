package com.sky.service;

import com.sky.result.BulkResult;
import com.sky.client.EsHttpClient;
import com.sky.entity.ReviewIndexDoc;
import com.sky.exception.ReviewBusinessException;
import com.sky.mapper.UserReviewMapper;
import com.sky.properties.EsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * I4（残留索引清理）的回归网 + I2（bulk 失败信息失真）的服务层落点。
 *
 * 施工图 §2 承诺过"删旧索引失败 → 下轮重建顺手清理"，但实现里从来没有任何地方会删
 * "没被别名指向的 {alias}_vN"：每发生一次 bulk 失败 / 切别名失败 / 抛异常 / 删索引失败，
 * 就永久留下一个完整索引，版本号只增不减。本类钉住 I4 的两半：
 *
 *   ① 中止路径：本次【刚建好】的目标索引在"bulk 有失败"或"R2 空索引护栏拒绝"而中止时被删掉
 *      （没被切换、数据也不完整 → 留着就是垃圾）；删除失败只记日志，不能盖掉原来的异常。
 *   ② 切换成功后清扫：{alias}_v* 里"既不是新目标、也不是别名当前指向"的一律删掉。
 *      ★ 安全底线：新目标索引与别名当前指向的索引【绝不】被删 —— 这是本次最容易写出事故的地方。
 *
 * 【为什么这里连 UserReviewMapper 也打桩】
 * "bulk 有失败"这条路径要求游标循环至少跑一轮（否则 bulk 根本不会被调用）。本机 review 表是
 * 0 行，用真实 mapper 只会像当初藏住 C1 那样"循环 0 次、测试假绿"；把取数打桩之后，
 * 被测的就是"AdminReviewServiceImpl 拿到 bulk 结果之后做什么"这段判断本身，
 * 且完全不依赖库里有没有数据。
 *
 * 【为什么用 @MockitoBean】
 * 本环境连不上虚拟机上的 ES（192.168.100.129:9200），真调必然假红。
 * （注：原来用的是 Boot 3.5 已标记 deprecated 的 @MockBean，已换成官方替代 @MockitoBean，
 *  语义相同，同时消掉编译告警。）
 * RANDOM_PORT 的原因同其它评价测试：WebSocketConfiguration 需要真实 Servlet 容器。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReviewReindexCleanupTest {

    @Autowired
    private AdminReviewService adminReviewService;
    @Autowired
    private EsProperties esProperties;

    @MockitoBean
    private EsHttpClient esHttpClient;
    @MockitoBean
    private UserReviewMapper userReviewMapper;

    // ==================== I2 + I4：bulk 有失败 → 中止、删目标、绝不切别名 ====================

    @Test
    @DisplayName("I2/I4：bulk 有失败 → 抛业务异常（消息带真实条数），删掉本次新建的目标索引，绝不切别名")
    void bulkFailed_shouldAbortWithAccurateCounts_andDeleteTargetIndex() {
        String alias = esProperties.getAlias();
        String target = "review_v9";
        String old = "review_v1";

        when(esHttpClient.mappingJson()).thenReturn("{\"mappings\":{}}");
        when(esHttpClient.resolveTargetIndex(alias)).thenReturn(target);
        when(esHttpClient.currentIndex(alias)).thenReturn(old);
        when(esHttpClient.createIndex(anyString(), any())).thenReturn(true);
        when(esHttpClient.switchAlias(any(), any(), any())).thenReturn(true); // 故意会成功：红的就是"切了"本身
        // 2 条里成功 1 条、失败 1 条 —— 旧实现只会报 failed = batch.size() = 2，且 indexed 少算
        when(userReviewMapper.pageForIndex(anyLong(), any(), anyInt())).thenReturn(batchOfTwo());
        when(esHttpClient.bulk(eq(target), anyList())).thenReturn(
                BulkResult.builder().indexed(1).failed(1)
                        .firstErrors(Collections.singletonList("_id=9002 status=400 type=mapper_parsing_exception")).build());

        assertThatThrownBy(() -> adminReviewService.reindex())
                .as("bulk 有失败 → 必须抛业务异常（HTTP 200 + code=0），不能返回 code=1 的成功")
                .isInstanceOf(ReviewBusinessException.class)
                // 两个数字必须是【逐条统计】出来的：真实写入 1 条（不是整批 2 条）、失败 1 条
                .hasMessageContaining("已写入 1 条")
                .hasMessageContaining("本批失败 1 条")
                .hasMessageContaining("索引未切换");

        verify(esHttpClient, never()).switchAlias(any(), any(), any()); // ★ 铁律：绝不切别名
        verify(esHttpClient).deleteIndex(target);                       // I4 中止路径：删掉半成品目标索引
        verify(esHttpClient, never()).deleteIndex(old);                 // 旧索引（线上在用的）不动
    }

    @Test
    @DisplayName("I4：中止时删目标索引也失败 → 只记日志，原业务异常照旧抛出（不被删除错误盖掉）")
    void bulkFailed_whenDeleteTargetFails_shouldStillThrowOriginalBusinessException() {
        String alias = esProperties.getAlias();
        String target = "review_v9";

        when(esHttpClient.mappingJson()).thenReturn("{\"mappings\":{}}");
        when(esHttpClient.resolveTargetIndex(alias)).thenReturn(target);
        when(esHttpClient.currentIndex(alias)).thenReturn(null);
        when(esHttpClient.createIndex(anyString(), any())).thenReturn(true);
        when(userReviewMapper.pageForIndex(anyLong(), any(), anyInt())).thenReturn(batchOfTwo());
        when(esHttpClient.bulk(eq(target), anyList())).thenReturn(
                BulkResult.builder().indexed(0).failed(2).firstErrors(Collections.singletonList("请求失败")).build());
        doThrow(new IllegalStateException("删索引炸了")).when(esHttpClient).deleteIndex(target);

        assertThatThrownBy(() -> adminReviewService.reindex())
                .as("清理是附带动作：它失败不能把\"为什么中止\"这个原因盖掉")
                .isInstanceOf(ReviewBusinessException.class)
                .hasMessageContaining("本批失败 2 条");
    }

    @Test
    @DisplayName("I4：R2 空索引护栏中止时，同样要删掉本次新建的空索引（旧索引一根汗毛都不动）")
    void emptyGuardAbort_shouldAlsoDeleteTargetIndex() {
        String alias = esProperties.getAlias();
        String target = "review_v9";
        String old = "review_v1";

        when(esHttpClient.mappingJson()).thenReturn("{\"mappings\":{}}");
        when(esHttpClient.resolveTargetIndex(alias)).thenReturn(target);
        when(esHttpClient.currentIndex(alias)).thenReturn(old);
        when(esHttpClient.createIndex(anyString(), any())).thenReturn(true);
        when(esHttpClient.count(old)).thenReturn(5L);                        // 旧索引里有数据
        when(userReviewMapper.pageForIndex(anyLong(), any(), anyInt())).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> adminReviewService.reindex())
                .isInstanceOf(ReviewBusinessException.class)
                .hasMessageContaining("未写入任何文档");

        verify(esHttpClient, never()).switchAlias(any(), any(), any());
        verify(esHttpClient).deleteIndex(target);       // I4：空索引留着就是垃圾
        verify(esHttpClient, never()).deleteIndex(old); // ★ 旧索引与线上查询未受影响
    }

    // ==================== I4：切换成功后的清扫 ====================

    @Test
    @DisplayName("I4 清扫：只删\"既不是新目标、也不是别名当前指向\"的残留（v1 被别名指着、v2 是目标 → 都绝不能删）")
    void sweep_shouldOnlyDeleteIndicesThatAreNeitherTargetNorAliasCurrent() {
        String alias = esProperties.getAlias();
        String target = "review_v2";
        String old = "review_v1";

        when(esHttpClient.mappingJson()).thenReturn("{\"mappings\":{}}");
        when(esHttpClient.resolveTargetIndex(alias)).thenReturn(target);
        when(esHttpClient.currentIndex(alias)).thenReturn(old);   // 别名当前指向 review_v1
        when(esHttpClient.createIndex(anyString(), any())).thenReturn(true);
        when(esHttpClient.count(old)).thenReturn(0L);             // 旧索引是空的 → 放行（不是护栏要拦的场景）
        when(esHttpClient.switchAlias(any(), any(), any())).thenReturn(true);
        when(userReviewMapper.pageForIndex(anyLong(), any(), anyInt())).thenReturn(Collections.emptyList());
        when(esHttpClient.listIndices(alias))
                .thenReturn(Arrays.asList("review_v1", "review_v2", "review_v9"));

        assertThat(adminReviewService.reindex().getIndexed()).isZero();

        // ★ 安全底线：别名当前指向的 review_v1、新目标 review_v2 绝不能被删
        verify(esHttpClient, never()).deleteIndex("review_v1");
        verify(esHttpClient, never()).deleteIndex("review_v2");
        // 只有历史残留 review_v9 该删，而且只删这一次
        verify(esHttpClient).deleteIndex("review_v9");
        verify(esHttpClient, times(1)).deleteIndex(anyString());
    }

    @Test
    @DisplayName("I4 清扫：切换成功后别名已指向新目标 → 旧索引照样在清扫里被删掉（不是靠 deleteIndex(old)）")
    void sweep_shouldStillDeleteTheOldIndex() {
        String alias = esProperties.getAlias();
        String target = "review_v2";
        String old = "review_v1";

        when(esHttpClient.mappingJson()).thenReturn("{\"mappings\":{}}");
        when(esHttpClient.resolveTargetIndex(alias)).thenReturn(target);
        // 第 1 次（动手前）= 旧索引 review_v1；第 2 次（清扫时）= 切换后的别名指向 review_v2
        when(esHttpClient.currentIndex(alias)).thenReturn(old).thenReturn(target);
        when(esHttpClient.createIndex(anyString(), any())).thenReturn(true);
        when(esHttpClient.count(old)).thenReturn(0L);
        when(esHttpClient.switchAlias(any(), any(), any())).thenReturn(true);
        when(userReviewMapper.pageForIndex(anyLong(), any(), anyInt())).thenReturn(Collections.emptyList());
        when(esHttpClient.listIndices(alias))
                .thenReturn(Arrays.asList("review_v1", "review_v2", "review_v9"));

        adminReviewService.reindex();

        verify(esHttpClient).deleteIndex("review_v1");   // 旧索引仍然会被删（施工图第 5 步的保证没丢）
        verify(esHttpClient).deleteIndex("review_v9");   // 历史残留一起清
        verify(esHttpClient, never()).deleteIndex("review_v2"); // ★ 新目标（= 别名现在指向的）绝不删
    }

    @Test
    @DisplayName("I4 清扫是尽力而为：拿不到索引列表 → 只记日志，重建仍然算成功")
    void sweep_whenListIndicesFails_shouldNotFailTheReindex() {
        String alias = esProperties.getAlias();
        String target = "review_v2";

        when(esHttpClient.mappingJson()).thenReturn("{\"mappings\":{}}");
        when(esHttpClient.resolveTargetIndex(alias)).thenReturn(target);
        when(esHttpClient.currentIndex(alias)).thenReturn(null);   // 首次重建 → 旧索引为空 → 放行
        when(esHttpClient.createIndex(anyString(), any())).thenReturn(true);
        when(esHttpClient.switchAlias(any(), any(), any())).thenReturn(true);
        when(userReviewMapper.pageForIndex(anyLong(), any(), anyInt())).thenReturn(Collections.emptyList());
        // 查询失败（客户端契约是抛业务异常）—— 清扫必须自己兜住，不能让整次重建失败
        when(esHttpClient.listIndices(alias))
                .thenThrow(new ReviewBusinessException("查询 ES 索引信息失败"));

        assertThat(adminReviewService.reindex().getFailed()).isZero();
        verify(esHttpClient, never()).deleteIndex(anyString());
    }

    @Test
    @DisplayName("I2：indexed 必须按【真实写入条数】累加 —— 两批各 2 条、实际只写成功 2+1 条 → indexed 必须是 3，不是 4")
    void reindex_shouldAccumulateRealIndexedCount_notBatchSize() {
        String alias = esProperties.getAlias();
        String target = "review_v2";

        when(esHttpClient.mappingJson()).thenReturn("{\"mappings\":{}}");
        when(esHttpClient.resolveTargetIndex(alias)).thenReturn(target);
        when(esHttpClient.currentIndex(alias)).thenReturn(null);
        when(esHttpClient.createIndex(anyString(), any())).thenReturn(true);
        when(esHttpClient.switchAlias(any(), any(), any())).thenReturn(true);
        when(esHttpClient.listIndices(alias)).thenReturn(Collections.singletonList(target));
        when(userReviewMapper.pageForIndex(anyLong(), any(), anyInt()))
                .thenReturn(batch(9001L, 9002L))                  // 第 1 批：2 条，ES 只成功 2 条
                .thenReturn(batch(9003L, 9004L))                  // 第 2 批：2 条，ES 只成功 1 条
                .thenReturn(Collections.emptyList());             // 第 3 批：空 → 结束
        when(esHttpClient.bulk(eq(target), anyList()))
                .thenReturn(BulkResult.builder().indexed(2).failed(0).firstErrors(Collections.emptyList()).build())
                .thenReturn(BulkResult.builder().indexed(1).failed(0).firstErrors(Collections.emptyList()).build());

        // 按批次条数累加会得到 4；按真实写入条数累加才是 3（这正是 I2 要修的那条）
        assertThat(adminReviewService.reindex().getIndexed()).isEqualTo(3);
    }

    private List<ReviewIndexDoc> batchOfTwo() {
        return batch(9001L, 9002L);
    }

    /** 造一批只带 reviewId 的文档（够 reindex 推进游标与统计条数用） */
    private List<ReviewIndexDoc> batch(Long... reviewIds) {
        List<ReviewIndexDoc> docs = new java.util.ArrayList<>();
        for (Long id : reviewIds) {
            docs.add(ReviewIndexDoc.builder().reviewId(id).orderId(880000L + id).score(5)
                    .content("还行").createTime("2026-10-06 10:00:00").build());
        }
        return docs;
    }
}
