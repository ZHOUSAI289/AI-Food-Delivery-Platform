package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 评论（表 review）
 *
 * 【哪些字段是用户填的、哪些是系统写的】
 *   用户填：score、content（content 允许为空 —— 只打分也算评价）
 *   系统填：userId（从 token 取，不接受前端传）、sentiment / tags（阶段③异步分析写入）、createTime
 *
 * 【为什么 sentiment / tags 在①阶段必须保持 null】
 * "为空"就是"还没分析"的唯一标记：阶段③的 MQ 消费者正是靠"这两列有没有值"来判断幂等
 * （重复投递同一条消息时直接跳过）。一旦①阶段写入默认值（比如 0），
 * 就再也分不清"没分析"和"分析完了但没抽到标签"，幂等判断会失效。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Review implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    //订单ID
    private Long orderId;

    //用户ID
    private Long userId;

    //评价等级
    private Integer score;

    //评论
    private String content;

    //评论的性质：1 正面 / 0 中性 / -1 负面
    private Integer sentiment;

    //口味标签，逗号分隔，如 "太咸,份量少"：阶段③异步分析写入，①阶段保持 null
    private String tags;

    //评价时间：表上这列是 NOT NULL，插入时必须给值（ReviewVO / ReviewPageVO 都要回显它）
    private LocalDateTime createTime;

}
