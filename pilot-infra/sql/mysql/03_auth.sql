-- =============================================================
-- 增量迁移 03：补齐登录能力
-- 背景：01_schema.sql 的 sp_user 只设计了「有用户」，没设计「怎么登录」。
--       本项目要做 JWT 鉴权，必须存密码哈希。
--
-- 设计取舍：
--   * 存 BCrypt 哈希（$2a$10$...），不存明文、不存 MD5/SHA
--   * 列名 password_hash 而非 password，避免被误当成可直接读取的字段
--   * 幂等：用存储过程判断列是否存在，可重复执行
--
-- 种子账号（密码统一 123456）：
--   zhangsan / lisi / wangwu
-- =============================================================

USE shop_pilot;

DROP PROCEDURE IF EXISTS sp_add_password_column;
DELIMITER $$
CREATE PROCEDURE sp_add_password_column()
BEGIN
    IF NOT EXISTS(SELECT 1
                  FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = 'shop_pilot'
                    AND TABLE_NAME = 'sp_user'
                    AND COLUMN_NAME = 'password_hash') THEN
        ALTER TABLE sp_user
            ADD COLUMN password_hash VARCHAR(100) NOT NULL DEFAULT ''
                COMMENT 'BCrypt 密码哈希' AFTER username;
    END IF;
END$$
DELIMITER ;

CALL sp_add_password_column();
DROP PROCEDURE IF EXISTS sp_add_password_column;

-- 种子密码：123456 的 BCrypt 哈希（cost=10）
-- 注意：这是固定的公开哈希，仅用于本地开发账号；生产注册走 /api/auth/register 现场哈希
UPDATE sp_user
SET password_hash = '$2b$10$akYvm2rwMI/JC0Bhf07hJuZEnDOdAaABrRTc1Tk8al6pDajmLVu0y'
WHERE password_hash = '';
