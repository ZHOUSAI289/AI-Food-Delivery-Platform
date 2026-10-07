package com.sky.client;

import com.sky.entity.ReviewIndexDoc;
import com.sky.result.BulkResult;

import java.util.List;

public interface EsHttpClient {
    /** 读 classpath 下的 mapping（resources/es/review-index-mapping.json） */
    String mappingJson();

    /**
     * 当前被别名指向的物理索引；【别名确实不存在】返回 null（首次重建）。
     * 这个 null 只有一种含义：ES 回了空数组。查询本身失败（网络不通/超时/响应解不出）
     * 一律抛 ReviewBusinessException —— 绝不能把"没查出来"当成"没有旧索引"，
     * 否则 switchAlias 只会发 add，别名会同时指向新旧两个索引。
     */
    String currentIndex(String alias);

    /**
     * 下一个要用的物理索引名，如 review_v2（首次 review_v1）。
     * 查询失败（网络不通/超时/响应解不出）抛 ReviewBusinessException —— 调用方必须在碰
     * 索引/别名之前中止，而不是把查不到版本号当成 max=0 去猜 review_v1。
     */
    String resolveTargetIndex(String alias);

    boolean createIndex(String index, String mappingJson);

    /** 删索引。index 为 null 时直接返回（首次重建没有旧索引） */
    void deleteIndex(String index);

    /** 一次请求里同时 remove 旧 + add 新，ES 保证原子 */
    boolean switchAlias(String alias, String newIndex, String oldIndex);

    boolean indexDoc(String index, String id, String docJson);

    /**
     * 批量写入；内部拼 NDJSON，返回**逐条统计**的结果（indexed / failed / firstErrors）。
     * 【必须是结果对象，不能是 boolean】见 BulkResult 的类注释：失败数、已写入数、失败原因
     * 都得让调用方看得到，否则调用方只能把"整批条数"当成失败数报出去。
     * 请求失败 / 响应解析不出 items 时保守地按"整批失败"返回（indexed=0、failed=本批条数）。
     */
    BulkResult bulk(String index, List<ReviewIndexDoc> docs);

    /**
     * 列出 `{alias}_v*` 这些物理索引名（GET /_cat/indices/{alias}_v*?h=index&format=json）。
     * 语义与 resolveTargetIndex 一致：查询失败（网络不通/超时/响应解不出）抛 ReviewBusinessException。
     *
     * 【调用方注意】I4 的"切换成功后清扫"是**尽力而为**的活儿：拿不到列表就记日志跳过，
     * 绝不能让整次重建失败 —— 所以那个 try-catch 在服务层（清扫逻辑所在的那一层），
     * 而不是把这个方法改成像"查不到就返回空列表"那样会骗人的语义
     * （空列表 = 没有任何残留索引，与"没查到"混在一起就是这个模块的老毛病）。
     */
    List<String> listIndices(String alias);

    /**
     * 索引 / 别名下的文档数（POST /{index}/_count）。
     * 【索引不存在返回 0】—— 这是明确的业务事实（空索引），不是失败。
     * 查询失败（网络不通/超时/响应解不出）抛 ReviewBusinessException，绝不返回 0：
     * 否则"计数查不出来"会被当成"旧索引是空的"，空索引护栏就形同虚设。
     */
    long count(String indexOrAlias);

    /** 返回 ES 的原始响应（§3.5 的聚合自己解 JSON） */
    String search(String indexOrAlias, String queryJson);                               // 读 resources/es/review-index-mapping.json
}
