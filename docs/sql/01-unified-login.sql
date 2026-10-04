-- ============================================================================
-- 统一登录改造 · 数据库脚本
--
-- 目的：让 employee / rider / user 三张表都有「账号 + 密码」，从而支持
--      一个登录接口按 username 自动识别身份（商家 / 骑手 / 用户）。
--
-- 说明：
--   employee 表本来就有 username(UNIQUE) + password(MD5)，不动。
--   rider / user 表补账号字段，回填现有数据后加 NOT NULL + 唯一索引。
--   密码统一存 MD5 十六进制（和 EmployeeServiceImpl 里的
--   DigestUtils.md5DigestAsHex 结果一致），所以这里用 MD5() 生成。
--
-- 重复执行会报 "Duplicate column name"，属正常，说明已经改过了。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 骑手表：账号用手机号，初始密码 123456
--    先加成可空 -> 回填 -> 再改成 NOT NULL，避免非空列加不上去
-- ---------------------------------------------------------------------------
ALTER TABLE rider
    ADD COLUMN username VARCHAR(32) NULL AFTER id,
    ADD COLUMN password VARCHAR(64) NULL AFTER username;

UPDATE rider SET username = phone, password = MD5('123456');

ALTER TABLE rider
    MODIFY username VARCHAR(32) NOT NULL COMMENT '登录账号',
    MODIFY password VARCHAR(64) NOT NULL COMMENT '登录密码(MD5)',
    ADD UNIQUE KEY uk_rider_username (username);

-- ---------------------------------------------------------------------------
-- 2. 用户表：原来只有微信 openid，没有账号密码
--    openid 保留不动（小程序登录代码只是注释掉了，没删）
--    status 仿照 employee 表，0 禁用 1 启用
-- ---------------------------------------------------------------------------
ALTER TABLE user
    ADD COLUMN username VARCHAR(32) NULL AFTER id,
    ADD COLUMN password VARCHAR(64) NULL AFTER username,
    ADD COLUMN status INT NOT NULL DEFAULT 1 COMMENT '0禁用 1启用' AFTER avatar;

UPDATE user SET username = '13800000009', password = MD5('123456');

ALTER TABLE user
    MODIFY username VARCHAR(32) NOT NULL COMMENT '登录账号',
    MODIFY password VARCHAR(64) NOT NULL COMMENT '登录密码(MD5)',
    ADD UNIQUE KEY uk_user_username (username);
