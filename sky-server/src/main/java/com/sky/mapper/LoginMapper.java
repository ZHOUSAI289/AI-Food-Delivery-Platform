package com.sky.mapper;

import com.sky.dto.AccountInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 统一登录的账号查询 Mapper。
 *
 * 核心就一条 SQL：用 UNION ALL 一次把 employee / rider / user 三张表都查了，
 * role 直接由 SQL 里的字面量给出，省得 Java 里再判断"是哪张表查到的"。
 *
 * 为什么不用三次单表查询：三次查询就得在 Java 里按顺序判断"谁先查到算谁"，
 * 一旦账号在多端重名就会静默取到第一个，这是安全隐患。UNION 拿回全部命中行，
 * 让调用方（LoginServiceImpl）能显式发现重名并拒绝登录。
 *
 * 一次查询 = 一次数据库往返，顺带比三次查询更快。
 */
@Mapper
public interface LoginMapper {

    /**
     * 按账号名在三张表里查人。
     *
     * @param username 登录账号
     * @return 命中的账号列表；正常 0 或 1 条，≥2 条说明账号在多端重名
     */
    @Select("SELECT 'ADMIN' AS role, id, name, password, status FROM employee WHERE username = #{username} " +
            "UNION ALL " +
            "SELECT 'RIDER' AS role, id, name, password, status FROM rider WHERE username = #{username} " +
            "UNION ALL " +
            "SELECT 'USER' AS role, id, name, password, status FROM `user` WHERE username = #{username}")
    List<AccountInfo> listByUsername(@Param("username") String username);

}
