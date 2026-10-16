-- Token 2026-10-14 完整优化。仅用于本项目已核验测试数据库。
-- 使用mysql批处理且禁止--force；遇首个错误停止。执行前必须加密备份并隔离恢复核验。

-- ===== security.sql =====
-- M1/M2：管理员安全状态及资料归一。仅适用于已备份并恢复验证的 token 测试库。
USE token;
SET NAMES utf8mb4;
SET time_zone = '+00:00';

DELIMITER $$
DROP PROCEDURE IF EXISTS token_security_merge_20261014$$
CREATE PROCEDURE token_security_merge_20261014()
BEGIN
  DECLARE bad BIGINT DEFAULT 0;
  IF BINARY DATABASE() <> BINARY 'token' OR
     (SELECT COUNT(*) FROM information_schema.schemata WHERE BINARY schema_name=BINARY 'token') <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='安全合表只允许 token 数据库';
  END IF;
  IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='ad_master' AND column_name='uid' AND data_type='int'
      AND is_nullable='NO' AND COALESCE(generation_expression,'')='') <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ad_master.uid 结构不符合实际审计基线';
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.key_column_usage WHERE table_schema=DATABASE()
      AND referenced_table_name IN ('ad_detail','account_totp','admin_login_attempt','admin_login_security_state')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='待移除表仍被外键引用';
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='ad_detail') THEN
    SELECT COUNT(*) INTO bad FROM ad_detail d LEFT JOIN ad_master a ON a.uid=d.uid WHERE a.uid IS NULL;
    IF bad<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ad_detail 存在孤立账号'; END IF;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='account_totp') THEN
    SELECT COUNT(*) INTO bad FROM account_totp t LEFT JOIN ad_master a ON a.uid=t.uid WHERE a.uid IS NULL;
    IF bad<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='account_totp 存在孤立账号'; END IF;
    SELECT COUNT(*) INTO bad FROM account_totp WHERE enabled=1 AND COALESCE(secret_ciphertext,'')='';
    IF bad<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='启用 TOTP 的账号缺少加密密钥'; END IF;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='admin_login_attempt') THEN
    SELECT COUNT(*) INTO bad FROM admin_login_attempt t LEFT JOIN ad_master a ON a.uid=t.admin_uid WHERE a.uid IS NULL;
    IF bad<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='登录失败状态存在孤立账号'; END IF;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='admin_login_security_state') THEN
    SELECT COUNT(*) INTO bad FROM admin_login_security_state t LEFT JOIN ad_master a ON a.uid=t.admin_uid WHERE a.uid IS NULL;
    IF bad<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='会话安全状态存在孤立账号'; END IF;
  END IF;

  CREATE TABLE IF NOT EXISTS admin_security (
    admin_uid INT NOT NULL COMMENT '管理员 UID，对应 ad_master.uid',
    totp_secret_ciphertext VARCHAR(500) NULL COMMENT 'AES-GCM 加密的 TOTP 密钥',
    totp_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否已完成 TOTP 绑定',
    totp_required TINYINT NOT NULL DEFAULT 0 COMMENT '是否强制 TOTP 验证',
    totp_last_used_counter BIGINT NULL COMMENT '已消费的 TOTP 时间步计数，防止验证码重放',
    totp_confirmed_at DATETIME(6) NULL COMMENT 'TOTP 绑定确认时间，UTC',
    failed_count INT NOT NULL DEFAULT 0 COMMENT '本轮连续认证失败次数',
    locked_until DATETIME(6) NULL COMMENT '临时登录锁定截止时间，UTC',
    invalid_before DATETIME(6) NULL COMMENT '会话及在途认证失效边界，UTC',
    login_attempt_updated_at DATETIME(6) NULL COMMENT '登录失败状态最近更新时间，UTC',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '安全记录最近更新时间，UTC',
    PRIMARY KEY (admin_uid),
    KEY idx_admin_security_totp (totp_required,totp_enabled)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理员统一安全状态';
  INSERT INTO admin_security(admin_uid) SELECT a.uid FROM ad_master a
    LEFT JOIN admin_security s ON s.admin_uid=a.uid WHERE s.admin_uid IS NULL;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='account_totp') THEN
    UPDATE admin_security s JOIN account_totp t ON t.uid=s.admin_uid
       SET s.totp_secret_ciphertext=t.secret_ciphertext,s.totp_enabled=t.enabled,s.totp_required=t.required,
           s.totp_last_used_counter=t.last_used_counter,s.totp_confirmed_at=t.confirmed_at;
    SELECT COUNT(*) INTO bad FROM account_totp t JOIN admin_security s ON s.admin_uid=t.uid
      WHERE NOT(BINARY s.totp_secret_ciphertext <=> BINARY t.secret_ciphertext) OR NOT(s.totp_enabled <=> t.enabled)
         OR NOT(s.totp_required <=> t.required) OR NOT(s.totp_last_used_counter <=> t.last_used_counter)
         OR NOT(s.totp_confirmed_at <=> t.confirmed_at);
    IF bad<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TOTP 迁移核验失败'; END IF;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='admin_login_attempt') THEN
    UPDATE admin_security s JOIN admin_login_attempt t ON t.admin_uid=s.admin_uid
       SET s.failed_count=t.failed_count,s.locked_until=t.locked_until,s.login_attempt_updated_at=t.updated_at;
    SELECT COUNT(*) INTO bad FROM admin_login_attempt t JOIN admin_security s ON s.admin_uid=t.admin_uid
      WHERE NOT(s.failed_count <=> t.failed_count) OR NOT(s.locked_until <=> t.locked_until)
         OR NOT(s.login_attempt_updated_at <=> t.updated_at);
    IF bad<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='登录锁定状态迁移核验失败'; END IF;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='admin_login_security_state') THEN
    UPDATE admin_security s JOIN admin_login_security_state t ON t.admin_uid=s.admin_uid SET s.invalid_before=t.invalid_before;
    SELECT COUNT(*) INTO bad FROM admin_login_security_state t JOIN admin_security s ON s.admin_uid=t.admin_uid
      WHERE NOT(s.invalid_before <=> t.invalid_before);
    IF bad<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='会话失效边界迁移核验失败'; END IF;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ad_master' AND column_name='phone') THEN
    ALTER TABLE ad_master ADD COLUMN phone VARCHAR(50) NULL COMMENT '管理员联系电话';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ad_master' AND column_name='contact_email') THEN
    ALTER TABLE ad_master ADD COLUMN contact_email VARCHAR(100) NULL COMMENT '管理员联系邮箱';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ad_master' AND column_name='remark') THEN
    ALTER TABLE ad_master ADD COLUMN remark VARCHAR(255) NULL COMMENT '管理员备注';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ad_master' AND column_name='country') THEN
    ALTER TABLE ad_master ADD COLUMN country VARCHAR(100) NULL COMMENT '管理员保存的国家或地区摘要';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ad_master' AND column_name='login_count') THEN
    ALTER TABLE ad_master ADD COLUMN login_count INT NULL COMMENT '管理员累计登录次数';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ad_master' AND column_name='last_login_ip') THEN
    ALTER TABLE ad_master ADD COLUMN last_login_ip VARCHAR(100) NULL COMMENT '管理员最近登录 IP';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ad_master' AND column_name='last_login_client') THEN
    ALTER TABLE ad_master ADD COLUMN last_login_client VARCHAR(100) NULL COMMENT '管理员最近登录客户端';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ad_master' AND column_name='profile_updated_at') THEN
    ALTER TABLE ad_master ADD COLUMN profile_updated_at DATETIME(6) NULL COMMENT '资料及登录摘要更新时间，UTC，非登录发生时间';
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='ad_detail') THEN
    UPDATE ad_master a JOIN ad_detail d ON d.uid=a.uid
       SET a.phone=d.tel,a.contact_email=d.email,a.remark=d.comment,a.country=d.country,
           a.login_count=d.login_count,a.last_login_ip=d.login_ip,a.last_login_client=d.clientos,a.profile_updated_at=d.updatetime;
    SELECT COUNT(*) INTO bad FROM ad_master a JOIN ad_detail d ON d.uid=a.uid
     WHERE NOT(BINARY a.phone <=> BINARY d.tel) OR NOT(BINARY a.contact_email <=> BINARY d.email) OR NOT(BINARY a.remark <=> BINARY d.comment)
        OR NOT(BINARY a.country <=> BINARY d.country) OR NOT(a.login_count <=> d.login_count)
        OR NOT(BINARY a.last_login_ip <=> BINARY d.login_ip) OR NOT(BINARY a.last_login_client <=> BINARY d.clientos)
        OR NOT(a.profile_updated_at <=> d.updatetime);
    IF bad<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='管理员资料迁移核验失败'; END IF;
  END IF;

  -- 固定移除已无运行时消费者的旧明文 TOTP/层级/额度字段，permission 由 M7 单独处理。
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ad_master' AND column_name='secret_key') THEN
    ALTER TABLE ad_master DROP COLUMN secret_key;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ad_master' AND column_name='action') THEN
    ALTER TABLE ad_master DROP COLUMN action;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ad_master' AND column_name='level') THEN
    ALTER TABLE ad_master DROP COLUMN level;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ad_master' AND column_name='amount') THEN
    ALTER TABLE ad_master DROP COLUMN amount;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ad_master' AND column_name='edit') THEN
    ALTER TABLE ad_master DROP COLUMN edit;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ad_master' AND column_name='email') THEN
    ALTER TABLE ad_master DROP COLUMN email;
  END IF;
  DROP TABLE IF EXISTS ad_detail,account_totp,admin_login_attempt,admin_login_security_state;
END$$
CALL token_security_merge_20261014()$$
DROP PROCEDURE token_security_merge_20261014$$
DELIMITER ;

-- ===== scope_content.sql =====
-- M3/M6/M7：目标库 token 已完成备份/恢复验证后整段执行。
-- 当前 UI 的空绑定表示主动授权为全局；有标记却没有根节点则禁止访问。
USE token;
SET NAMES utf8mb4;
DELIMITER $$
DROP PROCEDURE IF EXISTS token_scope_content_cleanup$$
CREATE PROCEDURE token_scope_content_cleanup()
BEGIN
  IF DATABASE() <> 'token' OR @@lower_case_table_names IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='必须在已核实的token数据库执行';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ad_master' AND column_name='scope_configured_at') THEN
    ALTER TABLE ad_master ADD scope_configured_at DATETIME(6) NULL COMMENT '会员范围配置时间；空为全局，非空无根节点为禁止访问';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='compute_admin_scope_state') THEN
    UPDATE ad_master a JOIN compute_admin_scope_state s ON s.admin_uid=a.uid
      SET a.scope_configured_at=s.updated_at
      WHERE EXISTS(SELECT 1 FROM compute_admin_member_scope r WHERE r.admin_uid=a.uid);
  END IF;
  UPDATE ad_master a SET a.scope_configured_at=COALESCE(a.scope_configured_at,UTC_TIMESTAMP(6))
    WHERE EXISTS(SELECT 1 FROM compute_admin_member_scope r WHERE r.admin_uid=a.uid);
  IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='compute_admin_agent') THEN
    -- 无法映射的旧限制账号继续拒绝，不能因为删旧表变成全局。
    UPDATE ad_master a JOIN compute_admin_agent b ON b.admin_uid=a.uid
      SET a.scope_configured_at=COALESCE(a.scope_configured_at,UTC_TIMESTAMP(6));
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_content' AND column_name='owner_agent_id') THEN
    IF EXISTS(SELECT 1 FROM compute_content WHERE owner_agent_id IS NOT NULL OR (target_type='AGENT' AND target_member_id IS NULL)) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='须先按白名单清理尚依赖旧代理归属的内容及子记录';
    END IF;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_member_group' AND column_name='agent_id') THEN
    IF EXISTS(SELECT 1 FROM compute_member_group WHERE agent_id IS NOT NULL AND owner_member_id IS NULL) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='须先处理仅有旧代理归属的会员分组';
    END IF;
  END IF;
  -- 固定已核实外键名称，先移除子表引用，再删除父表；不禁用外键检查。
  IF EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='compute_member' AND constraint_name='compute_member_ibfk_1' AND constraint_type='FOREIGN KEY') THEN
    ALTER TABLE compute_member DROP FOREIGN KEY compute_member_ibfk_1;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='compute_member_group' AND constraint_name='compute_member_group_ibfk_1' AND constraint_type='FOREIGN KEY') THEN
    ALTER TABLE compute_member_group DROP FOREIGN KEY compute_member_group_ibfk_1;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_member' AND column_name='agent_id') THEN
    ALTER TABLE compute_member DROP COLUMN agent_id;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_member_group' AND column_name='agent_id') THEN
    ALTER TABLE compute_member_group DROP COLUMN agent_id;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_content' AND column_name='agent_id') THEN
    ALTER TABLE compute_content DROP COLUMN agent_id;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_content' AND column_name='owner_agent_id') THEN
    ALTER TABLE compute_content DROP COLUMN owner_agent_id;
  END IF;
  DROP TABLE IF EXISTS compute_admin_scope_state;
  DROP TABLE IF EXISTS compute_admin_agent;
  DROP TABLE IF EXISTS compute_agent_closure;
  DROP TABLE IF EXISTS compute_agent_line;
  -- 旧通知读写入口已移除；本项目不迁移旧数据或旧链接。
  DROP TABLE IF EXISTS compute_member_notice;
END$$
CALL token_scope_content_cleanup()$$
DROP PROCEDURE token_scope_content_cleanup$$
DELIMITER ;

-- ===== settings.sql =====
-- M4/M5：统一逐键配置；领域配置保留原业务生效时间和版本，不进入展示缓存。
USE token;
SET NAMES utf8mb4;
SET time_zone='+00:00';
DELIMITER $$
DROP PROCEDURE IF EXISTS token_merge_settings_20261014$$
CREATE PROCEDURE token_merge_settings_20261014()
BEGIN
  IF BINARY DATABASE()<>BINARY 'token' OR
     (SELECT COUNT(*) FROM information_schema.schemata WHERE BINARY schema_name=BINARY 'token')<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='配置合并仅允许token库';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='system_setting' AND column_name='revision') THEN
    ALTER TABLE system_setting ADD revision BIGINT NOT NULL DEFAULT 0 COMMENT '配置项并发版本，每次成功修改递增';
  END IF;
  ALTER TABLE system_setting MODIFY setting_value LONGTEXT NULL COMMENT '配置原始值；领域配置使用包含业务生效时间的JSON文档',
    MODIFY updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '配置修改时间，UTC，不代替业务启用边界';
  IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='compute_setting') THEN
    IF EXISTS(SELECT 1 FROM compute_setting c JOIN system_setting s ON BINARY c.setting_key=BINARY s.setting_key
      WHERE s.group_name<>'compute' OR NOT(BINARY c.setting_value <=> BINARY s.setting_value)) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='业务设置与基础设置存在冲突，禁止覆盖';
    END IF;
    INSERT INTO system_setting(setting_key,setting_value,value_type,group_name,is_public,is_editable,revision,updated_at,description)
      SELECT c.setting_key,c.setting_value,
        CASE WHEN JSON_VALID(c.setting_value) THEN CASE WHEN JSON_TYPE(c.setting_value) IN ('OBJECT','ARRAY') THEN 'json' ELSE 'string' END ELSE 'string' END,
        'compute',0,0,0,c.updated_at,'业务配置，仅由对应权限和领域校验入口维护'
      FROM compute_setting c LEFT JOIN system_setting s ON BINARY s.setting_key=BINARY c.setting_key WHERE s.setting_key IS NULL;
    IF EXISTS(SELECT 1 FROM compute_setting c LEFT JOIN system_setting s ON BINARY c.setting_key=BINARY s.setting_key
      WHERE s.setting_key IS NULL OR NOT(BINARY c.setting_value <=> BINARY s.setting_value)) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='业务配置逐键迁移核验失败';
    END IF;
    DROP TABLE compute_setting;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='compute_reward_configuration') THEN
    IF EXISTS(SELECT 1 FROM system_setting WHERE setting_key='compute.reward.configuration') THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='奖励目标配置键已存在，请核验后继续，禁止覆盖';
    END IF;
    INSERT INTO system_setting(setting_key,setting_value,value_type,group_name,is_public,is_editable,revision,updated_by,updated_at,description)
      SELECT 'compute.reward.configuration',JSON_OBJECT('configuration',configuration_json,'activatedAt',DATE_FORMAT(activated_at,'%Y-%m-%dT%H:%i:%s.%fZ')),
        'json','reward',0,0,revision,updated_by,updated_at,'奖励规则及原业务启用时间，仅奖励管理权限可修改'
      FROM compute_reward_configuration WHERE singleton_id=1;
    IF (SELECT COUNT(*) FROM system_setting WHERE setting_key='compute.reward.configuration')<>1 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='奖励配置迁移核验失败';
    END IF;
    DROP TABLE compute_reward_configuration;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='notification_automation_config') THEN
    IF EXISTS(SELECT 1 FROM system_setting WHERE setting_key='notification.automation.configuration') THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='自动通知目标配置键已存在，禁止覆盖';
    END IF;
    INSERT INTO system_setting(setting_key,setting_value,value_type,group_name,is_public,is_editable,revision,updated_by,updated_at,description)
      SELECT 'notification.automation.configuration',JSON_OBJECT('configuration',config_json,'collectionStartedAt',DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%fZ')),
        'json','notification',0,0,revision,updated_by,updated_at,'自动通知规则及原采集起点，仅专用权限入口可修改'
      FROM notification_automation_config WHERE id=1;
    IF (SELECT COUNT(*) FROM system_setting WHERE setting_key='notification.automation.configuration')<>1 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='通知配置迁移核验失败';
    END IF;
    DROP TABLE notification_automation_config;
  END IF;
END$$
CALL token_merge_settings_20261014()$$
DROP PROCEDURE token_merge_settings_20261014$$
DELIMITER ;

-- ===== mcag.sql =====
-- 最新逐笔充值协议收敛；仅删除已核实为空的两张旧协议表。
USE token;
SET NAMES utf8mb4;
SET time_zone = '+00:00';
DELIMITER $$
DROP PROCEDURE IF EXISTS token_mcag_current_20261014$$
CREATE PROCEDURE token_mcag_current_20261014()
BEGIN
  DECLARE bad BIGINT DEFAULT 0;
  IF BINARY DATABASE() <> BINARY 'token' OR
     (SELECT COUNT(*) FROM information_schema.schemata WHERE BINARY schema_name=BINARY 'token') <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MCAG 清理只允许 token 数据库';
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.key_column_usage WHERE table_schema=DATABASE()
      AND referenced_table_name IN ('compute_mcag_deposit','compute_mcag_collection_receipt')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='旧协议表仍有外键引用';
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='compute_mcag_deposit') THEN
    SELECT COUNT(*) INTO bad FROM compute_mcag_deposit;
    IF bad<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='旧充值表非空，不能移除永久入账凭据'; END IF;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='compute_mcag_collection_receipt') THEN
    SELECT COUNT(*) INTO bad FROM compute_mcag_collection_receipt;
    IF bad<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='旧归集收款表非空，不能移除永久入账凭据'; END IF;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_mcag_deposit_order' AND column_name='credit_origin') THEN
    SELECT COUNT(*) INTO bad FROM compute_mcag_deposit_order WHERE BINARY credit_origin <> BINARY 'ORDER' OR credit_origin IS NULL;
    IF bad<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='逐笔订单仍关联旧协议入账'; END IF;
  END IF;
  SELECT COUNT(*) INTO bad FROM compute_mcag_collection_batch WHERE BINARY accounting_model <> BINARY 'RECEIPT_SWEEP' OR accounting_model IS NULL;
  IF bad<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='归集批次仍有旧会计协议'; END IF;
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_mcag_collection_batch' AND column_name='record_id') THEN
    SELECT COUNT(*) INTO bad FROM compute_mcag_collection_batch WHERE record_id IS NOT NULL;
    IF bad<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='归集批次仍关联本地充值展示'; END IF;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_mcag_collection_batch' AND column_name='local_deposit_id') THEN
    SELECT COUNT(*) INTO bad FROM compute_mcag_collection_batch WHERE local_deposit_id IS NOT NULL;
    IF bad<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='归集批次仍关联历史入账'; END IF;
  END IF;

  -- 关闭从客户标识拆分猜测会员的旧回退前，从真实地址归属补齐永久身份。
  SELECT COUNT(*) INTO bad FROM (
    SELECT merchant_id,environment,customer_id FROM compute_mcag_address
     GROUP BY merchant_id,environment,customer_id HAVING COUNT(DISTINCT member_id,wallet_type)<>1
  ) conflicting_identity;
  IF bad<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='已有地址的客户身份归属冲突'; END IF;
  SELECT COUNT(*) INTO bad FROM compute_mcag_address a JOIN compute_mcag_customer_identity i
    ON BINARY i.merchant_id=BINARY a.merchant_id AND BINARY i.environment=BINARY a.environment AND BINARY i.customer_id=BINARY a.customer_id
   WHERE i.member_id<>a.member_id OR BINARY i.wallet_type<>BINARY a.wallet_type;
  IF bad<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='地址与已登记客户身份冲突'; END IF;
  INSERT INTO compute_mcag_customer_identity(merchant_id,environment,customer_id,member_id,wallet_type,created_at)
    SELECT a.merchant_id,a.environment,a.customer_id,MIN(a.member_id),MIN(a.wallet_type),MIN(a.created_at)
      FROM compute_mcag_address a LEFT JOIN compute_mcag_customer_identity i
        ON BINARY i.merchant_id=BINARY a.merchant_id AND BINARY i.environment=BINARY a.environment AND BINARY i.customer_id=BINARY a.customer_id
     WHERE i.customer_id IS NULL GROUP BY a.merchant_id,a.environment,a.customer_id;

  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_mcag_deposit_order' AND column_name='credit_origin') THEN
    ALTER TABLE compute_mcag_deposit_order DROP COLUMN credit_origin;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_mcag_collection_batch' AND column_name='record_id') THEN
    ALTER TABLE compute_mcag_collection_batch DROP COLUMN record_id;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_mcag_collection_batch' AND column_name='local_deposit_id') THEN
    ALTER TABLE compute_mcag_collection_batch DROP COLUMN local_deposit_id;
  END IF;
  ALTER TABLE compute_mcag_collection_batch ALTER COLUMN accounting_model SET DEFAULT 'RECEIPT_SWEEP';
  DROP TABLE IF EXISTS compute_mcag_deposit,compute_mcag_collection_receipt;
END$$
CALL token_mcag_current_20261014()$$
DROP PROCEDURE token_mcag_current_20261014$$
DELIMITER ;

-- ===== business.sql =====
-- 当前GPU、VIP点击模型收敛；旧版本测试记录按固定条件清理。
USE token;
SET NAMES utf8mb4;
SET time_zone='+00:00';
DELIMITER $$
DROP PROCEDURE IF EXISTS token_business_cleanup_20261014$$
CREATE PROCEDURE token_business_cleanup_20261014()
BEGIN
IF BINARY DATABASE()<>BINARY 'token' OR (SELECT COUNT(*) FROM information_schema.schemata WHERE BINARY schema_name=BINARY 'token')<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='业务清理只允许token库'; END IF;
DELETE r FROM compute_request r JOIN compute_order o ON o.member_id=r.member_id AND JSON_VALID(r.result_json) AND JSON_UNQUOTE(JSON_EXTRACT(r.result_json,'$.id'))=CAST(o.id AS CHAR) WHERE r.request_type='ORDER' AND o.product_type<>'GPU';
DELETE s FROM compute_order_settlement s JOIN compute_order o ON o.id=s.order_id WHERE o.product_type<>'GPU';
DELETE FROM compute_order WHERE product_type<>'GPU';
DELETE FROM compute_product WHERE type<>'GPU';
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_auto_contract' AND column_name='engine_version') THEN
DELETE s FROM compute_auto_settlement s JOIN compute_auto_contract c ON c.id=s.contract_id WHERE c.engine_version<>3;
DELETE FROM compute_auto_contract WHERE engine_version<>3; END IF;
DELETE FROM compute_job_run WHERE job_code='SETTLE_AUTO';
DELETE FROM compute_job WHERE job_code='SETTLE_AUTO';
IF EXISTS(SELECT 1 FROM compute_product WHERE daily_rate_min IS NULL OR daily_rate_max IS NULL OR daily_rate_min<0 OR daily_rate_max<daily_rate_min) OR EXISTS(SELECT 1 FROM compute_order WHERE daily_rate IS NULL OR daily_rate<0) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='当前GPU日收益率缺失或不合法'; END IF;
ALTER TABLE compute_product MODIFY daily_rate_min DECIMAL(14,8) NOT NULL COMMENT '最低日收益率百分比', MODIFY daily_rate_max DECIMAL(14,8) NOT NULL COMMENT '最高日收益率百分比';
ALTER TABLE compute_order MODIFY daily_rate DECIMAL(14,8) NOT NULL COMMENT '成交时固定日收益率百分比';
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_level' AND column_name='auto_model_ids') THEN
UPDATE compute_level l LEFT JOIN (SELECT level_id,JSON_ARRAYAGG(provider_id) ids FROM (SELECT DISTINCT x.id level_id,m.provider_id FROM compute_level x JOIN compute_model m ON JSON_CONTAINS(COALESCE(x.auto_model_ids,JSON_ARRAY()),CAST(m.id AS CHAR)) WHERE m.provider_id IS NOT NULL) bindings GROUP BY level_id) p ON p.level_id=l.id SET l.auto_provider_ids=COALESCE(p.ids,JSON_ARRAY()) WHERE l.auto_provider_ids IS NULL; END IF;
UPDATE compute_level SET auto_provider_ids=JSON_ARRAY() WHERE auto_provider_ids IS NULL;
ALTER TABLE compute_level MODIFY auto_provider_ids JSON NOT NULL COMMENT '本等级绑定的供应商ID数组，空数组表示不绑定';
ALTER TABLE compute_auto_contract MODIFY level_id INT NOT NULL COMMENT '执行时VIP等级ID，对应compute_level.id';
DELETE FROM system_setting WHERE group_name='compute' AND setting_key IN ('subscriptionEnabled','vipAutoUpgradeEnabled');
IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='compute_subscription_quote') THEN
IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='compute_product_quote') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='报价新旧表同时存在，停止核对'; END IF;
RENAME TABLE `compute_subscription_quote` TO `compute_product_quote`;
DELETE FROM compute_product_quote; -- 仅首次改名时丢弃旧短期报价，重复执行保留当前报价。
END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_order' AND column_name='subscription_quote_json') THEN ALTER TABLE `compute_order` CHANGE COLUMN `subscription_quote_json` `product_quote_json` json NULL COMMENT '购买时固定报价快照，不按当前产品配置重算'; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_order' AND column_name='purchase_amount_credits') THEN ALTER TABLE `compute_order` CHANGE COLUMN `purchase_amount_credits` `purchase_amount_token` decimal(38,8) NULL COMMENT '成交时固定Token计价金额，用于个人累计限购'; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_product' AND column_name='annual_rate_min') THEN ALTER TABLE `compute_product` DROP COLUMN `annual_rate_min`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_product' AND column_name='annual_rate_max') THEN ALTER TABLE `compute_product` DROP COLUMN `annual_rate_max`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_product' AND column_name='min_level') THEN ALTER TABLE `compute_product` DROP COLUMN `min_level`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_product' AND column_name='provider_id') THEN ALTER TABLE `compute_product` DROP COLUMN `provider_id`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_product' AND column_name='model_ids') THEN ALTER TABLE `compute_product` DROP COLUMN `model_ids`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_product' AND column_name='subscription_rank') THEN ALTER TABLE `compute_product` DROP COLUMN `subscription_rank`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_product' AND column_name='subscription_price') THEN ALTER TABLE `compute_product` DROP COLUMN `subscription_price`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_order' AND column_name='annual_rate') THEN ALTER TABLE `compute_order` DROP COLUMN `annual_rate`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_order' AND column_name='rate_basis') THEN ALTER TABLE `compute_order` DROP COLUMN `rate_basis`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_order' AND column_name='model_ids') THEN ALTER TABLE `compute_order` DROP COLUMN `model_ids`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_order' AND column_name='subscription_rank') THEN ALTER TABLE `compute_order` DROP COLUMN `subscription_rank`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_order' AND column_name='subscription_paid_amount') THEN ALTER TABLE `compute_order` DROP COLUMN `subscription_paid_amount`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_order' AND column_name='subscription_credit_amount') THEN ALTER TABLE `compute_order` DROP COLUMN `subscription_credit_amount`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_order' AND column_name='subscription_previous_order_id') THEN ALTER TABLE `compute_order` DROP COLUMN `subscription_previous_order_id`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_order' AND column_name='subscription_ended_at') THEN ALTER TABLE `compute_order` DROP COLUMN `subscription_ended_at`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_level' AND column_name='auto_min_amount') THEN ALTER TABLE `compute_level` DROP COLUMN `auto_min_amount`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_level' AND column_name='auto_max_amount') THEN ALTER TABLE `compute_level` DROP COLUMN `auto_max_amount`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_level' AND column_name='auto_daily_rate_min') THEN ALTER TABLE `compute_level` DROP COLUMN `auto_daily_rate_min`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_level' AND column_name='auto_daily_rate_max') THEN ALTER TABLE `compute_level` DROP COLUMN `auto_daily_rate_max`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_level' AND column_name='auto_duration_days') THEN ALTER TABLE `compute_level` DROP COLUMN `auto_duration_days`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_level' AND column_name='auto_max_active') THEN ALTER TABLE `compute_level` DROP COLUMN `auto_max_active`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_level' AND column_name='auto_settlement_mode') THEN ALTER TABLE `compute_level` DROP COLUMN `auto_settlement_mode`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_level' AND column_name='auto_income_mode') THEN ALTER TABLE `compute_level` DROP COLUMN `auto_income_mode`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_level' AND column_name='auto_execution_mode') THEN ALTER TABLE `compute_level` DROP COLUMN `auto_execution_mode`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_level' AND column_name='auto_delay_minutes') THEN ALTER TABLE `compute_level` DROP COLUMN `auto_delay_minutes`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_level' AND column_name='auto_model_ids') THEN ALTER TABLE `compute_level` DROP COLUMN `auto_model_ids`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_auto_contract' AND column_name='duration_days') THEN ALTER TABLE `compute_auto_contract` DROP COLUMN `duration_days`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_auto_contract' AND column_name='settlement_mode') THEN ALTER TABLE `compute_auto_contract` DROP COLUMN `settlement_mode`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_auto_contract' AND column_name='settled_days') THEN ALTER TABLE `compute_auto_contract` DROP COLUMN `settled_days`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_auto_contract' AND column_name='matures_at') THEN ALTER TABLE `compute_auto_contract` DROP COLUMN `matures_at`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_auto_contract' AND column_name='engine_version') THEN ALTER TABLE `compute_auto_contract` DROP COLUMN `engine_version`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_auto_contract' AND column_name='income_mode') THEN ALTER TABLE `compute_auto_contract` DROP COLUMN `income_mode`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_auto_contract' AND column_name='execution_mode') THEN ALTER TABLE `compute_auto_contract` DROP COLUMN `execution_mode`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='compute_model_usage') THEN
IF EXISTS(SELECT 1 FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND referenced_table_name='compute_model_usage') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='旧业务表仍存在外键依赖'; END IF;
DROP TABLE `compute_model_usage`; END IF;
IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='compute_team_reward') THEN
IF EXISTS(SELECT 1 FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND referenced_table_name='compute_team_reward') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='旧业务表仍存在外键依赖'; END IF;
DROP TABLE `compute_team_reward`; END IF;
END$$
CALL token_business_cleanup_20261014()$$
DROP PROCEDURE token_business_cleanup_20261014$$
DELIMITER ;

-- ===== auxiliary-cleanup.sql =====
-- 合并进主任务迁移；仅针对已核验 token 库四张表。执行端必须遇首个错误停止。
USE token;
SET NAMES utf8mb4;

DELIMITER $$
DROP PROCEDURE IF EXISTS token_auxiliary_cleanup_20261014$$
CREATE PROCEDURE token_auxiliary_cleanup_20261014()
BEGIN
  DECLARE index_columns TEXT;
  IF DATABASE() <> 'token' OR (SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='token') <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='数据库身份必须是 token';
  END IF;
  IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('system_notification','compute_activity_prize','compute_model','compute_fee_rule')) <> 4 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='辅助清理所需业务表缺失';
  END IF;

  -- 通知表仅持久化站内消息；实时弹窗继续通过 SSE 推送。
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='system_notification' AND column_name='display_type') THEN
    IF EXISTS(SELECT 1 FROM system_notification WHERE display_type NOT IN ('MESSAGE','POPUP')) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='发现未知持久通知类型';
    END IF;
    DELETE FROM system_notification WHERE display_type='POPUP';
    IF EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='system_notification' AND index_name='idx_notification_recipient_display_read') THEN
      SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) INTO index_columns FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name='system_notification' AND index_name='idx_notification_recipient_display_read';
      IF index_columns <> 'recipient_uid,display_type,read_status,id' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='通知旧索引结构与已核验结构不同';
      END IF;
      ALTER TABLE system_notification DROP INDEX idx_notification_recipient_display_read;
    END IF;
    ALTER TABLE system_notification DROP COLUMN display_type;
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='system_notification' AND index_name='idx_notification_recipient_hidden_read') THEN
    ALTER TABLE system_notification ADD INDEX idx_notification_recipient_hidden_read(recipient_uid,hidden_status,read_status,id);
  ELSE
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) INTO index_columns FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='system_notification' AND index_name='idx_notification_recipient_hidden_read';
    IF index_columns <> 'recipient_uid,hidden_status,read_status,id' THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='通知新索引结构不匹配';
    END IF;
  END IF;

  -- 转盘使用唯一概率字段，宝箱配置 JSON 的 chestRanges[].weight 不受影响。
  IF EXISTS(SELECT 1 FROM compute_activity_prize WHERE probability IS NULL OR probability < 0 OR probability > 1000000) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='奖项 probability 必须已核验为非空且在0至1000000范围内';
  END IF;
  ALTER TABLE compute_activity_prize MODIFY COLUMN probability DECIMAL(13,6) NOT NULL DEFAULT 0 COMMENT '相对概率权重，0不参与抽取';
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_activity_prize' AND column_name='weight') THEN
    ALTER TABLE compute_activity_prize DROP COLUMN weight;
  END IF;
  -- 目标实例 MySQL 8.0.12 无已执行 CHECK；范围由保存校验与上述数据守卫保障。

  -- 供应商由 provider_id 唯一关联，保留同一供应商下模型名称唯一。
  IF EXISTS(SELECT 1 FROM compute_model m LEFT JOIN compute_provider p ON p.id=m.provider_id WHERE p.id IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='存在缺失供应商关联的模型';
  END IF;
  IF EXISTS(SELECT 1 FROM compute_model GROUP BY provider_id,name HAVING COUNT(*)>1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='同一供应商下模型名称重复';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='compute_model' AND index_name='uk_model_provider_name') THEN
    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) INTO index_columns FROM information_schema.statistics
      WHERE table_schema=DATABASE() AND table_name='compute_model' AND index_name='uk_model_provider_name';
    IF index_columns NOT IN ('provider,name','provider_id,name') THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='模型唯一索引结构与已核验结构不同';
    END IF;
    IF index_columns='provider,name' THEN
      ALTER TABLE compute_model DROP INDEX uk_model_provider_name;
    END IF;
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='compute_model' AND index_name='uk_model_provider_name') THEN
    ALTER TABLE compute_model ADD UNIQUE KEY uk_model_provider_name(provider_id,name);
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_model' AND column_name='provider') THEN
    ALTER TABLE compute_model DROP COLUMN provider;
  END IF;

  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_fee_rule' AND column_name='low_amount_threshold') THEN
    ALTER TABLE compute_fee_rule DROP COLUMN low_amount_threshold;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_fee_rule' AND column_name='low_amount_fee') THEN
    ALTER TABLE compute_fee_rule DROP COLUMN low_amount_fee;
  END IF;
END$$
CALL token_auxiliary_cleanup_20261014()$$
DROP PROCEDURE token_auxiliary_cleanup_20261014$$
DELIMITER ;

-- ===== constraints.sql =====
-- 当前结构约束与统一语言标签；固定对象、遇错停止。
USE token;
SET NAMES utf8mb4;
DELIMITER $$
DROP PROCEDURE IF EXISTS token_constraints_20261014$$
CREATE PROCEDURE token_constraints_20261014()
BEGIN
IF BINARY DATABASE()<>BINARY 'token' OR (SELECT COUNT(*) FROM information_schema.schemata WHERE BINARY schema_name=BINARY 'token')<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='约束优化只允许token库'; END IF;
IF EXISTS(SELECT 1 FROM ad_master WHERE username IS NULL OR TRIM(username)='') OR EXISTS(SELECT 1 FROM ad_master GROUP BY username HAVING COUNT(*)>1) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='管理员用户名存在空值或重复'; END IF;
ALTER TABLE ad_master MODIFY username VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '管理员登录用户名，大小写不敏感且唯一';
IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='ad_master' AND index_name='uk_admin_username') THEN ALTER TABLE ad_master ADD UNIQUE KEY uk_admin_username(username); END IF;
IF EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='ad_master' AND index_name='idx_uid_ad_master') THEN ALTER TABLE ad_master DROP INDEX idx_uid_ad_master; END IF;
IF EXISTS(SELECT 1 FROM translates WHERE lang IS NULL OR type IS NULL OR TRIM(lang)='') OR EXISTS(SELECT 1 FROM translates GROUP BY tkey,lang,type HAVING COUNT(*)>1) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='翻译唯一键存在空值或重复'; END IF;
ALTER TABLE translates MODIFY lang VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '完整语言标签或内置模板标识', MODIFY type INT NOT NULL COMMENT '语言模板类型：1服务端、5后台、7用户端及内置模板';
ALTER TABLE translate_language MODIFY lang VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '完整语言标签';
ALTER TABLE compute_content MODIFY source_language VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'zh' COMMENT '原文语言标签';
ALTER TABLE compute_content_translation_item MODIFY language VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '目标语言标签';
ALTER TABLE compute_level MODIFY source_language VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'zh' COMMENT '原文语言标签';
ALTER TABLE compute_member MODIFY user_language VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '用户最近主动选择的完整语言标签，空值表示尚未选择';
ALTER TABLE compute_product MODIFY source_language VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'zh' COMMENT '原文语言标签';
ALTER TABLE compute_translation_form MODIFY source_language VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '源文启用语言标签';
ALTER TABLE ad_master MODIFY locale VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '管理员语言标签';
ALTER TABLE compute_model MODIFY provider_id BIGINT NOT NULL COMMENT '所属供应商ID，对应compute_provider.id';
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='compute_checkin' AND column_name='reward_credits') THEN ALTER TABLE compute_checkin CHANGE reward_credits reward_token DECIMAL(28,8) NOT NULL COMMENT '日奖励与连续奖励合计，单位Token'; END IF;
END$$
CALL token_constraints_20261014()$$
DROP PROCEDURE token_constraints_20261014$$
DELIMITER ;
DROP PROCEDURE IF EXISTS exp_ensure_notification_translation_templates;

-- ===== permissions.sql =====
-- 将已核实的固定旧码映射一次性写入当前叶子，运行时不再授权旧码或父节点。
USE token;
SET NAMES utf8mb4;
DELIMITER $$
DROP PROCEDURE IF EXISTS token_permission_cleanup_20261014$$
CREATE PROCEDURE token_permission_cleanup_20261014()
BEGIN
DECLARE previous_concat BIGINT DEFAULT @@session.group_concat_max_len;
DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; SET SESSION group_concat_max_len=previous_concat; RESIGNAL; END;
IF BINARY DATABASE()<>BINARY 'token' OR (SELECT COUNT(*) FROM information_schema.schemata WHERE BINARY schema_name=BINARY 'token')<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='权限清理只允许token库'; END IF;
SET SESSION group_concat_max_len=65535;
DROP TEMPORARY TABLE IF EXISTS token_permission_map;
DROP TEMPORARY TABLE IF EXISTS token_permission_roles;
CREATE TEMPORARY TABLE token_permission_map(old_code VARCHAR(9) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,new_code VARCHAR(9) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,PRIMARY KEY(old_code,new_code));
CREATE TEMPORARY TABLE token_permission_roles(id INT PRIMARY KEY,role_text LONGTEXT NOT NULL);
INSERT INTO token_permission_map(old_code,new_code) VALUES
('601','60101'),
('601','60102'),
('602','60200'),
('602','60201'),
('602','60202'),
('602','60203'),
('602','60205'),
('602','60206'),
('602','602041'),
('602','602042'),
('602','602043'),
('603','60300'),
('603','60301'),
('603','60302'),
('603','60303'),
('603','603041'),
('603','603042'),
('604','60400'),
('605','60500'),
('605','60501'),
('605','60502'),
('605','60503'),
('606','6060101'),
('606','6060102'),
('606','6060103'),
('606','6060104'),
('606','6060105'),
('606','6060201'),
('606','6060202'),
('606','6060203'),
('606','6060204'),
('606','6060205'),
('606','6060301'),
('606','6060302'),
('606','6060303'),
('606','6060401'),
('606','6060402'),
('606','6060403'),
('606','6060501'),
('606','6060502'),
('607','60701'),
('607','60702'),
('607','607031'),
('607','607032'),
('607','607033'),
('607','607041'),
('607','607042'),
('607','607043'),
('608','60801'),
('608','60802'),
('608','60803'),
('608','60804'),
('608','60805'),
('610','10100'),
('610','20100'),
('610','20101'),
('610','20102'),
('610','20103'),
('610','20104'),
('610','20105'),
('610','20106'),
('610','20107'),
('610','20200'),
('610','20201'),
('610','20300'),
('610','20301'),
('610','20302'),
('610','20500'),
('610','20501'),
('610','30100'),
('610','30101'),
('610','30200'),
('610','30201'),
('610','30300'),
('610','30400'),
('610','40100'),
('610','40200'),
('610','50100'),
('610','50101'),
('610','50200'),
('610','50201'),
('610','50300'),
('610','50301'),
('610','61100'),
('610','61101'),
('610','61200'),
('610','61201'),
('610','61300'),
('610','61301'),
('610','61400'),
('610','61401'),
('610','61500'),
('610','61501'),
('610','61700'),
('610','61701'),
('610','70100'),
('610','70101'),
('610','70200'),
('610','70201'),
('610','70300'),
('610','70301'),
('610','70400'),
('610','70401'),
('610','70402'),
('610','70500'),
('610','70501'),
('610','70600'),
('610','70601'),
('610','70800'),
('610','70801'),
('610','70900'),
('610','70901'),
('610','71000'),
('610','71001'),
('610','80100'),
('610','80200'),
('610','80201'),
('610','80202'),
('610','80203'),
('610','80204'),
('610','80300'),
('10100','10100'),
('20100','20100'),
('20101','20101'),
('20102','20102'),
('20103','20103'),
('20104','20104'),
('20105','20105'),
('20106','20106'),
('20107','20107'),
('20200','20200'),
('20201','20201'),
('20300','20300'),
('20301','20301'),
('20302','20302'),
('20400','20400'),
('20401','20401'),
('20402','20402'),
('20500','20500'),
('20501','20501'),
('30100','30100'),
('30101','30101'),
('30200','30200'),
('30201','30201'),
('30202','30202'),
('30300','30300'),
('30400','30400'),
('40100','40100'),
('40200','40200'),
('40300','40300'),
('50100','50100'),
('50101','50101'),
('50200','50200'),
('50201','50201'),
('50300','50300'),
('50301','50301'),
('50400','50400'),
('50401','50401'),
('60101','60101'),
('60102','60102'),
('60200','60200'),
('60201','60201'),
('60202','60202'),
('60203','60203'),
('60204','602041'),
('60204','602042'),
('60204','602043'),
('60205','60205'),
('60206','60206'),
('60300','60300'),
('60301','60301'),
('60302','60302'),
('60303','60303'),
('60304','603041'),
('60304','603042'),
('60400','60400'),
('60500','60500'),
('60501','60501'),
('60502','60502'),
('60503','60503'),
('60601','6060101'),
('60601','6060102'),
('60601','6060103'),
('60601','6060104'),
('60601','6060105'),
('60602','6060201'),
('60602','6060202'),
('60602','6060203'),
('60602','6060204'),
('60602','6060205'),
('60603','6060301'),
('60603','6060302'),
('60603','6060303'),
('60604','6060401'),
('60604','6060402'),
('60604','6060403'),
('60605','6060501'),
('60605','6060502'),
('60701','60701'),
('60702','60702'),
('60703','607031'),
('60703','607032'),
('60703','607033'),
('60704','607041'),
('60704','607042'),
('60704','607043'),
('60801','60801'),
('60802','60802'),
('60803','60803'),
('60804','60804'),
('60805','60805'),
('61001','10100'),
('61002','70101'),
('61002','70201'),
('61002','70301'),
('61003','20100'),
('61004','20101'),
('61004','20102'),
('61004','20500'),
('61004','20501'),
('61005','30100'),
('61005','30200'),
('61005','30300'),
('61005','30400'),
('61006','30101'),
('61006','30201'),
('61006','30300'),
('61006','30400'),
('61007','61101'),
('61007','61201'),
('61007','61301'),
('61007','61401'),
('61007','61501'),
('61007','70400'),
('61007','70401'),
('61007','70402'),
('61008','61700'),
('61009','61701'),
('61010','20200'),
('61010','20201'),
('61011','20300'),
('61011','20301'),
('61011','20302'),
('61012','40100'),
('61012','40200'),
('61013','70500'),
('61013','70600'),
('61013','70800'),
('61013','70900'),
('61013','71000'),
('61014','70501'),
('61014','70601'),
('61014','70801'),
('61014','70901'),
('61014','71001'),
('61015','50100'),
('61015','50200'),
('61015','50300'),
('61016','50101'),
('61016','50201'),
('61016','50301'),
('61017','20103'),
('61018','20104'),
('61019','70400'),
('61019','70401'),
('61019','70402'),
('61020','70100'),
('61020','70200'),
('61020','70300'),
('61021','61100'),
('61021','61200'),
('61021','61300'),
('61021','61400'),
('61021','61500'),
('61022','20105'),
('61023','20106'),
('61024','80100'),
('61024','80200'),
('61024','80201'),
('61024','80202'),
('61024','80203'),
('61024','80204'),
('61024','80300'),
('61100','61100'),
('61101','61101'),
('61200','61200'),
('61201','61201'),
('61300','61300'),
('61301','61301'),
('61400','61400'),
('61401','61401'),
('61500','61500'),
('61501','61501'),
('61700','61700'),
('61701','61701'),
('70100','70100'),
('70101','70101'),
('70200','70200'),
('70201','70201'),
('70300','70300'),
('70301','70301'),
('70400','70400'),
('70401','70401'),
('70402','70402'),
('70500','70500'),
('70501','70501'),
('70600','70600'),
('70601','70601'),
('70800','70800'),
('70801','70801'),
('70900','70900'),
('70901','70901'),
('71000','71000'),
('71001','71001'),
('80100','80100'),
('80200','80200'),
('80201','80201'),
('80202','80202'),
('80203','80203'),
('80204','80204'),
('80300','80300'),
('602041','602041'),
('602042','602042'),
('602043','602043'),
('603041','603041'),
('603042','603042'),
('607031','607031'),
('607032','607032'),
('607033','607033'),
('607041','607041'),
('607042','607042'),
('607043','607043'),
('610041','20101'),
('610042','20102'),
('610043','20500'),
('610043','20501'),
('610051','30100'),
('610052','30200'),
('610053','30300'),
('610054','30400'),
('610061','30101'),
('610062','30201'),
('610101','20200'),
('610102','20201'),
('610103','20107'),
('610111','20300'),
('610112','20301'),
('610113','20302'),
('610114','20400'),
('610115','20401'),
('610116','20402'),
('610191','70400'),
('610192','70401'),
('610193','70402'),
('610241','80200'),
('610242','80201'),
('610243','80100'),
('610244','80203'),
('610245','80202'),
('610246','80300'),
('610247','80204'),
('6060101','6060101'),
('6060102','6060102'),
('6060103','6060103'),
('6060104','6060104'),
('6060105','6060105'),
('6060201','6060201'),
('6060202','6060202'),
('6060203','6060203'),
('6060204','6060204'),
('6060205','6060205'),
('6060301','6060301'),
('6060302','6060302'),
('6060303','6060303'),
('6060401','6060401'),
('6060402','6060402'),
('6060403','6060403'),
('6060501','6060501'),
('6060502','6060502');
START TRANSACTION;
-- 按逗号分隔的完整权限项匹配，允许项两侧空白，不把非法的内部空白或前导零变为有效权限。
INSERT INTO token_permission_roles(id,role_text)
SELECT r.id,COALESCE(GROUP_CONCAT(DISTINCT m.new_code ORDER BY CAST(m.new_code AS UNSIGNED) SEPARATOR ','),'')
FROM user_role r LEFT JOIN token_permission_map m ON
CONVERT(COALESCE(r.role,'') USING utf8mb4) COLLATE utf8mb4_unicode_ci REGEXP
CONCAT('(^|,)[[:space:]]*',m.old_code,'[[:space:]]*(,|$)') COLLATE utf8mb4_unicode_ci
GROUP BY r.id;
-- 只修改权限串；isSelf超级角色标记、受保护账号和角色归属不变。
UPDATE user_role r JOIN token_permission_roles p ON p.id=r.id SET r.role=p.role_text;
DELETE FROM memu WHERE id IN (609,610,60901,61001,61002,61003,61004,61005,61006,61007,61008,61009,61010,61011,61012,61013,61014,61015,61016,61017,61018,61019,61020,61021,61022,61023,61024,610041,610042,610043,610051,610052,610053,610054,610061,610062,610101,610102,610103,610111,610112,610113,610114,610115,610116,610191,610192,610193,610241,610242,610243,610244,610245,610246,610247);
COMMIT;
DROP TEMPORARY TABLE token_permission_roles;
DROP TEMPORARY TABLE token_permission_map;
SET SESSION group_concat_max_len=previous_concat;
IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ad_master' AND column_name='permission') THEN ALTER TABLE ad_master DROP COLUMN permission; END IF;
END$$
CALL token_permission_cleanup_20261014()$$
DROP PROCEDURE token_permission_cleanup_20261014$$
DELIMITER ;

-- ===== branding.sql =====
-- Token 品牌、JSON字段和最新业务字典；不改外部商户身份、钱包币种或资金数值。
USE token;
SET NAMES utf8mb4;
SET time_zone='+00:00';
DELIMITER $$
DROP PROCEDURE IF EXISTS token_branding_20261014$$
CREATE PROCEDURE token_branding_20261014()
BEGIN
IF BINARY DATABASE()<>BINARY 'token' OR (SELECT COUNT(*) FROM information_schema.schemata WHERE BINARY schema_name=BINARY 'token')<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='品牌迁移只允许token库'; END IF;
ALTER TABLE `compute_activity_attempt_grant` MODIFY COLUMN `threshold_amount` decimal(26,8) NOT NULL COMMENT '本次资格对应的Token阶梯门槛；旧基线为0';
ALTER TABLE `compute_activity_attempt_grant` MODIFY COLUMN `deposit_amount` decimal(26,8) NOT NULL COMMENT '首次核验资格时成功充值的Token金额快照';
ALTER TABLE `compute_activity_invite_grant` MODIFY COLUMN `minimum_first_deposit` decimal(26,8) NOT NULL COMMENT '成员全历史首笔成功充值所需Token金额快照';
ALTER TABLE `compute_activity_invite_grant` MODIFY COLUMN `qualification_snapshot` json NOT NULL COMMENT '本档资格来源会员、首笔充值订单与固定Token金额快照';
ALTER TABLE `compute_checkin` MODIFY COLUMN `asset_rate` decimal(28,18) NOT NULL COMMENT '入账时每单位原币对应Token汇率快照；零奖励无需换算时记0';
ALTER TABLE `compute_finance_snapshot` MODIFY COLUMN `snapshot_source` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '固定计价来源：订单快照、首次回填或USD_ACCOUNTING_V2入账快照';
ALTER TABLE `compute_level` MODIFY COLUMN `threshold` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '自动升级所需COMPUTE钱包可用Token余额门槛，不含冻结或展示赠送';
ALTER TABLE `compute_level` MODIFY COLUMN `direct_member_deposit_threshold` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '每位正式直推成员累计成功充值固定计价门槛，单位Token，缺快照不计，0仍需正数充值';
ALTER TABLE `compute_order` MODIFY COLUMN `amount` decimal(28,8) NOT NULL COMMENT 'GPU订单固定本金，按原快照计息和返本';
ALTER TABLE `compute_order` MODIFY COLUMN `status` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ACTIVE' COMMENT '产品订单状态：ACTIVE履约中、COMPLETED已完成';
ALTER TABLE `compute_order` MODIFY COLUMN `daily_rate` decimal(14,8) NOT NULL COMMENT '购买时固定日收益百分比，不按当前配置重算';
ALTER TABLE `compute_order` MODIFY COLUMN `purchase_mode` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT 'GPU购买方式：AMOUNT金额或QUANTITY整数份数';
ALTER TABLE `compute_order` MODIFY COLUMN `purchase_quantity` bigint(20) DEFAULT NULL COMMENT 'GPU真实购买整数份数；金额购买为0，未记录时为空';
ALTER TABLE `compute_order` MODIFY COLUMN `purchase_unit_price` decimal(26,8) DEFAULT NULL COMMENT 'GPU购买时每份单价快照，单位Token；金额购买为0';
ALTER TABLE `compute_product` MODIFY COLUMN `unit_price` decimal(26,8) NOT NULL DEFAULT '0.00000000' COMMENT 'GPU每份单价，单位Token；份数模式必须大于零';
ALTER TABLE `compute_product` MODIFY COLUMN `max_purchase_amount` decimal(26,8) NOT NULL DEFAULT '0.00000000' COMMENT '每用户每GPU项目累计购买上限，单位Token，0不限，到期不恢复';
ALTER TABLE system_notification COMMENT='管理员站内收件箱，实时弹窗不持久化';
END$$
CALL token_branding_20261014()$$
DROP PROCEDURE token_branding_20261014$$
DELIMITER ;
START TRANSACTION;
UPDATE compute_order SET product_quote_json=JSON_REMOVE(JSON_SET(product_quote_json,'$.quota.purchasedAmountToken',JSON_EXTRACT(product_quote_json,'$.quota.purchasedAmountCredits')),'$.quota.purchasedAmountCredits') WHERE JSON_CONTAINS_PATH(product_quote_json,'one','$.quota.purchasedAmountCredits');
UPDATE compute_order SET product_quote_json=JSON_REMOVE(JSON_SET(product_quote_json,'$.quota.remainingAmountToken',JSON_EXTRACT(product_quote_json,'$.quota.remainingAmountCredits')),'$.quota.remainingAmountCredits') WHERE JSON_CONTAINS_PATH(product_quote_json,'one','$.quota.remainingAmountCredits');
UPDATE compute_order SET product_quote_json=JSON_REMOVE(JSON_SET(product_quote_json,'$.unitPriceToken',JSON_EXTRACT(product_quote_json,'$.unitPriceCredits')),'$.unitPriceCredits') WHERE JSON_CONTAINS_PATH(product_quote_json,'one','$.unitPriceCredits');
UPDATE compute_order SET product_quote_json=JSON_REMOVE(JSON_SET(product_quote_json,'$.purchaseAmountToken',JSON_EXTRACT(product_quote_json,'$.purchaseAmountCredits')),'$.purchaseAmountCredits') WHERE JSON_CONTAINS_PATH(product_quote_json,'one','$.purchaseAmountCredits');
UPDATE compute_reward_award SET rule_snapshot=JSON_REMOVE(JSON_SET(rule_snapshot,'$.effectiveDepositToken',JSON_EXTRACT(rule_snapshot,'$.effectiveDepositCredits')),'$.effectiveDepositCredits') WHERE JSON_CONTAINS_PATH(rule_snapshot,'one','$.effectiveDepositCredits');
UPDATE compute_withdrawal SET rule_snapshot=JSON_REMOVE(JSON_SET(rule_snapshot,'$.localReview.amountToken',JSON_EXTRACT(rule_snapshot,'$.localReview.amountCredits')),'$.localReview.amountCredits') WHERE JSON_CONTAINS_PATH(rule_snapshot,'one','$.localReview.amountCredits');
UPDATE compute_withdrawal SET rule_snapshot=JSON_REMOVE(JSON_SET(rule_snapshot,'$.localReview.thresholdToken',JSON_EXTRACT(rule_snapshot,'$.localReview.thresholdCredits')),'$.localReview.thresholdCredits') WHERE JSON_CONTAINS_PATH(rule_snapshot,'one','$.localReview.thresholdCredits');
UPDATE system_setting SET setting_value=JSON_REMOVE(JSON_SET(IF(JSON_VALID(setting_value),setting_value,JSON_OBJECT()),'$.displayGift.amountToken',JSON_EXTRACT(IF(JSON_VALID(setting_value),setting_value,JSON_OBJECT()),'$.displayGift.amountCredits')),'$.displayGift.amountCredits') WHERE JSON_CONTAINS_PATH(IF(JSON_VALID(setting_value),setting_value,JSON_OBJECT()),'one','$.displayGift.amountCredits');
UPDATE system_setting SET setting_value=JSON_REMOVE(JSON_SET(IF(JSON_VALID(setting_value),setting_value,JSON_OBJECT()),'$.thresholdToken',JSON_EXTRACT(IF(JSON_VALID(setting_value),setting_value,JSON_OBJECT()),'$.thresholdCredits')),'$.thresholdCredits') WHERE JSON_CONTAINS_PATH(IF(JSON_VALID(setting_value),setting_value,JSON_OBJECT()),'one','$.thresholdCredits');
UPDATE compute_level SET translations_json=REPLACE(REPLACE(REPLACE(translations_json,'CREDITS','TOKEN'),'Credits','Token'),'credits','token') WHERE LOWER(CAST(translations_json AS CHAR)) LIKE '%credits%';
UPDATE compute_auto_contract SET rule_snapshot=JSON_SET(rule_snapshot,'$.translationsJson',REPLACE(REPLACE(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(rule_snapshot,'$.translationsJson')),'CREDITS','TOKEN'),'Credits','Token'),'credits','token')) WHERE JSON_TYPE(JSON_EXTRACT(rule_snapshot,'$.translationsJson'))='STRING' AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(rule_snapshot,'$.translationsJson'))) LIKE '%credits%';
UPDATE translates SET tkey=REPLACE(REPLACE(REPLACE(tkey,'CREDITS','TOKEN'),'Credits','Token'),'credits','token'),tvalue=REPLACE(REPLACE(REPLACE(tvalue,'CREDITS','TOKEN'),'Credits','Token'),'credits','token'),title=REPLACE(REPLACE(REPLACE(title,'CREDITS','TOKEN'),'Credits','Token'),'credits','token'),action_time=UTC_TIMESTAMP() WHERE LOWER(tkey) LIKE '%credits%' OR LOWER(tvalue) LIKE '%credits%' OR LOWER(title) LIKE '%credits%';
UPDATE system_setting SET setting_value=REPLACE(REPLACE(REPLACE(setting_value,'CREDITS','TOKEN'),'Credits','Token'),'credits','token'),revision=revision+1,updated_at=UTC_TIMESTAMP(6) WHERE group_name IN ('site','user-site','user-share') AND LOWER(setting_value) LIKE '%credits%';
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) SELECT 'user.product.quoteExpired','报价已过期，请刷新后重试','zh',7,0,'',0,UTC_TIMESTAMP() WHERE NOT EXISTS(SELECT 1 FROM translates WHERE tkey='user.product.quoteExpired' AND lang='zh' AND type=7);
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) SELECT 'user.product.quoteExpired','This quote has expired. Refresh it and try again.','en',7,0,'',0,UTC_TIMESTAMP() WHERE NOT EXISTS(SELECT 1 FROM translates WHERE tkey='user.product.quoteExpired' AND lang='en' AND type=7);
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) SELECT 'user.product.quoting','正在获取报价','zh',7,0,'',0,UTC_TIMESTAMP() WHERE NOT EXISTS(SELECT 1 FROM translates WHERE tkey='user.product.quoting' AND lang='zh' AND type=7);
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) SELECT 'user.product.quoting','Fetching quote','en',7,0,'',0,UTC_TIMESTAMP() WHERE NOT EXISTS(SELECT 1 FROM translates WHERE tkey='user.product.quoting' AND lang='en' AND type=7);
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) SELECT 'user.product.refreshQuote','刷新报价','zh',7,0,'',0,UTC_TIMESTAMP() WHERE NOT EXISTS(SELECT 1 FROM translates WHERE tkey='user.product.refreshQuote' AND lang='zh' AND type=7);
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) SELECT 'user.product.refreshQuote','Refresh quote','en',7,0,'',0,UTC_TIMESTAMP() WHERE NOT EXISTS(SELECT 1 FROM translates WHERE tkey='user.product.refreshQuote' AND lang='en' AND type=7);
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) SELECT 'server.settings.concurrentUpdate','配置已被其他请求修改，请刷新后重试','zh',1,0,'',0,UTC_TIMESTAMP() WHERE NOT EXISTS(SELECT 1 FROM translates WHERE tkey='server.settings.concurrentUpdate' AND lang='zh' AND type=1);
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) SELECT 'server.settings.concurrentUpdate','Settings were changed by another request. Refresh and try again.','en',1,0,'',0,UTC_TIMESTAMP() WHERE NOT EXISTS(SELECT 1 FROM translates WHERE tkey='server.settings.concurrentUpdate' AND lang='en' AND type=1);
UPDATE system_setting SET setting_value=LOWER(UUID()),revision=revision+1,updated_at=UTC_TIMESTAMP(6) WHERE setting_key='i18n.translations.revision';
COMMIT;

-- ===== dictionary.sql =====
USE token;
SET NAMES utf8mb4;
ALTER TABLE `compute_fee_rule` MODIFY COLUMN `open_time` time DEFAULT NULL COMMENT '提款开始时间，按系统业务时区';
ALTER TABLE `compute_fee_rule` MODIFY COLUMN `close_time` time DEFAULT NULL COMMENT '提款结束时间不包含边界，跨午夜归开始日，按系统业务时区';
ALTER TABLE `compute_fee_rule` MODIFY COLUMN `free_start_time` time DEFAULT NULL COMMENT '免费时段开始时间，按系统业务时区';
ALTER TABLE `compute_fee_rule` MODIFY COLUMN `free_end_time` time DEFAULT NULL COMMENT '免费时段结束时间，按系统业务时区';
ALTER TABLE `compute_company_file` MODIFY COLUMN `resource_type` varchar(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PDF' COMMENT '公司资源类型：PDF私有文件或LINK外部链接';
ALTER TABLE `compute_exchange_refresh` MODIFY COLUMN `next_attempt_at` datetime(6) DEFAULT NULL COMMENT '下次尝试时间，UTC；加密行情30分钟，法币12小时，失败沿用各自周期';
ALTER TABLE `compute_level` MODIFY COLUMN `direct_member_deposit_threshold` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '有效直推成员累计成功充值的固定Token门槛，包含测试用户，缺计价不补算';

-- ===== current-ui-text.sql =====
USE token;
SET NAMES utf8mb4;
START TRANSACTION;
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) VALUES('user.product.quoteExpired','价格已过期，请刷新后确认。','zh',7,0,'',0,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE tvalue=VALUES(tvalue),action_time=UTC_TIMESTAMP();
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) VALUES('user.product.quoting','正在计算购买价格…','zh',7,0,'',0,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE tvalue=VALUES(tvalue),action_time=UTC_TIMESTAMP();
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) VALUES('user.product.refreshQuote','刷新','zh',7,0,'',0,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE tvalue=VALUES(tvalue),action_time=UTC_TIMESTAMP();
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) VALUES('user.ui.products.catalogIntro','选择 GPU 算力项目，按项目周期与日收益率参与。','zh',7,0,'',0,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE tvalue=VALUES(tvalue),action_time=UTC_TIMESTAMP();
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) VALUES('user.v8.auto.catalogIntro','探索 GPU 项目，各自按产品配置和订单周期运行。','zh',7,0,'',0,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE tvalue=VALUES(tvalue),action_time=UTC_TIMESTAMP();
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) VALUES('user.v9.productsIntro','GPU 产品按各自购买方式、周期和日收益率规则执行。','zh',7,0,'',0,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE tvalue=VALUES(tvalue),action_time=UTC_TIMESTAMP();
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) VALUES('user.home.description','在一个平台探索模型算力与 GPU 项目，管理您的每一笔投入。','zh',7,0,'',0,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE tvalue=VALUES(tvalue),action_time=UTC_TIMESTAMP();
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) VALUES('user.product.risk','日收益率范围仅供参考，实际条款以服务端生成的订单快照为准。B、D 模式不返还本金，请确认后购买。','zh',7,0,'',0,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE tvalue=VALUES(tvalue),action_time=UTC_TIMESTAMP();
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) VALUES('user.order.rate','订单日收益率','zh',7,0,'',0,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE tvalue=VALUES(tvalue),action_time=UTC_TIMESTAMP();
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) VALUES('user.settings.answerWallet','算力钱包作为 Token 算力收益的计算基数，项目钱包用于购买 GPU 项目；收益钱包接收结算收益与奖励，并支持按规则提款。','zh',7,0,'',0,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE tvalue=VALUES(tvalue),action_time=UTC_TIMESTAMP();
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) VALUES('user.settings.answerIncome','每笔订单在购买时保存日收益率、周期和结算方式，按系统业务时区执行结算。收益到账后可在收益记录和钱包中查看。','zh',7,0,'',0,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE tvalue=VALUES(tvalue),action_time=UTC_TIMESTAMP();
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) VALUES('user.home.description','Explore model compute and GPU projects in one place, and manage every allocation.','en',7,0,'',0,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE tvalue=VALUES(tvalue),action_time=UTC_TIMESTAMP();
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) VALUES('user.product.risk','The daily return range is a reference. The server-generated order snapshot determines the actual terms. Modes B and D do not return principal; confirm carefully.','en',7,0,'',0,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE tvalue=VALUES(tvalue),action_time=UTC_TIMESTAMP();
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) VALUES('user.order.rate','Order daily return','en',7,0,'',0,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE tvalue=VALUES(tvalue),action_time=UTC_TIMESTAMP();
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) VALUES('user.settings.answerWallet','The compute wallet provides the basis for Token compute income. The project wallet funds GPU projects. The earnings wallet receives settled income and rewards, and supports withdrawals under platform rules.','en',7,0,'',0,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE tvalue=VALUES(tvalue),action_time=UTC_TIMESTAMP();
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) VALUES('user.settings.answerIncome','Each order saves its daily return, duration, and settlement method at purchase. Settlement uses the system business time zone. Once credited, income appears in your income records and wallet.','en',7,0,'',0,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE tvalue=VALUES(tvalue),action_time=UTC_TIMESTAMP();
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) VALUES('user.product.quoteExpired','This price has expired. Refresh it before confirming.','en',7,0,'',0,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE tvalue=VALUES(tvalue),action_time=UTC_TIMESTAMP();
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) VALUES('user.product.quoting','Calculating your purchase price…','en',7,0,'',0,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE tvalue=VALUES(tvalue),action_time=UTC_TIMESTAMP();
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) VALUES('user.product.refreshQuote','Refresh','en',7,0,'',0,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE tvalue=VALUES(tvalue),action_time=UTC_TIMESTAMP();
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) VALUES('user.ui.products.catalogIntro','Explore GPU compute projects with their own duration and daily return.','en',7,0,'',0,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE tvalue=VALUES(tvalue),action_time=UTC_TIMESTAMP();
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) VALUES('user.v8.auto.catalogIntro','Explore GPU projects, each following its own configuration and order cycle.','en',7,0,'',0,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE tvalue=VALUES(tvalue),action_time=UTC_TIMESTAMP();
INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time) VALUES('user.v9.productsIntro','GPU products follow their configured purchase mode, duration and daily return.','en',7,0,'',0,UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE tvalue=VALUES(tvalue),action_time=UTC_TIMESTAMP();
UPDATE system_setting SET setting_value=LOWER(UUID()),revision=revision+1,updated_at=UTC_TIMESTAMP(6) WHERE setting_key='i18n.translations.revision';
COMMIT;

-- ===== retired_settings.sql =====
-- 固定清理已移除消费者的旧配置；当前配置与资金事实不变。
USE token;
SET NAMES utf8mb4;
DELIMITER $$
DROP PROCEDURE IF EXISTS token_retired_settings_20261014$$
CREATE PROCEDURE token_retired_settings_20261014()
BEGIN
  IF BINARY DATABASE() <> BINARY 'token'
     OR (SELECT COUNT(*) FROM information_schema.schemata WHERE BINARY schema_name = BINARY 'token') <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '旧配置清理只允许token库';
  END IF;
  DELETE FROM system_setting
  WHERE group_name = 'compute'
    AND setting_key IN ('teamDepth', 'registrationReward', 'depositRewardRate', 'subscriptionEnabled', 'vipAutoUpgradeEnabled');
END$$
CALL token_retired_settings_20261014()$$
DROP PROCEDURE token_retired_settings_20261014$$
DELIMITER ;

-- ===== i18n-current-supplement.sql =====
-- 当前中英词条补缺；仅补21个在用管理键，不覆盖人工非空翻译。
USE token;
SET NAMES utf8mb4;
START TRANSACTION;
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.hint.v38.directMembersHelp','zh','人数为0时不限制。按直属成员全历史成功充值判断有效资格，包含测试账号；停用账号已完成的充值仍保留。自动升级开启时，算力余额和有效直推人数同时达标才升级，不会自动降级。',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.hint.v38.directMembersHelp' AND lang='zh');
UPDATE translates SET tvalue='人数为0时不限制。按直属成员全历史成功充值判断有效资格，包含测试账号；停用账号已完成的充值仍保留。自动升级开启时，算力余额和有效直推人数同时达标才升级，不会自动降级。',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.hint.v38.directMembersHelp' AND lang='zh' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.hint.v38.testMemberHelp','zh','测试标记只作用于本人，不沿邀请关系继承。正常参与奖励、活动及人数统计，报表仅排除其金额；测试充值不能使用真实链，提款内部完成、不真实代付。',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.hint.v38.testMemberHelp' AND lang='zh');
UPDATE translates SET tvalue='测试标记只作用于本人，不沿邀请关系继承。正常参与奖励、活动及人数统计，报表仅排除其金额；测试充值不能使用真实链，提款内部完成、不真实代付。',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.hint.v38.testMemberHelp' AND lang='zh' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v26Payout.gatewayAttempts','zh','任务尝试次数',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.gatewayAttempts' AND lang='zh');
UPDATE translates SET tvalue='任务尝试次数',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.gatewayAttempts' AND lang='zh' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v26Payout.gatewayNextAttemptAt','zh','下次查询或重试',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.gatewayNextAttemptAt' AND lang='zh');
UPDATE translates SET tvalue='下次查询或重试',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.gatewayNextAttemptAt' AND lang='zh' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v26Payout.gatewayReason','zh','最近失败原因',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.gatewayReason' AND lang='zh');
UPDATE translates SET tvalue='最近失败原因',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.gatewayReason' AND lang='zh' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v26Payout.gatewayState','zh','提交阶段',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.gatewayState' AND lang='zh');
UPDATE translates SET tvalue='提交阶段',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.gatewayState' AND lang='zh' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v26Payout.reason.CUSTODY_PAYOUT_DISABLED','zh','Crypto 托管代付未启用',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.CUSTODY_PAYOUT_DISABLED' AND lang='zh');
UPDATE translates SET tvalue='Crypto 托管代付未启用',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.CUSTODY_PAYOUT_DISABLED' AND lang='zh' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v26Payout.reason.GATEWAY_ACCESS_DENIED','zh','支付接口接入被拒绝，请核对商户凭据及权限',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.GATEWAY_ACCESS_DENIED' AND lang='zh');
UPDATE translates SET tvalue='支付接口接入被拒绝，请核对商户凭据及权限',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.GATEWAY_ACCESS_DENIED' AND lang='zh' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v26Payout.reason.GATEWAY_INVALID_RESPONSE','zh','支付响应核验失败，保留冻结待核查',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.GATEWAY_INVALID_RESPONSE' AND lang='zh');
UPDATE translates SET tvalue='支付响应核验失败，保留冻结待核查',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.GATEWAY_INVALID_RESPONSE' AND lang='zh' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v26Payout.reason.GATEWAY_RATE_LIMITED','zh','支付接口限流，等待重试',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.GATEWAY_RATE_LIMITED' AND lang='zh');
UPDATE translates SET tvalue='支付接口限流，等待重试',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.GATEWAY_RATE_LIMITED' AND lang='zh' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v26Payout.reason.GATEWAY_TIMEOUT','zh','支付请求超时，保留原单查询',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.GATEWAY_TIMEOUT' AND lang='zh');
UPDATE translates SET tvalue='支付请求超时，保留原单查询',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.GATEWAY_TIMEOUT' AND lang='zh' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v26Payout.reason.MERCHANT_POOL_UNAVAILABLE','zh','Crypto 无可用代付钱包或可用余额不足',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.MERCHANT_POOL_UNAVAILABLE' AND lang='zh');
UPDATE translates SET tvalue='Crypto 无可用代付钱包或可用余额不足',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.MERCHANT_POOL_UNAVAILABLE' AND lang='zh' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v38Members.onlineStatus','zh','在线状态',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Members.onlineStatus' AND lang='zh');
UPDATE translates SET tvalue='在线状态',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Members.onlineStatus' AND lang='zh' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v38Members.paymentSummary','zh','充提汇总',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Members.paymentSummary' AND lang='zh');
UPDATE translates SET tvalue='充提汇总',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Members.paymentSummary' AND lang='zh' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v38Reports.effectiveHint','zh','按真实直属关系和成功充值判断有效客户，测试账号正常参与。新奖励按来源事件幂等发放；每个来源下级只占一个有效客户档位，修改奖励不中断已占用记录、不重发已领取档位。',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Reports.effectiveHint' AND lang='zh');
UPDATE translates SET tvalue='按真实直属关系和成功充值判断有效客户，测试账号正常参与。新奖励按来源事件幂等发放；每个来源下级只占一个有效客户档位，修改奖励不中断已占用记录、不重发已领取档位。',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Reports.effectiveHint' AND lang='zh' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v38Reports.invitedHelp','zh','只统计本人直属成员，包含测试账号。全历史第一笔成功充值须在当前活动或轮次有效期内发生并达到门槛，后续充值不能补足首充资格；测试来源只计本轮业务切换后的新充值。各人数档每轮分别奖励一次，例如1人获1次、2人获2次，达到2人累计获3次。',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Reports.invitedHelp' AND lang='zh');
UPDATE translates SET tvalue='只统计本人直属成员，包含测试账号。全历史第一笔成功充值须在当前活动或轮次有效期内发生并达到门槛，后续充值不能补足首充资格；测试来源只计本轮业务切换后的新充值。各人数档每轮分别奖励一次，例如1人获1次、2人获2次，达到2人累计获3次。',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Reports.invitedHelp' AND lang='zh' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v38Reports.peopleHelp','zh','人数包含测试账号；充值人数在区间内去重，首充人数按全历史首次成功充值认定。金额汇总排除测试账号。',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Reports.peopleHelp' AND lang='zh');
UPDATE translates SET tvalue='人数包含测试账号；充值人数在区间内去重，首充人数按全历史首次成功充值认定。金额汇总排除测试账号。',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Reports.peopleHelp' AND lang='zh' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v38Reports.personalHelp','zh','按用户展示资金、产品、算力和邀请关系。测试账号保留成员信息、人数与个人记录，其金额不计入报表汇总；趋势按系统自然日展示。',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Reports.personalHelp' AND lang='zh');
UPDATE translates SET tvalue='按用户展示资金、产品、算力和邀请关系。测试账号保留成员信息、人数与个人记录，其金额不计入报表汇总；趋势按系统自然日展示。',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Reports.personalHelp' AND lang='zh' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v38Reports.teamHelp','zh','团队包含本人及实际邀请后代，按选定累计深度或全部层级统计。测试成员正常展示并计入人数，仅其金额从汇总排除；正式下级仍正常统计。',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Reports.teamHelp' AND lang='zh');
UPDATE translates SET tvalue='团队包含本人及实际邀请后代，按选定累计深度或全部层级统计。测试成员正常展示并计入人数，仅其金额从汇总排除；正式下级仍正常统计。',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Reports.teamHelp' AND lang='zh' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v36.global.withdrawalFee','zh','提款手续费',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v36.global.withdrawalFee' AND lang='zh');
UPDATE translates SET tvalue='提款手续费',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v36.global.withdrawalFee' AND lang='zh' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v36.global.withdrawalGrossAmount','zh','提款总额',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v36.global.withdrawalGrossAmount' AND lang='zh');
UPDATE translates SET tvalue='提款总额',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v36.global.withdrawalGrossAmount' AND lang='zh' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.hint.v38.directMembersHelp','en','Zero means no minimum. Qualification uses direct members'' full successful deposit history, including test accounts. Completed deposits remain valid for disabled accounts. When automatic upgrades are enabled, both compute balance and qualified direct-member requirements must be met. Levels never downgrade automatically.',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.hint.v38.directMembersHelp' AND lang='en');
UPDATE translates SET tvalue='Zero means no minimum. Qualification uses direct members'' full successful deposit history, including test accounts. Completed deposits remain valid for disabled accounts. When automatic upgrades are enabled, both compute balance and qualified direct-member requirements must be met. Levels never downgrade automatically.',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.hint.v38.directMembersHelp' AND lang='en' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.hint.v38.testMemberHelp','en','The test flag applies only to this account and is not inherited through invitations. Rewards, activities and member counts work normally; only monetary report totals exclude it. Real blockchain deposits remain unavailable, and withdrawals complete internally without a real payout.',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.hint.v38.testMemberHelp' AND lang='en');
UPDATE translates SET tvalue='The test flag applies only to this account and is not inherited through invitations. Rewards, activities and member counts work normally; only monetary report totals exclude it. Real blockchain deposits remain unavailable, and withdrawals complete internally without a real payout.',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.hint.v38.testMemberHelp' AND lang='en' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v26Payout.gatewayAttempts','en','Job attempts',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.gatewayAttempts' AND lang='en');
UPDATE translates SET tvalue='Job attempts',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.gatewayAttempts' AND lang='en' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v26Payout.gatewayNextAttemptAt','en','Next lookup or retry',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.gatewayNextAttemptAt' AND lang='en');
UPDATE translates SET tvalue='Next lookup or retry',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.gatewayNextAttemptAt' AND lang='en' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v26Payout.gatewayReason','en','Latest failure',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.gatewayReason' AND lang='en');
UPDATE translates SET tvalue='Latest failure',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.gatewayReason' AND lang='en' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v26Payout.gatewayState','en','Submission stage',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.gatewayState' AND lang='en');
UPDATE translates SET tvalue='Submission stage',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.gatewayState' AND lang='en' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v26Payout.reason.CUSTODY_PAYOUT_DISABLED','en','Crypto custody payouts are disabled',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.CUSTODY_PAYOUT_DISABLED' AND lang='en');
UPDATE translates SET tvalue='Crypto custody payouts are disabled',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.CUSTODY_PAYOUT_DISABLED' AND lang='en' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v26Payout.reason.GATEWAY_ACCESS_DENIED','en','Payment access denied; check merchant credentials and permissions',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.GATEWAY_ACCESS_DENIED' AND lang='en');
UPDATE translates SET tvalue='Payment access denied; check merchant credentials and permissions',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.GATEWAY_ACCESS_DENIED' AND lang='en' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v26Payout.reason.GATEWAY_INVALID_RESPONSE','en','Payment response validation failed; funds remain frozen',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.GATEWAY_INVALID_RESPONSE' AND lang='en');
UPDATE translates SET tvalue='Payment response validation failed; funds remain frozen',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.GATEWAY_INVALID_RESPONSE' AND lang='en' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v26Payout.reason.GATEWAY_RATE_LIMITED','en','Payment API rate limited; retry pending',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.GATEWAY_RATE_LIMITED' AND lang='en');
UPDATE translates SET tvalue='Payment API rate limited; retry pending',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.GATEWAY_RATE_LIMITED' AND lang='en' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v26Payout.reason.GATEWAY_TIMEOUT','en','Payment request timed out; checking the original order',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.GATEWAY_TIMEOUT' AND lang='en');
UPDATE translates SET tvalue='Payment request timed out; checking the original order',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.GATEWAY_TIMEOUT' AND lang='en' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v26Payout.reason.MERCHANT_POOL_UNAVAILABLE','en','Crypto has no available payout wallet or sufficient available balance',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.MERCHANT_POOL_UNAVAILABLE' AND lang='en');
UPDATE translates SET tvalue='Crypto has no available payout wallet or sufficient available balance',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v26Payout.reason.MERCHANT_POOL_UNAVAILABLE' AND lang='en' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v38Members.onlineStatus','en','Online status',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Members.onlineStatus' AND lang='en');
UPDATE translates SET tvalue='Online status',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Members.onlineStatus' AND lang='en' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v38Members.paymentSummary','en','Deposits and withdrawals',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Members.paymentSummary' AND lang='en');
UPDATE translates SET tvalue='Deposits and withdrawals',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Members.paymentSummary' AND lang='en' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v38Reports.effectiveHint','en','Qualified customers use actual direct relationships and successful deposits, including test accounts. New rewards are idempotent by source event. Each source member occupies one qualified-customer tier; changing rewards preserves claimed tiers and does not grant them again.',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Reports.effectiveHint' AND lang='en');
UPDATE translates SET tvalue='Qualified customers use actual direct relationships and successful deposits, including test accounts. New rewards are idempotent by source event. Each source member occupies one qualified-customer tier; changing rewards preserves claimed tiers and does not grant them again.',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Reports.effectiveHint' AND lang='en' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v38Reports.invitedHelp','en','Counts direct members, including test accounts. Their first successful deposit in full history must meet the threshold within the current activity or round; later deposits cannot complete first-deposit qualification. Test sources count only new deposits after the participation cutover. Each member-count tier is granted once per round: 1 member grants 1 chance and 2 members grant 2 more, for 3 chances in total.',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Reports.invitedHelp' AND lang='en');
UPDATE translates SET tvalue='Counts direct members, including test accounts. Their first successful deposit in full history must meet the threshold within the current activity or round; later deposits cannot complete first-deposit qualification. Test sources count only new deposits after the participation cutover. Each member-count tier is granted once per round: 1 member grants 1 chance and 2 members grant 2 more, for 3 chances in total.',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Reports.invitedHelp' AND lang='en' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v38Reports.peopleHelp','en','Counts include test accounts. Depositors are deduplicated within the period, and first deposits use each member''s full successful deposit history. Monetary totals exclude test accounts.',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Reports.peopleHelp' AND lang='en');
UPDATE translates SET tvalue='Counts include test accounts. Depositors are deduplicated within the period, and first deposits use each member''s full successful deposit history. Monetary totals exclude test accounts.',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Reports.peopleHelp' AND lang='en' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v38Reports.personalHelp','en','Shows each member''s funds, products, compute activity and invitation relationships. Test accounts retain their member details, counts and personal records, while their amounts are excluded from monetary report totals. Trends use system calendar days.',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Reports.personalHelp' AND lang='en');
UPDATE translates SET tvalue='Shows each member''s funds, products, compute activity and invitation relationships. Test accounts retain their member details, counts and personal records, while their amounts are excluded from monetary report totals. Trends use system calendar days.',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Reports.personalHelp' AND lang='en' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v38Reports.teamHelp','en','Teams include the member and actual invitation descendants, using the selected cumulative depth or all levels. Test members remain visible and count toward totals; only their amounts are excluded. Regular descendants continue to count normally.',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Reports.teamHelp' AND lang='en');
UPDATE translates SET tvalue='Teams include the member and actual invitation descendants, using the selected cumulative depth or all levels. Test members remain visible and count toward totals; only their amounts are excluded. Regular descendants continue to count normally.',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v38Reports.teamHelp' AND lang='en' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v36.global.withdrawalFee','en','Withdrawal fees',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v36.global.withdrawalFee' AND lang='en');
UPDATE translates SET tvalue='Withdrawal fees',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v36.global.withdrawalFee' AND lang='en' AND TRIM(COALESCE(tvalue,''))='';
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT 'admin.compute.v36.global.withdrawalGrossAmount','en','Gross withdrawals',5,0,'当前界面词条补齐',0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v36.global.withdrawalGrossAmount' AND lang='en');
UPDATE translates SET tvalue='Gross withdrawals',action_time=UTC_TIMESTAMP(6) WHERE type=5 AND BINARY tkey=BINARY 'admin.compute.v36.global.withdrawalGrossAmount' AND lang='en' AND TRIM(COALESCE(tvalue,''))='';
-- 固定7个无现行调用的旧界面键；不扩展到其它未引用词条。
DELETE FROM translates WHERE type=5 AND tkey IN ('admin.compute.v11.features.floatingActivity','admin.compute.v11.features.title','admin.compute.v11.sound.attention','admin.compute.v11.sound.choose','admin.compute.v11.sound.help','admin.compute.v11.sound.title','admin.compute.v11.visuals.walletShare');
COMMIT;

-- ===== indexes.sql =====
-- 清理旧列退出后缩短为主键或现存索引完全副本的三个索引。
USE token;
SET NAMES utf8mb4;
DELIMITER $$
DROP PROCEDURE IF EXISTS token_redundant_indexes_20261014$$
CREATE PROCEDURE token_redundant_indexes_20261014()
BEGIN
  IF BINARY DATABASE() <> BINARY 'token'
     OR (SELECT COUNT(*) FROM information_schema.schemata WHERE BINARY schema_name = BINARY 'token') <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '冗余索引清理只允许token库';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='compute_member' AND index_name='idx_compute_member_agent') THEN
    IF (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='compute_member' AND index_name='idx_compute_member_agent') <> 1
       OR NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='compute_member' AND index_name='idx_compute_member_agent' AND column_name='id' AND seq_in_index=1 AND non_unique=1 AND sub_part IS NULL)
       OR COALESCE((SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='compute_member' AND index_name='PRIMARY'), '') <> 'id' THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '会员旧代理索引结构不符，停止清理';
    END IF;
    ALTER TABLE compute_member DROP INDEX idx_compute_member_agent;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='compute_content' AND index_name='idx_compute_content_scope') THEN
    IF (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='compute_content' AND index_name='idx_compute_content_scope') <> 1
       OR NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='compute_content' AND index_name='idx_compute_content_scope' AND column_name='id' AND seq_in_index=1 AND non_unique=1 AND sub_part IS NULL)
       OR COALESCE((SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='compute_content' AND index_name='PRIMARY'), '') <> 'id' THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '内容旧代理索引结构不符，停止清理';
    END IF;
    ALTER TABLE compute_content DROP INDEX idx_compute_content_scope;
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='compute_auto_contract' AND index_name='idx_v8_auto_daily') THEN
    IF COALESCE((SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='compute_auto_contract' AND index_name='idx_v8_auto_daily'), '') <> 'member_id,created_at'
       OR COALESCE((SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='compute_auto_contract' AND index_name='idx_auto_member'), '') <> 'member_id,created_at'
       OR EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='compute_auto_contract' AND index_name IN ('idx_v8_auto_daily','idx_auto_member') AND (non_unique<>1 OR sub_part IS NOT NULL)) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'VIP重复索引结构不符，停止清理';
    END IF;
    ALTER TABLE compute_auto_contract DROP INDEX idx_v8_auto_daily;
  END IF;
END$$
CALL token_redundant_indexes_20261014()$$
DROP PROCEDURE token_redundant_indexes_20261014$$
DELIMITER ;

-- ===== replay-snapshots.sql =====
-- 同步当前幂等响应字段名，不修改请求号、资金数值和外部商户身份。
USE token;
SET NAMES utf8mb4;
DELIMITER $$
DROP PROCEDURE IF EXISTS token_replay_snapshots_20261014$$
CREATE PROCEDURE token_replay_snapshots_20261014()
BEGIN
IF BINARY DATABASE()<>BINARY 'token' OR (SELECT COUNT(*) FROM information_schema.schemata WHERE BINARY schema_name=BINARY 'token')<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='快照字段收敛只允许token库'; END IF;
START TRANSACTION;
UPDATE compute_request r JOIN compute_order o ON r.member_id=o.member_id AND CAST(JSON_UNQUOTE(JSON_EXTRACT(r.result_json,'$.id')) AS UNSIGNED)=o.id
SET r.result_json=JSON_REMOVE(JSON_SET(r.result_json,'$.productQuoteJson',CAST(o.product_quote_json AS CHAR)),'$.subscriptionQuoteJson','$.annualRate','$.rateBasis','$.modelIds','$.subscriptionRank','$.subscriptionPaidAmount','$.subscriptionCreditAmount','$.subscriptionPreviousOrderId','$.subscriptionEndedAt')
WHERE r.request_type='ORDER' AND o.product_type='GPU' AND JSON_CONTAINS_PATH(r.result_json,'one','$.subscriptionQuoteJson');
UPDATE compute_request SET result_json=JSON_REMOVE(JSON_SET(result_json,'$.purchaseAmountToken',JSON_EXTRACT(result_json,'$.purchaseAmountCredits')),'$.purchaseAmountCredits') WHERE request_type='ORDER' AND JSON_CONTAINS_PATH(result_json,'one','$.purchaseAmountCredits');
UPDATE compute_request r JOIN compute_withdrawal w ON r.member_id=w.member_id AND CAST(JSON_UNQUOTE(JSON_EXTRACT(r.result_json,'$.id')) AS UNSIGNED)=w.id
SET r.result_json=JSON_SET(r.result_json,'$.ruleSnapshot',CAST(w.rule_snapshot AS CHAR))
WHERE r.request_type='WITHDRAWAL' AND JSON_CONTAINS_PATH(r.result_json,'one','$.ruleSnapshot');
UPDATE compute_level SET description=REPLACE(REPLACE(REPLACE(description,'CREDITS','TOKEN'),'Credits','Token'),'credits','token') WHERE LOWER(description) LIKE '%credits%';
COMMIT;
END$$
CALL token_replay_snapshots_20261014()$$
DROP PROCEDURE token_replay_snapshots_20261014$$
DELIMITER ;
ALTER TABLE compute_exchange_rate COMMENT='平台Token与美元固定一比一，外币使用最近成功行情及刷新状态';

-- ===== final-i18n.sql =====
-- 当前展示词条纠正与已退出业务词条清理，固定键集合。
USE token;
SET NAMES utf8mb4;
DELIMITER $$
DROP PROCEDURE IF EXISTS token_final_i18n_20261014$$
CREATE PROCEDURE token_final_i18n_20261014()
BEGIN
IF BINARY DATABASE()<>BINARY 'token' OR (SELECT COUNT(*) FROM information_schema.schemata WHERE BINARY schema_name=BINARY 'token')<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='最终词条清理只允许token库'; END IF;
START TRANSACTION;
UPDATE translates SET tvalue=CONVERT(0x5768656e20656e61626c65642c2077696e6e696e672074686973207072697a65206175746f6d61746963616c6c7920616464732069747320636f6e666967757265642072657761726420746f20746865206561726e696e67732077616c6c65742e204f74686572776973652c206d616e75616c20726564656d7074696f6e2072656d61696e7320617661696c61626c652e20546869732073657474696e672061666665637473206e65772077696e73206f6e6c7920616e6420646f6573206e6f74206368616e67652070726576696f757320726564656d7074696f6e207265636f7264732e USING utf8mb4),action_time=UTC_TIMESTAMP() WHERE type=5 AND BINARY lang=BINARY CONVERT(0x656e USING utf8mb4) AND BINARY tkey=BINARY CONVERT(0x61646d696e2e636f6d707574652e61637469766974795633322e6175746f43726564697448656c70 USING utf8mb4);
UPDATE translates SET tvalue=CONVERT(0x3120546f6b656e203d2031205553442e20446973706c61792076616c756174696f6e73206e65766572206368616e6765206f726967696e616c2063757272656e63792077616c6c6574732c2062616c616e6365732c206465706f736974732c207769746864726177616c73206f7220736574746c656d656e74732e204e6f6e7a65726f2061737365747320776974686f7574206120726174652072656d61696e20756e7072696365642c20616e6420746f74616c7320696e636c756465206f6e6c7920707269636564206173736574732e2046726f7a656e2062616c616e63657320617265206578636c756465642e USING utf8mb4),action_time=UTC_TIMESTAMP() WHERE type=5 AND BINARY lang=BINARY CONVERT(0x656e USING utf8mb4) AND BINARY tkey=BINARY CONVERT(0x61646d696e2e636f6d707574652e75692e76616c756174696f6e48696e74 USING utf8mb4);
UPDATE translates SET tvalue=CONVERT(0x496e73756666696369656e742073657276696365206665652062616c616e6365 USING utf8mb4),action_time=UTC_TIMESTAMP() WHERE type=5 AND BINARY lang=BINARY CONVERT(0x656e USING utf8mb4) AND BINARY tkey=BINARY CONVERT(0x61646d696e2e636f6d707574652e7634394d65726368616e742e6465706f736974436f6c6c656374696f6e2e726561736f6e2e534552564943455f4645455f50454e44494e47 USING utf8mb4);
UPDATE translates SET tvalue=CONVERT(0x52656672657368206d65726368616e7420746f6b656e2061646472657373 USING utf8mb4),action_time=UTC_TIMESTAMP() WHERE type=5 AND BINARY lang=BINARY CONVERT(0x656e USING utf8mb4) AND BINARY tkey=BINARY CONVERT(0x61646d696e2e6c6f672e747970652e6d65726368616e74546f6b656e4164647265737352656672657368 USING utf8mb4);
UPDATE translates SET tvalue=CONVERT(0x56696577206d65726368616e7420746f6b656e USING utf8mb4),action_time=UTC_TIMESTAMP() WHERE type=5 AND BINARY lang=BINARY CONVERT(0x656e USING utf8mb4) AND BINARY tkey=BINARY CONVERT(0x61646d696e2e7065726d697373696f6e2e7632332e6d65726368616e7446656573 USING utf8mb4);
UPDATE translates SET tvalue=CONVERT(0x3120546f6b656e203d203120555344e38082e6ada4e5a484e4bb85e8aebee7bdaee5b195e7a4bae4bcb0e580bcefbc8ce4b88de694b9e58f98e58e9fe5b881e992b1e58c85e38081e4bd99e9a29de38081e58585e580bce38081e68f90e6acbee68896e7bb93e7ae97e98791e9a29de38082e69caae9858de7bdaee4bcb0e580bce79a84e99d9ee99bb6e8b584e4baa7e4bc9ae6a087e8aeb0e4b8bae69caae8aea1e4bbb7efbc8ce680bbe9a29de4bb85e8a1a8e7a4bae5b7b2e8aea1e4bbb7e983a8e58886efbc9be586bbe7bb93e4bd99e9a29de4b88de8aea1e585a5e680bbe8b584e4baa7e38082 USING utf8mb4),action_time=UTC_TIMESTAMP() WHERE type=5 AND BINARY lang=BINARY CONVERT(0x7a68 USING utf8mb4) AND BINARY tkey=BINARY CONVERT(0x61646d696e2e636f6d707574652e75692e76616c756174696f6e48696e74 USING utf8mb4);
UPDATE translates SET tvalue=CONVERT(0x3120546f6b656e203d20312055534420c2b72046726f7a656e2066756e6473206578636c75646564 USING utf8mb4),action_time=UTC_TIMESTAMP() WHERE type=7 AND BINARY lang=BINARY CONVERT(0x656e USING utf8mb4) AND BINARY tkey=BINARY CONVERT(0x757365722e75692e636f6d6d6f6e2e61737365744261736973 USING utf8mb4);
UPDATE translates SET tvalue=CONVERT(0x3120546f6b656e203d20312055534420c2b720e680bbe8b584e4baa7e4b88de58c85e590abe586bbe7bb93e98791e9a29d USING utf8mb4),action_time=UTC_TIMESTAMP() WHERE type=7 AND BINARY lang=BINARY CONVERT(0x7a68 USING utf8mb4) AND BINARY tkey=BINARY CONVERT(0x757365722e75692e636f6d6d6f6e2e61737365744261736973 USING utf8mb4);
UPDATE translates SET tvalue=REPLACE(REPLACE(tvalue,'Crédits','Token'),'crédits','Token'),action_time=UTC_TIMESTAMP() WHERE type=7 AND lang='fr' AND tkey IN ('user.v16.token.validMemberRule','user.v16Content.expectedIncomeBasis','user.v18Product.amountLimit','user.v18Product.amountRemaining','user.v21Rewards.depositCap','user.v21Rewards.effectiveMember','user.v21Rewards.vipBalance');
DELETE FROM translates WHERE type=7 AND tkey IN ('user.ui2.team.unpriced','user.v13.smallWithdrawalValue','user.v35Ledger.fact.accountingAmount');
DELETE FROM translates WHERE type=5 AND tkey IN (
  'admin.compute.enum.SUBSCRIPTION',
  'admin.compute.field.subscriptionPrice',
  'admin.compute.field.subscriptionRank',
  'admin.compute.hint.v10.subscriptionPrice',
  'admin.compute.hint.v10.subscriptionRank',
  'admin.compute.v10.subscriptionValidation',
  'admin.compute.v13.subscriptionEnabled',
  'admin.compute.v13.subscriptionHelp',
  'admin.compute.v9.subscriptions'
);
DELETE FROM translates WHERE type=7 AND tkey IN (
  'user.product.subscription',
  'user.reward.legacyDeposit',
  'user.reward.legacyRegistration',
  'user.ui.account.noSubscription',
  'user.ui.products.confirmSubscription',
  'user.v10.subscription.credit',
  'user.v10.subscription.current',
  'user.v10.subscription.endsAt',
  'user.v10.subscription.paid',
  'user.v10.subscription.price',
  'user.v10.subscription.quoteExpired',
  'user.v10.subscription.quoting',
  'user.v10.subscription.refresh',
  'user.v10.subscription.title',
  'user.v10.subscription.upgrade',
  'user.v10.subscription.upgradeTerms',
  'user.v44.subscription.closed',
  'user.v44.subscription.replaced',
  'user.v8.auto.activeLimit',
  'user.v8.auto.allocated',
  'user.v8.auto.allocationNotice',
  'user.v8.auto.allTokens',
  'user.v8.auto.backgroundAllowed',
  'user.v8.auto.catalogIntro',
  'user.v8.auto.clickConfirm',
  'user.v8.auto.clickRate',
  'user.v8.auto.cooldown',
  'user.v8.auto.cpuProducts',
  'user.v8.auto.cycle.KEEP',
  'user.v8.auto.cycle.RESET',
  'user.v8.auto.cyclePolicy',
  'user.v8.auto.execution.DELAYED',
  'user.v8.auto.execution.INSTANT',
  'user.v8.auto.executionMode',
  'user.v8.auto.expectedIncome',
  'user.v8.auto.fullAmountHint',
  'user.v8.auto.incomeMode',
  'user.v8.auto.intro',
  'user.v8.auto.minutes',
  'user.v8.auto.mode.CLICK',
  'user.v8.auto.mode.DAILY',
  'user.v8.auto.modelsRequired',
  'user.v8.auto.nextSettlement',
  'user.v8.auto.progressAmount',
  'user.v8.auto.progressBasis',
  'user.v8.auto.resultCountdown',
  'user.v8.auto.resultReady',
  'user.v8.auto.resultTime',
  'user.v8.auto.running',
  'user.v8.auto.scheduled',
  'user.v8.auto.seconds',
  'user.v8.auto.tokenInsufficient',
  'user.v8.auto.validity'
);
UPDATE system_setting SET setting_value=LOWER(UUID()),revision=revision+1,updated_at=UTC_TIMESTAMP(6) WHERE setting_key='i18n.translations.revision';
COMMIT;
END$$
CALL token_final_i18n_20261014()$$
DROP PROCEDURE token_final_i18n_20261014$$
DELIMITER ;
