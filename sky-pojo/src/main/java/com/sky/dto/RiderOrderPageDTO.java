package com.sky.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 骑手配送订单分页查询。
 *
 */
@Data
public class RiderOrderPageDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    // 配送状态
    private Integer status;

    // 页码
    private int page;

    // 每页显示记录数
    private int pageSize;
}
