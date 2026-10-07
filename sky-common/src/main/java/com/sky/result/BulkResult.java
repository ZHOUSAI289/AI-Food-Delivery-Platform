package com.sky.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * `_bulk` 的逐条统计结果（对应施工图 §3 的 `BulkResult { indexed; failed; firstErrors }`）。
 *
 * 【为什么必须是结果对象，而不是 boolean】
 * 原来是 `boolean bulk(...)`，失败时调用方只能拿到"整批都失败"这一个信息：
 * 报出来的失败数只能是 `batch.size()`（哪怕这批里 500 条只错了 1 条），
 * 已经写成功的那些既不计入 indexed 也不区分，indexed + failed 对不上总数；
 * `firstErrors` 又只进了日志，调用方拿不到失败原因。施工图 §3 早就定义了这三个字段，
 * 这里把它补回来。
 *
 * 【约定】不向调用方抛异常（除了查询类方法的既有约定）：由业务层看 `failed` 决定中止还是继续，
 * 失败时的响应码由业务层抛 ReviewBusinessException → `code=0` 来表达（与 createIndex / switchAlias
 * 一致：**最需要被注意的 bulk 失败不能悄悄走 code=1**）。
 *
 * 【保守判定】请求失败（resp == null）或响应解析不出 items 时，一律按"整批失败"：
 * `indexed = 0`、`failed = 本批条数`、`firstErrors` 写明原因。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkResult implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 逐条统计的写入成功条数（status == 200 覆盖 / 201 新建） */
    private int indexed;

    /** 逐条统计的写入失败条数（status 既不是 200 也不是 201，含解析不出 items 的整体失败） */
    private int failed;

    /** 前最多 3 条错误摘要（_id / status / error.type / error.reason），避免日志与响应爆掉 */
    private List<String> firstErrors;
}
