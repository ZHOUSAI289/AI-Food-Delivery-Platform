package com.sky.service;

import com.sky.dto.UserLoginDTO;
import com.sky.entity.User;
import com.sky.vo.UserInfoVO;

public interface UserService {


    /**
     * 获取当前用户信息
     * @return
     */
    UserInfoVO getUserInfo();
}
