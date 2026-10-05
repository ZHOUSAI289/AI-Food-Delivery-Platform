package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 员工列表项 / 员工详情（管理端）。
 *
 * 【为什么必须用 VO，而不是直接返回 Employee 实体】
 * employee 表上有 password（MD5 十六进制）。管理端的分页接口
 * （GET /admin/employee/page）原来是直接返回 Page<Employee> 的，实测响应里带着
 * 真实的密码哈希 —— 也就是说凡是能调管理端接口的人，都能拿到全部员工的密码。
 *
 * 用 VO 是从【类型层面】排除敏感字段：这个类根本没有 password 属性，
 * 所以不管谁怎么写、怎么写错，都漏不出去。相比之下"在 Service 里 setNull"
 * 只是一种需要人记得的约定 —— 本项目的 getById 就是靠 setPassword("****") 挡的，
 * 而分页那条路径谁也没记得，于是漏了。
 *
 * 配套地，分页 SQL 也改成了显式列名（不再 select *）：以后给 employee 表加敏感列时，
 * 不会因为"多了一列"就自动跟着返回。
 *
 * 这里刻意【没有】password，也刻意没有 create_user / update_user：
 * 后者是内部操作人 id，界面不展示，没必要对外。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    // 登录账号
    private String username;

    private String name;

    private String phone;

    // 性别：1 男 / 0 女
    private Integer sex;

    private String idNumber;

    // 账号状态：1 启用 / 0 禁用
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
