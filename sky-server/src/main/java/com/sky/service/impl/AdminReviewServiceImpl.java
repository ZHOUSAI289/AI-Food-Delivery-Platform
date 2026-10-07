package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.result.BulkResult;
import com.sky.client.EsHttpClient;
import com.sky.constant.MessageConstant;
import com.sky.dto.ReviewPageQueryDTO;
import com.sky.entity.ReviewIndexDoc;
import com.sky.exception.ReviewBusinessException;
import com.sky.mapper.UserReviewMapper;
import com.sky.properties.EsProperties;
import com.sky.result.PageResult;
import com.sky.service.AdminReviewService;
import com.sky.vo.ReviewPageVO;
import com.sky.vo.ReviewReindexVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@Service
@Slf4j
public class AdminReviewServiceImpl implements AdminReviewService {

    /**
     * 重建索引的【进程内】互斥锁（R4）。
     *
     * 【为什么需要】两次并发重建会互相踩：后完成的那次清扫（sweepStaleIndices）会把
     * 先完成者记下的"别名当前指向"当成历史残留，把【别人正在灌的索引】删掉；两次重建
     * 还会各自建新索引、互相切别名，留下孤儿索引和莫名其妙的失败。
     *
     * 【为什么是 ReentrantLock 而不是 synchronized】要用 tryLock() 做到"拿不到就立刻失败"，
     * 而不是排队等待 —— 这是管理端手动触发的运维接口，等下去没有任何意义。
     *
     * 【取舍】单实例部署用进程内锁就够。将来横向扩容成多实例时，每个 JVM 各有一把锁，
     * 等于没有互斥，必须换成 Redis 分布式锁（SETNX + 过期时间 + 唯一 value 校验后删除）。
     */
    private final ReentrantLock reindexLock = new ReentrantLock();

    @Autowired
    private UserReviewMapper userReviewMapper;
    @Autowired
    private EsProperties esProperties;
    @Autowired
    private EsHttpClient esHttpClient;

    /**
     * 分页查询用户评论
     * @param reviewPageQueryDTO
     * @return
     */
    public PageResult pageQuery(ReviewPageQueryDTO reviewPageQueryDTO) {
        PageHelper.startPage(reviewPageQueryDTO.getPage(), reviewPageQueryDTO.getPageSize());
        Page<ReviewPageVO> page = userReviewMapper.pageQuery(reviewPageQueryDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 全量重建评价索引（无参数：本期只做全量，窗口参数等 §4 之后再引入）。
     *
     * 【并发互斥（R4）】同一时刻只允许一次重建：拿不到锁【立刻】抛业务异常（code=0），
     * 不阻塞等待 —— 管理端手动触发的运维接口，排队等下去没有意义。
     * 锁必须在 finally 里释放（异常路径、bulk 失败路径都不能把锁漏掉，否则后续再也重建不了）。
     */
    public ReviewReindexVO reindex() {
        // 拿不到锁 = 另一次重建正在进行 → 立刻返回业务错误，绝不阻塞等待
        if (!reindexLock.tryLock()) {
            throw new ReviewBusinessException(MessageConstant.REVIEW_INDEX_BUSY);
        }
        try {
            return doReindex();
        } finally {
            // ★ 必须释放：四个 throw 出口（建索引失败 / bulk 失败 / 空索引护栏 / 切别名失败）
            // 都经过这里，漏掉就是永久死锁。
            reindexLock.unlock();
        }
    }

    /** reindex 的实际逻辑（已持有 reindexLock；拆出来只为让 try/finally 结构一眼可见） */
    private ReviewReindexVO doReindex() {
        String alias = esProperties.getAlias(); // 索引别名
        // 这两句是"动手之前必须先知道"的事。查询失败时客户端会抛 ReviewBusinessException
        // （不再返回 null 让调用方猜"是不是没有旧索引"），所以连不上 ES 时绝不会走到
        // 建索引 / 切别名那一步 —— 一个连不上的 ES 不该把线上别名改坏。
        String target = esHttpClient.resolveTargetIndex(alias); // 目标索引
        String old = esHttpClient.currentIndex(alias); // 旧索引（null = 别名确实不存在 → 首次重建）

        // 建新索引（mapping 为空时 createIndex 内部会直接失败）
        // 【I4 的边界】这里失败时【不清理】：索引很可能根本没建起来（mapping 空时连请求都没发），
        // 去删它只会多一条 404 日志。清理只从"建成功之后"的中止路径开始。
        if(!esHttpClient.createIndex(target, esHttpClient.mappingJson())){
            throw new ReviewBusinessException(MessageConstant.REVIEW_INDEX_CREATE_FAILED);
        }

        long lastId = 0L; // 上次写入的 ID
        int indexed = 0; // 已【真实写入成功】的条数（不再按批次条数累加）
        int batchSize = esProperties.getBatchSize(); // 批量大小

        while (true){
            // beginTime 传 null = 从头全量。pageForIndex 的 beginTime 参数保留（将来窗口重建用），
            // 但本期 days 已砍掉，没有任何调用方会传非 null —— 窗口语义与"整别名替换"自相矛盾
            // （{"days":90} 会变成"索引从此只保留 90 天"，窗口外的历史评价从检索里消失），
            // 所以留给 §4 之后再引入。
            List<ReviewIndexDoc> batch = userReviewMapper.pageForIndex(lastId, null, batchSize);
            if(batch == null || batch.isEmpty()){
                break;
            }
            // 脱敏后再进 ES（手机号原文不出库；ES 的访问面比 MySQL 大）
            batch.forEach(d -> d.setContent(maskPhone(d.getContent())));

            BulkResult result = esHttpClient.bulk(target,batch);
            if(result == null || result.getFailed() > 0){
                int failed = (result == null) ? batch.size() : result.getFailed();
                int written = indexed + ((result == null) ? 0 : result.getIndexed());
                // firstErrors 现在能传到调用方（异常消息里），不再只进日志
                log.error("重建中止：目标索引 {} 写入失败（已写入 {} 条 / 本批失败 {} 条），别名 {} 保持不动，错误摘要={}",
                        target, written, failed, alias,
                        (result == null ? "无结果对象" : result.getFirstErrors()));
                // I4 中止路径：这个索引没被切换、数据也不完整，删掉它（删失败只记日志，不盖掉下面的异常）
                deleteTargetOnAbort(target, "bulk 写入失败");
                throw new ReviewBusinessException(
                        String.format(MessageConstant.REVIEW_INDEX_BULK_FAILED, written, failed));
            }

            indexed += result.getIndexed(); // 按【真实写入条数】累加
            lastId = batch.get(batch.size() - 1).getReviewId();
            log.info("重建进度：已写入 {} 条（目标索引 {}）", indexed, target);
        }

        // ================= R2 护栏：一条都没写进去，绝不能拿去切别名 =================
        // 循环 0 次迭代的原因有很多：batch-size 配成 0/负数、SQL 回归、将来任何取数 bug。
        // 如果照切不误，就会把别名切到一个空索引上、并删掉旧索引。
        //
        // 【注意不能写成"indexed == 0 就中止"】—— 库里确实一条评价都没有（首次重建 /
        // 空库）是合法场景，必须放行。所以只在【旧索引存在且有文档】时才认定这次取数出错了。
        // count(old) 查不出来时会抛业务异常（不会返回 0），也就不会把"查不出来"当成"空索引"。
        if (indexed == 0 && old != null && !old.isEmpty()) {
            long oldCount = esHttpClient.count(old);
            if (oldCount > 0) {
                log.error("重建中止：目标索引 {} 一条都没写进去，但旧索引 {} 有 {} 条文档，拒绝切换别名（未删旧索引）",
                        target, old, oldCount);
                // I4 中止路径（同上）：空索引留着就是垃圾
                deleteTargetOnAbort(target, "拒绝切换到空索引");
                throw new ReviewBusinessException(MessageConstant.REVIEW_INDEX_EMPTY_ABORT);
            }
            log.info("本次未写入任何文档，旧索引 {} 也是空的（{} 条），按合法场景继续切别名", old, oldCount);
        }

        // 全部成功 → 一次请求原子切别名
        if (!esHttpClient.switchAlias(alias,target,old)){
            // 【这里不清理 target】切别名可能"响应丢失但实际生效"（超时/网络抖动），
            // 此时别名可能已经指向 target，删它等于删掉线上正在用的索引。宁可留一个完整索引，
            // 由下一轮重建的清扫顺手删掉（那时它既不是新目标、也不被别名指向）。
            throw new ReviewBusinessException(MessageConstant.REVIEW_INDEX_SWITCH_FAILED);
        }

        // 切换成功 → 清扫历史残留：删掉 {alias}_v* 里"既不是新目标、也不是别名当前指向"的那些。
        // 旧索引 old 自然落在这一批里（切换成功后别名已指向 target），所以它照样会被删掉；
        // 删失败、或上一次中止留下的孤儿索引，也在这里一起清掉（施工图 §2 承诺的"下轮重建顺手清理"）。
        //
        // 【R4：这段清扫正是"必须互斥"的原因】它按"别名当前指向"判断谁是残留，而这个判断
        // 对【另一次并发重建】是不成立的：B 正在灌 review_v4、A 刚把别名切到 review_v5 并开始清扫，
        // A 查到的"当前指向"是 review_v5 → review_v4 就被当成残留删掉（而 B 还在往里写）。
        // reindexLock 把这种交错彻底排除在进程内，所以清扫不需要再额外区分"别人正在灌的索引"。
        sweepStaleIndices(alias, target);

        log.info("重建完成：indexed={} 别名 {} -> {}", indexed, alias, target);
        return new ReviewReindexVO(indexed, 0);
    }

    /**
     * 切换成功后的清扫（I4 的第二半）：列出现有 `{alias}_v*`，删掉既不是新目标、
     * 也不是别名【当前】（重新查询，不信记忆）指向的那些。
     *
     * 【为什么是"尽力而为"】拿不到列表 / 查不到别名指向 → 记日志跳过，绝不让整次重建失败：
     * 重建本身已经成功（别名已切到完整的新索引），清扫只是顺手做的事。
     *
     * 【安全底线（本次最容易写出事故的地方）】target 与"别名当前指向的索引"绝不删。
     * 保护集合来自一次【新的查询】而不是变量 old —— 万一 old 与别名实际指向不一致，
     * 也绝不会把线上正在用的索引删掉。
     */
    private void sweepStaleIndices(String alias, String target) {
        try {
            String current = esHttpClient.currentIndex(alias);
            List<String> indices = esHttpClient.listIndices(alias);
            int removed = 0;
            for (String name : indices) {
                if (name == null || name.equals(target) || name.equals(current)) {
                    continue; // ★ 绝不删新目标索引，绝不删别名当前指向的索引
                }
                try {
                    esHttpClient.deleteIndex(name);
                    removed++;
                } catch (Exception e) {
                    log.error("清扫残留索引 {} 失败（继续清扫其它）：{}", name, e.toString());
                }
            }
            log.info("清扫历史残留索引：候选 {} 个，已删 {} 个（保护 target={} / 别名指向={}）",
                    indices == null ? 0 : indices.size(), removed, target, current);
        } catch (Exception e) {
            log.error("清扫历史残留索引失败（只记日志，不影响本次重建结果）：alias={} target={} err={}",
                    alias, target, e.toString());
        }
    }

    /**
     * 中止路径：删掉本次刚建好的目标索引（没被切换、数据也不完整 → 留着就是垃圾）。
     * 【只在建索引成功之后调用】建索引失败时索引可能根本没建起来，删它只会多一条 404 日志。
     * 【绝不盖掉原来的异常】删除失败只记日志。
     */
    private void deleteTargetOnAbort(String target, String why) {
        try {
            esHttpClient.deleteIndex(target);
            log.info("中止路径已删除本次新建的目标索引 {}（原因：{}）", target, why);
        } catch (Exception e) {
            log.error("中止路径删除目标索引 {} 失败（只记日志，不影响中止原因）：{}", target, e.toString());
        }
    }

    /** 手机号脱敏：1[3-9]\d{9} → *********** */
    private String maskPhone(String content) {
        return content == null ? null : content.replaceAll("1[3-9]\\d{9}", "***********");
    }
}
