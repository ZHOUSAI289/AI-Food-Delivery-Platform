package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewReindexVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer indexed;// 成功写入条数

    private Integer failed;// 失败条数
}
