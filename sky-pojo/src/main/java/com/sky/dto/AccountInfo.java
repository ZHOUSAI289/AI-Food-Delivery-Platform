package com.sky.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一登录的「账号查询结果」，不是前端入参，只在内部传。
 *
 * 由 LoginMapper 用一条 UNION ALL 把 employee / rider / user 三张表查出来的行装进这里，
 * role 是 SQL 里写死的字面量（'ADMIN' / 'RIDER' / 'USER'）。
 *
 * 一条 SQL 拿到 0~3 行：
 *   0 行 -> 账号不存在
 *   1 行 -> 正常，按 role 继续验密码
 *   ≥2 行 -> 账号在多端重名，必须报错拒绝，不能取第一个（见 LoginServiceImpl）
 */
@Data
public class AccountInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 角色：ADMIN / RIDER / USER，见 RoleConstant */
    private String role;

    /** 该角色对应表里的主键 id */
    private Long id;

    /** 姓名 */
    private String name;

    /** 库里存的 MD5 密码 */
    private String password;

    /** 状态：0 禁用 1 启用 */
    private Integer status;

}
