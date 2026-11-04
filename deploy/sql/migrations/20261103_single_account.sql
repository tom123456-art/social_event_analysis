-- 要求数据库版本不低于 8.0.16；先停止旧后端并完成可验证的备份。
-- 保留系统账号的编号、密码和历史内容，使所有旧会话失效。
DELIMITER $$
DROP PROCEDURE IF EXISTS migrate_single_account$$
CREATE PROCEDURE migrate_single_account()
BEGIN
    DECLARE account_id BIGINT;
    DECLARE locked INT DEFAULT 0;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        DO RELEASE_LOCK('hotspot_single_account_migration');
        RESIGNAL;
    END;
    SELECT GET_LOCK('hotspot_single_account_migration', 10) INTO locked;
    IF locked <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Cannot obtain migration lock';
    END IF;
    SELECT id INTO account_id FROM app_user WHERE username = 'admin';
    IF account_id IS NULL OR NOT EXISTS (
        SELECT 1 FROM app_user_credential WHERE user_id = account_id
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'An existing admin account and credentials are required';
    END IF;

    START TRANSACTION;
    UPDATE ai_conversation SET user_id = account_id WHERE user_id <> account_id;
    -- 保留历史作者信息，不将旧评论归属改为系统账号。
    UPDATE user_interaction SET user_id = NULL WHERE user_id <> account_id;
    UPDATE user_submission SET user_id = NULL WHERE user_id <> account_id;
    DELETE FROM app_user_session;
    DELETE FROM app_user WHERE id <> account_id;
    COMMIT;

    -- 数据库结构变更会隐式提交，以下步骤支持中断后重复执行。
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_user' AND COLUMN_NAME = 'role') THEN
        ALTER TABLE app_user DROP COLUMN role;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_user' AND COLUMN_NAME = 'account_slot') THEN
        ALTER TABLE app_user
            ADD COLUMN account_slot TINYINT NOT NULL DEFAULT 1,
            ADD UNIQUE KEY uk_single_account (account_slot),
            ADD CONSTRAINT chk_single_account CHECK (account_slot = 1);
    END IF;
    DO RELEASE_LOCK('hotspot_single_account_migration');
END$$
CALL migrate_single_account()$$
DROP PROCEDURE migrate_single_account$$
DELIMITER ;
