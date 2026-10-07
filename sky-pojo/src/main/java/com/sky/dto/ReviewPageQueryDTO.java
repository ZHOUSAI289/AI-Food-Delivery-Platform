package com.sky.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 管理端评价分页查询入参（GET /admin/review/page）。
 *
 * 【为什么 page / pageSize 用基本类型 int】
 * 与本项目其它分页 DTO（EmployeePageQueryDTO、RiderPageQueryDTO）保持一致。
 *
 * 【为什么时间范围用 String 而不是 LocalDateTime】
 * 这两个值是从 URL 查询参数绑定的，绑到 LocalDateTime 需要额外加
 * @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")，少写这个注解就会绑定失败。
 * 用 String 则原样传进 SQL，由 MySQL 把字符串和 datetime 列比较（'2026-10-01 00:00:00' 可直接比较），
 * 少一层格式转换、也少一个坑。格式固定：yyyy-MM-dd HH:mm:ss。
 *
 * 【keyword 的归宿】
 * 阶段①用 MySQL like 匹配 content；
 * 阶段③ ES 上线后应改成"先在 ES 检索出 reviewId，再回 MySQL 取这一页"，
 * 否则中文分词和相关性排序都是缺的。
 */
@Data
public class ReviewPageQueryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 页码，从 1 开始 */
    private int page;

    /** 每页条数 */
    private int pageSize;

    /** 只看差评就传 1 或 2；不传表示全部 */
    private Integer score;

    /** 起始时间，格式 yyyy-MM-dd HH:mm:ss，可空 */
    private String beginTime;

    /** 结束时间，格式 yyyy-MM-dd HH:mm:ss，可空 */
    private String endTime;

    /** 内容关键词，可空 */
    private String keyword;
}
