package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 「再来一单」的结果
 *
 * 【为什么需要这个 VO，而不是直接返回一个字符串提示语】
 * 再来一单可能只成功一部分：订单里有 3 个菜，其中 1 个已经下架或已被删除，
 * 剩下 2 个正常加进购物车。这时候前端要做两件不同的事：
 *   1. 告诉用户哪几个没加进去
 *   2. 判断"一个都没加进去"（addedCount == 0）时不要跳转到购物车，而是停在原地提示
 * 如果只返回一个拼好的字符串，第 2 件事就只能靠解析文字来判断，很脆弱。
 * 所以这里返回结构化的统计，文案交给前端自己组。
 *
 * 【addedCount 的口径：明细"行数"，不是商品"件数"】
 * 一张订单有 3 行明细、其中 1 行被跳过，addedCount 就是 2（不是剩余商品的件数总和）。
 * 因为 skippedNames 是按行记的，两个字段口径必须一致，否则前端拼提示语会自相矛盾。
 * 例：addedCount=2 而 skippedNames 只有 1 个名字，前端会说"2 个菜品已下架"，就错了。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepetitionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 成功加入购物车的明细行数
     * 全部商品都已下架或已删除时为 0
     */
    private Integer addedCount;

    /**
     * 因为已下架或已被删除而未能加入购物车的商品名称
     * 取的是订单明细里记录的名字（不是当前菜品表里的），
     * 因为菜品被物理删除后根本查不到名字，而订单里那份一定存在
     */
    private List<String> skippedNames;
}
