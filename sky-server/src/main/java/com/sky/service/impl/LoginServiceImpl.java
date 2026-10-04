package com.sky.service.impl;

import com.sky.constant.JwtClaimsConstant;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.AccountInfo;
import com.sky.dto.LoginDTO;
import com.sky.entity.User;
import com.sky.exception.AccountLockedException;
import com.sky.exception.AccountNotFoundException;
import com.sky.exception.LoginFailedException;
import com.sky.exception.PasswordErrorException;
import com.sky.mapper.LoginMapper;
import com.sky.properties.JwtProperties;
import com.sky.service.LoginService;
import com.sky.utils.JwtUtil;
import com.sky.vo.LoginVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 统一登录实现。
 *
 * 流程：一条 UNION SQL 查三张表 -> 判断命中行数 -> 验密码 -> 验状态 -> 签发带 role 的 token。
 * 三端的差别只有「查出来在哪张表」，后面的校验逻辑完全共用，所以只需要这一个实现。
 */
@Service
@Slf4j
public class LoginServiceImpl implements LoginService {

    @Autowired
    private LoginMapper loginMapper;

    @Autowired
    private JwtProperties jwtProperties;

    @Override
    public LoginVO login(LoginDTO loginDTO) {

        String username = loginDTO.getUsername();
        String password = loginDTO.getPassword();

        // 账号密码都不给就直接拒，避免后面 NPE
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new LoginFailedException(MessageConstant.LOGIN_FAILED);
        }

        // 1、一条 SQL 把三张表都查了
        List<AccountInfo> accounts = loginMapper.listByUsername(username);

        // 2、一个人都没查到
        if (accounts == null || accounts.isEmpty()) {
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }

        // 3、查到多个人：账号在两端或多端重名。
        //    这里必须直接拒绝，绝对不能"取第一个"——否则骑手用管理员的账号名就能登进管理端。
        if (accounts.size() > 1) {
            List<String> roles = accounts.stream().map(AccountInfo::getRole).collect(Collectors.toList());
            log.warn("账号 {} 在多个端重复，拒绝登录：{}", username, roles);
            throw new LoginFailedException(MessageConstant.ACCOUNT_CONFLICT);
        }

        AccountInfo account = accounts.get(0);

        // 4、密码比对：库里的密码存的是 MD5 十六进制
        String md5Password = DigestUtils.md5DigestAsHex(password.getBytes());
        if (!md5Password.equals(account.getPassword())) {
            throw new PasswordErrorException(MessageConstant.PASSWORD_ERROR);
        }

        // 5、状态校验（骑手 0=未上线不能用，用户/员工 0=禁用）。
        //    用 equals 而不是 == ：status 是 Integer 包装类型，== 比的是引用。
        if (account.getStatus() == null || StatusConstant.DISABLE.equals(account.getStatus())) {
            throw new AccountLockedException(MessageConstant.ACCOUNT_LOCKED);
        }

        // 6、签发 JWT：三端共用一个密钥，身份靠 role 区分，id 的含义由 role 决定
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.ID, account.getId());
        claims.put(JwtClaimsConstant.ROLE, account.getRole());
        claims.put(JwtClaimsConstant.USERNAME, username);
        claims.put(JwtClaimsConstant.NAME, account.getName());
        String token = JwtUtil.createJWT(jwtProperties.getSecretKey(), jwtProperties.getTtl(), claims);

        log.info("{} 端登录成功：{}({})", account.getRole(), account.getName(), username);

        return LoginVO.builder()
                .id(account.getId())
                .username(username)
                .name(account.getName())
                .role(account.getRole())
                .token(token)
                .build();
    }

}
