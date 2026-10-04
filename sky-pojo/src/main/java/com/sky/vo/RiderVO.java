package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 骑手列表项（管理端 R1 分页查询的出参元素）。
 *
 * 【为什么必须用 VO，而不是直接返回 Rider 实体】
 * Rider 实体上有 password 字段。管理端的员工分页（GET /admin/employee/page）就是
 * 直接返回了 Page<Employee>，结果把真实的密码哈希发给了前端 ——
 * 实测 records[0].password = "e10adc3949ba59abbe56e057f20f883e"（就是 123456 的 MD5）。
 * 用 VO 是从【类型层面】排除敏感字段，比"记得在某个地方 setNull"可靠得多：
 * 后者只要有人加一个新的查询入口忘了写，就又漏了（员工那条就是这么漏的）。
 *
 * 这里刻意【没有】password。
 *
 * 【status 和 online 是两个不同的东西，别混】
 *   status —— 账号状态（1 启用 / 0 停用），只有管理员能改，登录时校验的就是它
 *   online —— 接单状态（1 上线 / 0 离线），只有骑手本人能改
 * 两者以前共用一个字段，后果是"骑手一下线就再也登不进自己的账号"：
 * 登录会因为 status=0 报"账号被锁定"，而他改回来必须先能登录。见接口文档 §2.1。
 *
 * ⚠️ 派单（OrderServiceimplBusiness.deliveryOrder）目前仍然只按 status 筛人，
 * online 还没接进去 —— 那是 R9「上线/下线」一起做的事。所以现在 online 只是
 * 只读展示，4 个骑手都是建列时的默认值 0。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiderVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    // 登录账号
    private String username;

    // 骑手姓名
    private String name;

    // 手机号
    private String phone;

    // 账号状态：1 启用 / 0 停用。统一登录时校验的就是它
    private Integer status;

    // 接单状态 1 上线 / 0 下线
    private Integer online;

    private LocalDateTime createTime;
}
