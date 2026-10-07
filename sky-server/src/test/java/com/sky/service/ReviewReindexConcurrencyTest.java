package com.sky.service;

import com.sky.client.EsHttpClient;
import com.sky.entity.ReviewIndexDoc;
import com.sky.exception.ReviewBusinessException;
import com.sky.mapper.UserReviewMapper;
import com.sky.properties.EsProperties;
import com.sky.result.BulkResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R4（并发互斥）的回归网：已经有了一次重建在进行时，第二次 reindex() 必须【立刻】失败，
 * 而不是排队等待、更不是一起跑进去互相踩。
 *
 * 【为什么必须互斥（评审 R4）】
 * 两次并发重建若错开版本号（v3 / v4），先切完别名的那次会执行清扫（sweepStaleIndices）——
 * 它按"别名当前指向"判断谁是残留，于是会把【后完成者正在灌的索引】当成残留删掉；
 * 两次重建还会各自 createIndex、互相切别名，留下孤儿索引和莫名其妙的失败。
 * 铁律（不切到残缺索引）没被破坏，但代价是一次莫名的 500 + 一个永不清理的索引。
 *
 * 【这条测试怎么做到"确定地并发"】
 * 不让第一个线程去 bulk 里真的卡一会儿（那是 flaky 的经典写法），而是：
 *   ① 第一个线程在后台调 reindex()，并让它【停在 bulk 里】—— bulk 的桩用 CountDownLatch 卡住；
 *   ② 主线程等到 bulkStarted 这个信号，才确认"锁确实被第一个线程持有"；
 *   ③ 这时主线程调 reindex() → 断言拿到 REVIEW_INDEX_BUSY 业务异常（不是等待、不是成功）。
 * 所以"谁先拿到锁"是确定的，没有任何 sleep 竞态。
 *
 * 【不让测试挂死的三道保险】
 *   1. bulk 的桩里 await(bulkStarted 相关的 latch) 用的是【带超时】的 await，超时也放行；
 *   2. 主线程的 bulkStarted.await(...) 也带超时，超时就断言失败而不是永久阻塞；
 *   3. releaseBulk 在 @AfterEach 里无条件 countDown()——即使断言先失败，卡住的线程也能走完。
 *
 * 【为什么用 @MockitoBean 而不是 @MockBean】
 * Boot 3.5 里 @MockBean 已被标记为 deprecated（编译告警），@MockitoBean 是官方替代，
 * 语义相同（@MockitoBean 在 Spring Framework 6.2 / Boot 3.5 可用）。本类直接用它。
 *
 * 【为什么不真连 ES / 不用真 MySQL 数据】
 * 本环境连不上虚拟机上的 ES（192.168.100.129:9200），真调必然假红；取数也一并打桩，
 * 于是本类完全不依赖库里有没有评价数据，测的就是"互斥这一段"本身。
 * RANDOM_PORT 的原因同其它评价测试：WebSocketConfiguration 需要真实 Servlet 容器。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReviewReindexConcurrencyTest {

    @Autowired
    private AdminReviewService adminReviewService;
    @Autowired
    private EsProperties esProperties;

    @MockitoBean
    private EsHttpClient esHttpClient;
    @MockitoBean
    private UserReviewMapper userReviewMapper;

    /** bulk 被调用时打开（表示"第一个线程已经进到重建中段"） */
    private final CountDownLatch bulkStarted = new CountDownLatch(1);
    /** bulk 的桩在这里等（主线程断言完再放开） */
    private final CountDownLatch releaseBulk = new CountDownLatch(1);

    /** 兜底：断言失败也不能把后台线程永久留在 bulk 里 */
    @AfterEach
    void releaseBlockedBulk() {
        releaseBulk.countDown();
    }

    @Test
    @DisplayName("R4：一次重建进行中（卡在 bulk）→ 第二次 reindex 立刻抛业务异常，不排队、不并发建索引/切别名")
    void concurrentReindex_shouldFailFastInsteadOfRunningTwice() throws Exception {
        String alias = esProperties.getAlias();
        String target = "review_v1";
        String old = "review_v9";

        // 一起灌进去的 1 条文档：保证第一个线程会走到 bulk（而不是空批直接 break）
        when(userReviewMapper.pageForIndex(anyLong(), any(), anyInt()))
                .thenReturn(batch(9001L))
                .thenReturn(Collections.emptyList());
        when(esHttpClient.mappingJson()).thenReturn("{\"mappings\":{}}");
        when(esHttpClient.resolveTargetIndex(alias)).thenReturn(target);
        when(esHttpClient.currentIndex(alias)).thenReturn(old);
        when(esHttpClient.createIndex(anyString(), any())).thenReturn(true);
        when(esHttpClient.switchAlias(any(), any(), any())).thenReturn(true);
        when(esHttpClient.listIndices(alias)).thenReturn(Collections.emptyList());
        // ★ 第一个线程就停在这里：卡住 → 锁一直被它持有，直到测试主动放开
        when(esHttpClient.bulk(eq(target), anyList())).thenAnswer(inv -> {
            bulkStarted.countDown();
            releaseBulk.await(30, TimeUnit.SECONDS); // 带超时，超时也放行（绝不让它永久挂住）
            return BulkResult.builder().indexed(1).failed(0).firstErrors(Collections.emptyList()).build();
        });

        AtomicReference<Throwable> firstThreadError = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                adminReviewService.reindex();
            } catch (Throwable t) {
                firstThreadError.set(t);
            }
        }, "reindex-first");
        first.start();

        boolean secondCallAsserted = false;
        try {
            // ① 等到"第一个线程已进到 bulk"这个信号 —— 此刻锁【确定】被它持有
            //    （这里不能只靠 isAlive 判断：第一个线程可能刚好在信号之后跑完并放锁）
            assertThat(bulkStarted.await(20, TimeUnit.SECONDS))
                    .as("第一个线程应在 20s 内进到 bulk（否则本用例的前提不成立，请检查桩是否配错）")
                    .isTrue();

            // ★ ② 关键断言：锁已被第一个线程持有（它还卡在 bulk 里）→ 第二次调用必须立刻业务异常失败
            assertThatThrownBy(() -> adminReviewService.reindex())
                    .as("并发重建必须被进程内互斥挡住，而不是排队等待或一起跑")
                    .isInstanceOf(ReviewBusinessException.class)
                    .hasMessageContaining("另一次索引重建正在进行");
            // ③ 补一道前提校验：第一个线程此刻【必须还活着】，否则上面的异常就可能来自别的时序。
            //    它同时也是"互斥真的生效了"的证据（锁没生效时第一个线程早就跑完了）。
            assertThat(first.isAlive())
                    .as("第二次被拒绝时，第一个线程必须仍持有锁卡在 bulk 里")
                    .isTrue();
            secondCallAsserted = true;
        } finally {
            // ④ 无论断言成败都要放开第一个线程，否则它会一直卡在 bulk 里
            releaseBulk.countDown();
            first.join(TimeUnit.SECONDS.toMillis(30));
        }

        assertThat(secondCallAsserted).as("第二次调用必须是被互斥拒绝的那一次").isTrue();
        assertThat(first.isAlive()).as("第一个线程应已结束（锁必须被 finally 释放）").isFalse();
        assertThat(firstThreadError.get()).as("第一个线程本身不应失败：%s", firstThreadError.get()).isNull();

        // 被拒绝的那次【什么都没做】：只建了 1 次索引、只切了 1 次别名
        verify(esHttpClient, times(1)).createIndex(target, esHttpClient.mappingJson());
        verify(esHttpClient, times(1)).switchAlias(alias, target, old);

        // 锁在第一个线程正常结束后被释放了 → 现在还能再跑一次（证明没漏 finally unlock）
        assertThat(adminReviewService.reindex().getFailed())
                .as("锁必须已释放：紧接着的这次重建要能正常进入（否则就是漏了 finally unlock）")
                .isZero();
        verify(esHttpClient, times(2)).switchAlias(alias, target, old);
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
