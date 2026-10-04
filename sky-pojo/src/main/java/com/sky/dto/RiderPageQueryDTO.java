package com.sky.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 骑手分页查询入参（管理端 R1）。
 *
 * 字段名和 EmployeePageQueryDTO 保持一致（name / page / pageSize），
 * 因为前端分页组件传的就是这三个；分页参数走 query string，
 * 由 Spring 直接绑定到这些字段（GET 请求、不加 @RequestBody）。
 */
@Data
public class RiderPageQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    // 骑手姓名，模糊查询
    private String name;

    // 页码
    private int page;

    // 每页显示记录数
    private int pageSize;
}
