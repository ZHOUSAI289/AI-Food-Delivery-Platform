package com.sky.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 提交评价的入参（POST /user/review）。
 *
 * 【为什么这里没有 userId】
 * 评价人只能由 token 决定（服务端从 BaseContext.getCurrentId() 取）。
 * 如果让前端传，就等于允许任何人以别人的名义评价 —— 这类"身份字段"一律不进 DTO。
 *
 * 【为什么这里没有 id / sentiment / tags / createTime】
 * 它们都是系统生成或系统分析的结果。让前端能写 sentiment/tags，
 * 就等于允许伪造"这条评价是好评/差评"，后面管理端的洞察全会被污染。
 *
 * 校验（1~5 星、content ≤500 字）由 Service 手写完成，不用 @Valid：
 * 本项目没有 MethodArgumentNotValidException 的处理器，用 @Valid 会返回 400/500，
 * 而不是约定的 HTTP 200 + code=0 + 友好提示。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewSubmitDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单 id：必须是本人、且状态为 5（已完成）的订单 */
    private Long orderId;

    /** 打分：1~5 星，必填 */
    private Integer score;

    /** 文字评价：可空，≤500 字 */
    private String content;
}
