package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 评价回显（C 端：GET /user/review/order/{orderId}）。
 *
 * 【为什么只有这 5 个字段】
 * 用户查自己某一单评过没有，需要的就是这些。
 * 刻意【不包含】：
 *   - sentiment / tags：系统的分析结果，属于内部数据
 *   - userName：是评价人自己的名字，返回没有意义
 *   - dishes：从订单明细聚合出来的菜名，是管理端视角
 * 用 VO 而不是直接返回 Review 实体，就是为了让"不该出现的字段"在类型上就不存在，
 * 而不是靠"记得别 set"（项目里 getById 曾经用 setPassword("****") 的方式挡密码，就是这么漏的）。
 *
 * createTime 交给项目的 JacksonObjectMapper 统一格式化成 yyyy-MM-dd HH:mm:ss。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 评价 id */
    private Long id;

    /** 订单 id */
    private Long orderId;

    /** 打分：1~5 星 */
    private Integer score;

    /** 文字评价，可能为空（只打分的评价） */
    private String content;

    private LocalDateTime createTime;
}
