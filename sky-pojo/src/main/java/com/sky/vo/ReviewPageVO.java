package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理端评价列表项（GET /admin/review/page）。
 *
 * 【为什么和 ReviewVO 是两个类，而不是一个带空字段的类】
 * 两者面向的读者不同：C 端只需要"我自己这条评价"，管理端需要的是"这条评价在说什么、谁说的、哪道菜"。
 * 合成一个类会带来两个问题：
 *   1. C 端会收到 userName / dishes 这些它不该看到的字段（隐私 + 无用）；
 *   2. 以后管理端要加字段（比如商家回复），会顺手把 C 端的响应也改了。
 * 本项目既有的 EmployeeVO / RiderVO / OrderVO 也是按用途分的，这里保持一致。
 *
 * 【userName 是脱敏后的展示名，不是手机号原文】
 * 例如 138****0009。不要在 SQL 里直接把 user.phone 查出来再截断 ——
 * 脱敏动作应该发生在"装配 VO"这一步，避免原始手机号在 service/mapper 之间传递、
 * 最后被谁顺手 set 进某个响应里。
 *
 * 【dishes 是这一单涉及的菜名】
 * 由分页 SQL 用 group_concat 一次带出来（左连 order_detail），不要查出 N 条评价再循环查 N 次菜品名。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewPageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 评价 id */
    private Long id;

    /** 订单 id */
    private Long orderId;

    /** 打分：1~5 星 */
    private Integer score;

    /** 文字评价，可能为空 */
    private String content;

    private LocalDateTime createTime;

    /** 阶段③写入：1 正面 / 0 中性 / -1 负面；为 null 表示"还没分析"（前端显示"分析中"） */
    private Integer sentiment;

    /** 阶段③写入：逗号分隔的口味标签，如 "太咸,份量少"；为 null 表示还没分析 */
    private String tags;

    /** 评价人的脱敏展示名，如 138****0009 */
    private String userName;

    /** 这一单涉及的菜名，如 "蜀味水煮草鱼、米饭" */
    private String dishes;
}
