package com.sky.service;

import com.sky.dto.ReviewPageQueryDTO;
import com.sky.result.PageResult;
import com.sky.vo.ReviewReindexVO;

public interface AdminReviewService {

    /**
     * 分页查询用户评论
     * @param reviewPageQueryDTO
     * @return
     */
    PageResult pageQuery(ReviewPageQueryDTO reviewPageQueryDTO);

    /**
     * 全量重建评价索引。无参数 —— 本期只做全量（`days` 窗口已砍掉，见 ReviewController#reindex 注释）。
     *
     * <p>【本接口不可并发调用】进程内互斥（`ReentrantLock#tryLock`）保证同一时刻只有一次重建；
     * 并发的第二次调用会【立刻】抛 {@link com.sky.exception.ReviewBusinessException}（code=0），
     * 不排队、不阻塞 —— 这是管理端手动触发的运维接口。
     *
     * <p>【多实例部署需换成 Redis 分布式锁】进程内锁只在"同一个 JVM"里有效。一旦横向扩容成多实例，
     * 每个实例各有一把锁 = 没有互斥，必须换成 Redis 的 `SETNX`（带过期时间）。原因见
     * {@code AdminReviewServiceImpl#reindex} 的字段注释：并发重建会让先完成者的清扫把后者
     * 正在灌的索引当成"残留"删掉。
     *
     * @return 本次真实写入的条数（indexed）与失败条数（failed，成功时恒为 0）
     */
    ReviewReindexVO reindex();
}
