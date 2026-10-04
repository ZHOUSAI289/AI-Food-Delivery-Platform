package com.sky.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class RiderDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String username;

    private String name;

    private String phone;

    /**
     * 新增 / 编辑骑手的入参。
     *
     * 这里【故意没有】password / online / createTime / updateTime：
     *   password   密码由服务端固定为默认密码，表单不让管理员填
     *   online     上线/下线是骑手自己的动作（R9 的 PUT /rider/status），不属于管理端表单
     *   时间字段   服务端决定，项目里 5 个管理端 DTO 都没有这两个
     *
     * 别顺手加回来：加了之后只要 Service 改回 BeanUtils.copyProperties，
     * 客户端就能建出一个"上线中"的骑手。
     */
}
