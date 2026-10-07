package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewIndexDoc {
    private Long reviewId;
    private Long orderId;
    private Integer score;
    private Integer sentiment;
    private String tags;        // 逗号分隔的字符串 → split 成数组再放进 ES
    private String dishIds;
    private String dishNames;
    private String content;           // 写 ES 前脱敏
    private String createTime;        // 字符串即可，ES 那边按 format 解析
}
