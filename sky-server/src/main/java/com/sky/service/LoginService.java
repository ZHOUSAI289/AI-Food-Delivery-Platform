package com.sky.service;

import com.sky.dto.LoginDTO;
import com.sky.vo.LoginVO;

/**
 * 统一登录服务：三端（商家 / 骑手 / 用户）共用一个入口。
 */
public interface LoginService {

    /**
     * 登录：按账号自动识别身份，校验通过后签发带 role 的 JWT。
     *
     * @param loginDTO 账号密码
     * @return 登录结果（含 id、姓名、角色、token）
     */
    LoginVO login(LoginDTO loginDTO);

}
