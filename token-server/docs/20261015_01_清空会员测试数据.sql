-- 2026-10-15：仅本次已明确授权的 Token 测试会员全量重置。
-- 已审计首轮会员911人、最大UID1026、系统个人消息1304条；0会员时严格无业务写入。
-- 执行前：源码/数据库分别加密备份并隔离恢复验证；暂停本实例HTTP、调度、回调及外部写入。
-- 必须使用独立连接、首错退出且断开连接回滚；禁止mysql --force，禁止跳过失败语句继续COMMIT。
-- 仅DML，不关闭外键、不TRUNCATE、不重置自增、不新增生产存储过程或依赖CHECK。
-- 收款永久归属/客户身份/代次/事件/游标和邀请码占位保留；新UID不得复用旧UID。
-- 恢复回调前须运行包含removedCustomer守卫的新服务端，旧JAR不会因执行SQL自动获得代码修补。
-- 隔离恢复演练仅替换下面USE与expected_database两个库名，不改其他逻辑。
USE token;
SET NAMES utf8mb4;
SET time_zone = '+00:00';
SET @token_cleanup_expected_database = 'token';
SET @token_cleanup_at = UTC_TIMESTAMP(6);
SET @token_cleanup_expected_member_count = 911;
SET @token_cleanup_expected_member_max = 1026;

DROP TEMPORARY TABLE IF EXISTS token_member_cleanup_guard;
DROP TEMPORARY TABLE IF EXISTS token_member_cleanup_members;
DROP TEMPORARY TABLE IF EXISTS token_member_cleanup_generated_content;
DROP TEMPORARY TABLE IF EXISTS token_member_cleanup_content_tasks;
DROP TEMPORARY TABLE IF EXISTS token_member_cleanup_deposits;
DROP TEMPORARY TABLE IF EXISTS token_member_cleanup_withdrawals;
DROP TEMPORARY TABLE IF EXISTS token_member_cleanup_counts;
DROP TEMPORARY TABLE IF EXISTS token_member_cleanup_changes;

CREATE TEMPORARY TABLE token_member_cleanup_guard (
  assertion varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY
) ENGINE=InnoDB;

INSERT INTO token_member_cleanup_guard(assertion) VALUES
  ('database_identity'),
  ('schema_exactly_88_innodb_tables'),
  ('no_unreviewed_triggers'),
  ('relation_singleton_present'),
  ('member_snapshot_911_max_1026_or_empty'),
  ('uid_highwater_valid'),
  ('every_current_invite_permanently_reserved'),
  ('generated_messages_exactly_1304'),
  ('all_message_delivery_sources_verified'),
  ('payouts_only_terminal_or_reviewed_rejection'),
  ('member_snapshot_still_unchanged'),
  ('member_business_empty_after_cleanup'),
  ('content_sources_and_counters_clean'),
  ('content_task_sources_clean'),
  ('content_item_sources_clean'),
  ('retired_invite_reservations_clean'),
  ('uid_highwater_not_decreased'),
  ('relation_increment_exact');

-- 断言失败以主键重复错误中止：database_identity
INSERT INTO token_member_cleanup_guard(assertion)
SELECT 'database_identity' WHERE COALESCE((BINARY DATABASE()=BINARY @token_cleanup_expected_database
  AND (SELECT COUNT(*) FROM information_schema.schemata WHERE BINARY schema_name=BINARY @token_cleanup_expected_database)=1),0)=0;

-- 断言失败以主键重复错误中止：schema_exactly_88_innodb_tables
INSERT INTO token_member_cleanup_guard(assertion)
SELECT 'schema_exactly_88_innodb_tables' WHERE COALESCE(((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE')=88
  AND (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' AND engine='InnoDB'
    AND table_name IN ('ad_master','ad_record','admin_login_session','admin_security','compute_activity','compute_activity_attempt_grant','compute_activity_invite_grant','compute_activity_participation','compute_activity_prize','compute_address','compute_address_guard','compute_admin_member_scope','compute_admin_table_columns','compute_auto_contract','compute_auto_settlement','compute_checkin','compute_company_file','compute_content','compute_content_popup_seen','compute_content_recipient','compute_content_translation_item','compute_content_translation_task','compute_currency','compute_currency_asset','compute_currency_network','compute_deposit','compute_exchange_rate','compute_exchange_refresh','compute_fee_rule','compute_finance_snapshot','compute_finance_sound_config','compute_job','compute_job_run','compute_ledger','compute_level','compute_mcag_address','compute_mcag_address_current','compute_mcag_address_generation','compute_mcag_channel','compute_mcag_collection_batch','compute_mcag_cursor','compute_mcag_customer_identity','compute_mcag_deposit_order','compute_mcag_event','compute_mcag_payout','compute_member','compute_member_group','compute_member_invite_code','compute_member_login_ticket','compute_member_operation','compute_member_session','compute_merchant_snapshot','compute_model','compute_model_price','compute_order','compute_order_settlement','compute_product','compute_product_quote','compute_provider','compute_recovery_request','compute_relation_state','compute_request','compute_reward_award','compute_reward_event','compute_system_message_delivery','compute_system_message_template','compute_transfer','compute_transfer_rule','compute_translation_form','compute_wallet','compute_wheel_adjustment','compute_wheel_member','compute_withdrawal','ip_whitelist','material_asset','memu','notification_automation_state','notification_channel_config','notification_event_delivery','notification_finance_event','notification_telegram_binding','notification_template','system_notification','system_setting','translate_language','translates','translation_completion_task','user_role'))=88),0)=0;

-- 断言失败以主键重复错误中止：no_unreviewed_triggers
INSERT INTO token_member_cleanup_guard(assertion)
SELECT 'no_unreviewed_triggers' WHERE COALESCE(((SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE())=0),0)=0;

CREATE TEMPORARY TABLE token_member_cleanup_members (id bigint NOT NULL PRIMARY KEY) ENGINE=InnoDB;
CREATE TEMPORARY TABLE token_member_cleanup_generated_content (id bigint NOT NULL PRIMARY KEY) ENGINE=InnoDB;
CREATE TEMPORARY TABLE token_member_cleanup_content_tasks (id bigint NOT NULL PRIMARY KEY) ENGINE=InnoDB;
CREATE TEMPORARY TABLE token_member_cleanup_deposits (id bigint NOT NULL PRIMARY KEY) ENGINE=InnoDB;
CREATE TEMPORARY TABLE token_member_cleanup_withdrawals (id bigint NOT NULL PRIMARY KEY) ENGINE=InnoDB;
CREATE TEMPORARY TABLE token_member_cleanup_counts (
  phase varchar(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  table_name varchar(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  row_count bigint NOT NULL,
  PRIMARY KEY(phase,table_name)
) ENGINE=InnoDB;
CREATE TEMPORARY TABLE token_member_cleanup_changes (
  operation varchar(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  affected_rows bigint NOT NULL
) ENGINE=InnoDB;

SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;
START TRANSACTION;


-- 断言失败以主键重复错误中止：relation_singleton_present
INSERT INTO token_member_cleanup_guard(assertion)
SELECT 'relation_singleton_present' WHERE COALESCE(((SELECT COUNT(*) FROM compute_relation_state WHERE singleton_id=1)=1),0)=0;

SELECT version INTO @token_cleanup_relation_before FROM compute_relation_state WHERE singleton_id=1 FOR UPDATE;
INSERT INTO token_member_cleanup_members(id) SELECT id FROM compute_member;
SELECT COUNT(*),COALESCE(MAX(id),0) INTO @token_cleanup_member_count,@token_cleanup_member_max FROM token_member_cleanup_members;
SET @token_cleanup_run = IF(@token_cleanup_member_count>0,1,0);


-- 断言失败以主键重复错误中止：member_snapshot_911_max_1026_or_empty
INSERT INTO token_member_cleanup_guard(assertion)
SELECT 'member_snapshot_911_max_1026_or_empty' WHERE COALESCE((@token_cleanup_member_count=0
  OR (@token_cleanup_member_count=@token_cleanup_expected_member_count AND @token_cleanup_member_max=@token_cleanup_expected_member_max)),0)=0;

-- 断言失败以主键重复错误中止：uid_highwater_valid
INSERT INTO token_member_cleanup_guard(assertion)
SELECT 'uid_highwater_valid' WHERE COALESCE(((SELECT COUNT(*) FROM system_setting WHERE setting_key='memberUidLastAssigned'
  AND setting_value REGEXP '^[0-9]{1,16}$' AND CAST(setting_value AS UNSIGNED)<=9007199254740991)=1),0)=0;

SELECT CAST(setting_value AS UNSIGNED) INTO @token_cleanup_uid_before FROM system_setting WHERE setting_key='memberUidLastAssigned' FOR UPDATE;
INSERT INTO token_member_cleanup_generated_content(id)
SELECT DISTINCT c.id FROM compute_system_message_delivery d JOIN compute_content c ON c.id=d.content_id
WHERE @token_cleanup_run=1 AND c.kind='MESSAGE' AND c.created_by=0;
SELECT COUNT(*) INTO @token_cleanup_generated_count FROM token_member_cleanup_generated_content;


-- 断言失败以主键重复错误中止：generated_messages_exactly_1304
INSERT INTO token_member_cleanup_guard(assertion)
SELECT 'generated_messages_exactly_1304' WHERE COALESCE((@token_cleanup_run=0 OR @token_cleanup_generated_count=1304),0)=0;

-- 断言失败以主键重复错误中止：all_message_delivery_sources_verified
INSERT INTO token_member_cleanup_guard(assertion)
SELECT 'all_message_delivery_sources_verified' WHERE COALESCE((@token_cleanup_run=0 OR (
  (SELECT COUNT(*) FROM compute_system_message_delivery)=1304
  AND (SELECT COUNT(*) FROM compute_system_message_delivery d LEFT JOIN compute_content c ON c.id=d.content_id
    WHERE c.id IS NULL OR c.kind<>'MESSAGE' OR c.created_by<>0)=0)),0)=0;

-- 断言失败以主键重复错误中止：every_current_invite_permanently_reserved
INSERT INTO token_member_cleanup_guard(assertion)
SELECT 'every_current_invite_permanently_reserved' WHERE COALESCE((@token_cleanup_run=0 OR (SELECT COUNT(*) FROM compute_member m LEFT JOIN compute_member_invite_code c
  ON c.code=m.invite_code AND c.member_id=m.id WHERE c.code IS NULL OR c.retired_at IS NOT NULL)=0),0)=0;

-- 断言失败以主键重复错误中止：payouts_only_terminal_or_reviewed_rejection
INSERT INTO token_member_cleanup_guard(assertion)
SELECT 'payouts_only_terminal_or_reviewed_rejection' WHERE COALESCE((@token_cleanup_run=0 OR (SELECT COUNT(*) FROM compute_mcag_payout
  WHERE NOT (state IN ('SUCCEEDED','RELEASED') OR (state='REVIEW' AND reason='GATEWAY_UNAVAILABLE'
    AND submission_state='REJECTED' AND COALESCE(payout_id,'')='' AND COALESCE(remote_status,'')='' AND resource_version=0)))=0),0)=0;

INSERT INTO token_member_cleanup_content_tasks(id)
SELECT t.id FROM compute_content_translation_task t JOIN token_member_cleanup_generated_content c ON c.id=t.content_id
WHERE t.resource_type='CONTENT';
INSERT INTO token_member_cleanup_deposits(id) SELECT id FROM compute_deposit WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_withdrawals(id) SELECT id FROM compute_withdrawal WHERE @token_cleanup_run=1;

INSERT INTO token_member_cleanup_counts(phase,table_name,row_count)
SELECT 'before','ad_master',COUNT(*) FROM `ad_master`
UNION ALL
SELECT 'before','ad_record',COUNT(*) FROM `ad_record`
UNION ALL
SELECT 'before','admin_login_session',COUNT(*) FROM `admin_login_session`
UNION ALL
SELECT 'before','admin_security',COUNT(*) FROM `admin_security`
UNION ALL
SELECT 'before','compute_activity',COUNT(*) FROM `compute_activity`
UNION ALL
SELECT 'before','compute_activity_attempt_grant',COUNT(*) FROM `compute_activity_attempt_grant`
UNION ALL
SELECT 'before','compute_activity_invite_grant',COUNT(*) FROM `compute_activity_invite_grant`
UNION ALL
SELECT 'before','compute_activity_participation',COUNT(*) FROM `compute_activity_participation`
UNION ALL
SELECT 'before','compute_activity_prize',COUNT(*) FROM `compute_activity_prize`
UNION ALL
SELECT 'before','compute_address',COUNT(*) FROM `compute_address`
UNION ALL
SELECT 'before','compute_address_guard',COUNT(*) FROM `compute_address_guard`
UNION ALL
SELECT 'before','compute_admin_member_scope',COUNT(*) FROM `compute_admin_member_scope`
UNION ALL
SELECT 'before','compute_admin_table_columns',COUNT(*) FROM `compute_admin_table_columns`
UNION ALL
SELECT 'before','compute_auto_contract',COUNT(*) FROM `compute_auto_contract`
UNION ALL
SELECT 'before','compute_auto_settlement',COUNT(*) FROM `compute_auto_settlement`
UNION ALL
SELECT 'before','compute_checkin',COUNT(*) FROM `compute_checkin`
UNION ALL
SELECT 'before','compute_company_file',COUNT(*) FROM `compute_company_file`
UNION ALL
SELECT 'before','compute_content',COUNT(*) FROM `compute_content`
UNION ALL
SELECT 'before','compute_content_popup_seen',COUNT(*) FROM `compute_content_popup_seen`
UNION ALL
SELECT 'before','compute_content_recipient',COUNT(*) FROM `compute_content_recipient`
UNION ALL
SELECT 'before','compute_content_translation_item',COUNT(*) FROM `compute_content_translation_item`
UNION ALL
SELECT 'before','compute_content_translation_task',COUNT(*) FROM `compute_content_translation_task`
UNION ALL
SELECT 'before','compute_currency',COUNT(*) FROM `compute_currency`
UNION ALL
SELECT 'before','compute_currency_asset',COUNT(*) FROM `compute_currency_asset`
UNION ALL
SELECT 'before','compute_currency_network',COUNT(*) FROM `compute_currency_network`
UNION ALL
SELECT 'before','compute_deposit',COUNT(*) FROM `compute_deposit`
UNION ALL
SELECT 'before','compute_exchange_rate',COUNT(*) FROM `compute_exchange_rate`
UNION ALL
SELECT 'before','compute_exchange_refresh',COUNT(*) FROM `compute_exchange_refresh`
UNION ALL
SELECT 'before','compute_fee_rule',COUNT(*) FROM `compute_fee_rule`
UNION ALL
SELECT 'before','compute_finance_snapshot',COUNT(*) FROM `compute_finance_snapshot`
UNION ALL
SELECT 'before','compute_finance_sound_config',COUNT(*) FROM `compute_finance_sound_config`
UNION ALL
SELECT 'before','compute_job',COUNT(*) FROM `compute_job`
UNION ALL
SELECT 'before','compute_job_run',COUNT(*) FROM `compute_job_run`
UNION ALL
SELECT 'before','compute_ledger',COUNT(*) FROM `compute_ledger`
UNION ALL
SELECT 'before','compute_level',COUNT(*) FROM `compute_level`
UNION ALL
SELECT 'before','compute_mcag_address',COUNT(*) FROM `compute_mcag_address`
UNION ALL
SELECT 'before','compute_mcag_address_current',COUNT(*) FROM `compute_mcag_address_current`
UNION ALL
SELECT 'before','compute_mcag_address_generation',COUNT(*) FROM `compute_mcag_address_generation`
UNION ALL
SELECT 'before','compute_mcag_channel',COUNT(*) FROM `compute_mcag_channel`
UNION ALL
SELECT 'before','compute_mcag_collection_batch',COUNT(*) FROM `compute_mcag_collection_batch`
UNION ALL
SELECT 'before','compute_mcag_cursor',COUNT(*) FROM `compute_mcag_cursor`
UNION ALL
SELECT 'before','compute_mcag_customer_identity',COUNT(*) FROM `compute_mcag_customer_identity`
UNION ALL
SELECT 'before','compute_mcag_deposit_order',COUNT(*) FROM `compute_mcag_deposit_order`
UNION ALL
SELECT 'before','compute_mcag_event',COUNT(*) FROM `compute_mcag_event`
UNION ALL
SELECT 'before','compute_mcag_payout',COUNT(*) FROM `compute_mcag_payout`
UNION ALL
SELECT 'before','compute_member',COUNT(*) FROM `compute_member`
UNION ALL
SELECT 'before','compute_member_group',COUNT(*) FROM `compute_member_group`
UNION ALL
SELECT 'before','compute_member_invite_code',COUNT(*) FROM `compute_member_invite_code`
UNION ALL
SELECT 'before','compute_member_login_ticket',COUNT(*) FROM `compute_member_login_ticket`
UNION ALL
SELECT 'before','compute_member_operation',COUNT(*) FROM `compute_member_operation`
UNION ALL
SELECT 'before','compute_member_session',COUNT(*) FROM `compute_member_session`
UNION ALL
SELECT 'before','compute_merchant_snapshot',COUNT(*) FROM `compute_merchant_snapshot`
UNION ALL
SELECT 'before','compute_model',COUNT(*) FROM `compute_model`
UNION ALL
SELECT 'before','compute_model_price',COUNT(*) FROM `compute_model_price`
UNION ALL
SELECT 'before','compute_order',COUNT(*) FROM `compute_order`
UNION ALL
SELECT 'before','compute_order_settlement',COUNT(*) FROM `compute_order_settlement`
UNION ALL
SELECT 'before','compute_product',COUNT(*) FROM `compute_product`
UNION ALL
SELECT 'before','compute_product_quote',COUNT(*) FROM `compute_product_quote`
UNION ALL
SELECT 'before','compute_provider',COUNT(*) FROM `compute_provider`
UNION ALL
SELECT 'before','compute_recovery_request',COUNT(*) FROM `compute_recovery_request`
UNION ALL
SELECT 'before','compute_relation_state',COUNT(*) FROM `compute_relation_state`
UNION ALL
SELECT 'before','compute_request',COUNT(*) FROM `compute_request`
UNION ALL
SELECT 'before','compute_reward_award',COUNT(*) FROM `compute_reward_award`
UNION ALL
SELECT 'before','compute_reward_event',COUNT(*) FROM `compute_reward_event`
UNION ALL
SELECT 'before','compute_system_message_delivery',COUNT(*) FROM `compute_system_message_delivery`
UNION ALL
SELECT 'before','compute_system_message_template',COUNT(*) FROM `compute_system_message_template`
UNION ALL
SELECT 'before','compute_transfer',COUNT(*) FROM `compute_transfer`
UNION ALL
SELECT 'before','compute_transfer_rule',COUNT(*) FROM `compute_transfer_rule`
UNION ALL
SELECT 'before','compute_translation_form',COUNT(*) FROM `compute_translation_form`
UNION ALL
SELECT 'before','compute_wallet',COUNT(*) FROM `compute_wallet`
UNION ALL
SELECT 'before','compute_wheel_adjustment',COUNT(*) FROM `compute_wheel_adjustment`
UNION ALL
SELECT 'before','compute_wheel_member',COUNT(*) FROM `compute_wheel_member`
UNION ALL
SELECT 'before','compute_withdrawal',COUNT(*) FROM `compute_withdrawal`
UNION ALL
SELECT 'before','ip_whitelist',COUNT(*) FROM `ip_whitelist`
UNION ALL
SELECT 'before','material_asset',COUNT(*) FROM `material_asset`
UNION ALL
SELECT 'before','memu',COUNT(*) FROM `memu`
UNION ALL
SELECT 'before','notification_automation_state',COUNT(*) FROM `notification_automation_state`
UNION ALL
SELECT 'before','notification_channel_config',COUNT(*) FROM `notification_channel_config`
UNION ALL
SELECT 'before','notification_event_delivery',COUNT(*) FROM `notification_event_delivery`
UNION ALL
SELECT 'before','notification_finance_event',COUNT(*) FROM `notification_finance_event`
UNION ALL
SELECT 'before','notification_telegram_binding',COUNT(*) FROM `notification_telegram_binding`
UNION ALL
SELECT 'before','notification_template',COUNT(*) FROM `notification_template`
UNION ALL
SELECT 'before','system_notification',COUNT(*) FROM `system_notification`
UNION ALL
SELECT 'before','system_setting',COUNT(*) FROM `system_setting`
UNION ALL
SELECT 'before','translate_language',COUNT(*) FROM `translate_language`
UNION ALL
SELECT 'before','translates',COUNT(*) FROM `translates`
UNION ALL
SELECT 'before','translation_completion_task',COUNT(*) FROM `translation_completion_task`
UNION ALL
SELECT 'before','user_role',COUNT(*) FROM `user_role`;
SELECT JSON_OBJECT('phase','before','database',DATABASE(),'members',@token_cleanup_member_count,'maxMemberId',@token_cleanup_member_max,'generatedMessages',@token_cleanup_generated_count,'uidHighwater',@token_cleanup_uid_before,'relationVersion',@token_cleanup_relation_before,'tables',JSON_OBJECTAGG(table_name,row_count)) AS token_member_cleanup_before FROM token_member_cleanup_counts WHERE phase='before';

-- 必要的范围/身份保护；不改变管理员账号、角色和任何业务配置值。
UPDATE ad_master a JOIN compute_admin_member_scope s ON s.admin_uid=a.uid
SET a.scope_configured_at=@token_cleanup_at WHERE @token_cleanup_run=1 AND a.scope_configured_at IS NULL;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('ad_master.scope_configured_at',ROW_COUNT());
UPDATE system_setting SET setting_value=CAST(@token_cleanup_member_max AS CHAR),revision=revision+1,updated_at=@token_cleanup_at
WHERE setting_key='memberUidLastAssigned' AND @token_cleanup_run=1 AND CAST(setting_value AS UNSIGNED)<@token_cleanup_member_max;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('system_setting.memberUidLastAssigned',ROW_COUNT());
UPDATE compute_member_invite_code c JOIN token_member_cleanup_members m ON m.id=c.member_id
SET c.retired_at=@token_cleanup_at WHERE c.retired_at IS NULL;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('compute_member_invite_code.retired_at',ROW_COUNT());

-- 先清自动生成个人消息的明确CONTENT翻译任务；不能误伤同号产品/等级/表单。
DELETE i FROM compute_content_translation_item i JOIN token_member_cleanup_content_tasks t ON t.id=i.task_id;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_content_translation_item',ROW_COUNT());
DELETE t FROM compute_content_translation_task t JOIN token_member_cleanup_content_tasks c ON c.id=t.id;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_content_translation_task',ROW_COUNT());

-- 只清已删除充提事件的自动投递防重；能源/渠道/管理员通知不动。
DELETE n FROM notification_event_delivery n JOIN token_member_cleanup_deposits d
  ON n.source_key COLLATE utf8mb4_bin=CAST(d.id AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_bin
WHERE n.event_code='largeDeposit';
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.notification_event_delivery.deposit',ROW_COUNT());
DELETE n FROM notification_event_delivery n JOIN token_member_cleanup_withdrawals w
  ON n.source_key COLLATE utf8mb4_bin=CAST(w.id AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_bin
WHERE n.event_code IN ('largeWithdrawal','withdrawalOverdue');
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.notification_event_delivery.withdrawal',ROW_COUNT());

-- 固定整表白名单：本次全量会员业务（含历史孤立业务行），保留所有自增种子。
DELETE FROM `compute_activity_attempt_grant` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_activity_attempt_grant',ROW_COUNT());
DELETE FROM `compute_activity_invite_grant` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_activity_invite_grant',ROW_COUNT());
DELETE FROM `compute_activity_participation` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_activity_participation',ROW_COUNT());
DELETE FROM `compute_wheel_adjustment` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_wheel_adjustment',ROW_COUNT());
DELETE FROM `compute_wheel_member` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_wheel_member',ROW_COUNT());
DELETE FROM `compute_checkin` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_checkin',ROW_COUNT());
DELETE FROM `compute_content_popup_seen` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_content_popup_seen',ROW_COUNT());
DELETE FROM `compute_content_recipient` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_content_recipient',ROW_COUNT());
DELETE FROM `compute_system_message_delivery` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_system_message_delivery',ROW_COUNT());
DELETE FROM `compute_member_login_ticket` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_member_login_ticket',ROW_COUNT());
DELETE FROM `compute_member_operation` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_member_operation',ROW_COUNT());
DELETE FROM `compute_member_session` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_member_session',ROW_COUNT());
DELETE FROM `compute_recovery_request` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_recovery_request',ROW_COUNT());
DELETE FROM `compute_admin_member_scope` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_admin_member_scope',ROW_COUNT());
DELETE FROM `compute_product_quote` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_product_quote',ROW_COUNT());
DELETE FROM `compute_order_settlement` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_order_settlement',ROW_COUNT());
DELETE FROM `compute_order` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_order',ROW_COUNT());
DELETE FROM `compute_auto_settlement` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_auto_settlement',ROW_COUNT());
DELETE FROM `compute_auto_contract` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_auto_contract',ROW_COUNT());
DELETE FROM `compute_reward_award` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_reward_award',ROW_COUNT());
DELETE FROM `compute_reward_event` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_reward_event',ROW_COUNT());
DELETE FROM `compute_request` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_request',ROW_COUNT());
DELETE FROM `compute_transfer` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_transfer',ROW_COUNT());
DELETE FROM `compute_finance_snapshot` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_finance_snapshot',ROW_COUNT());
DELETE FROM `compute_ledger` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_ledger',ROW_COUNT());
DELETE FROM `compute_wallet` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_wallet',ROW_COUNT());
DELETE FROM `compute_mcag_address_current` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_mcag_address_current',ROW_COUNT());
DELETE FROM `compute_mcag_deposit_order` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_mcag_deposit_order',ROW_COUNT());
DELETE FROM `compute_mcag_collection_batch` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_mcag_collection_batch',ROW_COUNT());
DELETE FROM `compute_mcag_payout` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_mcag_payout',ROW_COUNT());
DELETE FROM `notification_finance_event` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.notification_finance_event',ROW_COUNT());
DELETE FROM `compute_deposit` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_deposit',ROW_COUNT());
DELETE FROM `compute_withdrawal` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_withdrawal',ROW_COUNT());
DELETE FROM `compute_address` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_address',ROW_COUNT());
DELETE FROM `compute_address_guard` WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_address_guard',ROW_COUNT());
DELETE c FROM compute_content c JOIN token_member_cleanup_generated_content g ON g.id=c.id;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_content.generated',ROW_COUNT());
UPDATE compute_content SET recipient_count=0,read_count=0,completed_count=0
WHERE @token_cleanup_run=1 AND (recipient_count<>0 OR read_count<>0 OR completed_count<>0);
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('compute_content.recipient_counts',ROW_COUNT());
UPDATE compute_activity SET used_stock=0,used_budget=0
WHERE @token_cleanup_run=1 AND (used_stock<>0 OR used_budget<>0);
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('compute_activity.used_counters',ROW_COUNT());
UPDATE compute_activity_prize SET used_stock=0 WHERE @token_cleanup_run=1 AND used_stock<>0;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('compute_activity_prize.used_stock',ROW_COUNT());

-- 断言失败以主键重复错误中止：member_snapshot_still_unchanged
INSERT INTO token_member_cleanup_guard(assertion)
SELECT 'member_snapshot_still_unchanged' WHERE COALESCE(((SELECT COUNT(*) FROM compute_member)=@token_cleanup_member_count
  AND (SELECT COALESCE(MAX(id),0) FROM compute_member)=@token_cleanup_member_max),0)=0;
UPDATE compute_member SET inviter_id=NULL WHERE @token_cleanup_run=1 AND inviter_id IS NOT NULL;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('compute_member.inviter_id',ROW_COUNT());
DELETE FROM compute_member WHERE @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('delete.compute_member',ROW_COUNT());
UPDATE compute_relation_state SET version=version+1 WHERE singleton_id=1 AND @token_cleanup_run=1;
INSERT INTO token_member_cleanup_changes(operation,affected_rows) VALUES('compute_relation_state.version',ROW_COUNT());
INSERT INTO token_member_cleanup_counts(phase,table_name,row_count)
SELECT 'after','ad_master',COUNT(*) FROM `ad_master`
UNION ALL
SELECT 'after','ad_record',COUNT(*) FROM `ad_record`
UNION ALL
SELECT 'after','admin_login_session',COUNT(*) FROM `admin_login_session`
UNION ALL
SELECT 'after','admin_security',COUNT(*) FROM `admin_security`
UNION ALL
SELECT 'after','compute_activity',COUNT(*) FROM `compute_activity`
UNION ALL
SELECT 'after','compute_activity_attempt_grant',COUNT(*) FROM `compute_activity_attempt_grant`
UNION ALL
SELECT 'after','compute_activity_invite_grant',COUNT(*) FROM `compute_activity_invite_grant`
UNION ALL
SELECT 'after','compute_activity_participation',COUNT(*) FROM `compute_activity_participation`
UNION ALL
SELECT 'after','compute_activity_prize',COUNT(*) FROM `compute_activity_prize`
UNION ALL
SELECT 'after','compute_address',COUNT(*) FROM `compute_address`
UNION ALL
SELECT 'after','compute_address_guard',COUNT(*) FROM `compute_address_guard`
UNION ALL
SELECT 'after','compute_admin_member_scope',COUNT(*) FROM `compute_admin_member_scope`
UNION ALL
SELECT 'after','compute_admin_table_columns',COUNT(*) FROM `compute_admin_table_columns`
UNION ALL
SELECT 'after','compute_auto_contract',COUNT(*) FROM `compute_auto_contract`
UNION ALL
SELECT 'after','compute_auto_settlement',COUNT(*) FROM `compute_auto_settlement`
UNION ALL
SELECT 'after','compute_checkin',COUNT(*) FROM `compute_checkin`
UNION ALL
SELECT 'after','compute_company_file',COUNT(*) FROM `compute_company_file`
UNION ALL
SELECT 'after','compute_content',COUNT(*) FROM `compute_content`
UNION ALL
SELECT 'after','compute_content_popup_seen',COUNT(*) FROM `compute_content_popup_seen`
UNION ALL
SELECT 'after','compute_content_recipient',COUNT(*) FROM `compute_content_recipient`
UNION ALL
SELECT 'after','compute_content_translation_item',COUNT(*) FROM `compute_content_translation_item`
UNION ALL
SELECT 'after','compute_content_translation_task',COUNT(*) FROM `compute_content_translation_task`
UNION ALL
SELECT 'after','compute_currency',COUNT(*) FROM `compute_currency`
UNION ALL
SELECT 'after','compute_currency_asset',COUNT(*) FROM `compute_currency_asset`
UNION ALL
SELECT 'after','compute_currency_network',COUNT(*) FROM `compute_currency_network`
UNION ALL
SELECT 'after','compute_deposit',COUNT(*) FROM `compute_deposit`
UNION ALL
SELECT 'after','compute_exchange_rate',COUNT(*) FROM `compute_exchange_rate`
UNION ALL
SELECT 'after','compute_exchange_refresh',COUNT(*) FROM `compute_exchange_refresh`
UNION ALL
SELECT 'after','compute_fee_rule',COUNT(*) FROM `compute_fee_rule`
UNION ALL
SELECT 'after','compute_finance_snapshot',COUNT(*) FROM `compute_finance_snapshot`
UNION ALL
SELECT 'after','compute_finance_sound_config',COUNT(*) FROM `compute_finance_sound_config`
UNION ALL
SELECT 'after','compute_job',COUNT(*) FROM `compute_job`
UNION ALL
SELECT 'after','compute_job_run',COUNT(*) FROM `compute_job_run`
UNION ALL
SELECT 'after','compute_ledger',COUNT(*) FROM `compute_ledger`
UNION ALL
SELECT 'after','compute_level',COUNT(*) FROM `compute_level`
UNION ALL
SELECT 'after','compute_mcag_address',COUNT(*) FROM `compute_mcag_address`
UNION ALL
SELECT 'after','compute_mcag_address_current',COUNT(*) FROM `compute_mcag_address_current`
UNION ALL
SELECT 'after','compute_mcag_address_generation',COUNT(*) FROM `compute_mcag_address_generation`
UNION ALL
SELECT 'after','compute_mcag_channel',COUNT(*) FROM `compute_mcag_channel`
UNION ALL
SELECT 'after','compute_mcag_collection_batch',COUNT(*) FROM `compute_mcag_collection_batch`
UNION ALL
SELECT 'after','compute_mcag_cursor',COUNT(*) FROM `compute_mcag_cursor`
UNION ALL
SELECT 'after','compute_mcag_customer_identity',COUNT(*) FROM `compute_mcag_customer_identity`
UNION ALL
SELECT 'after','compute_mcag_deposit_order',COUNT(*) FROM `compute_mcag_deposit_order`
UNION ALL
SELECT 'after','compute_mcag_event',COUNT(*) FROM `compute_mcag_event`
UNION ALL
SELECT 'after','compute_mcag_payout',COUNT(*) FROM `compute_mcag_payout`
UNION ALL
SELECT 'after','compute_member',COUNT(*) FROM `compute_member`
UNION ALL
SELECT 'after','compute_member_group',COUNT(*) FROM `compute_member_group`
UNION ALL
SELECT 'after','compute_member_invite_code',COUNT(*) FROM `compute_member_invite_code`
UNION ALL
SELECT 'after','compute_member_login_ticket',COUNT(*) FROM `compute_member_login_ticket`
UNION ALL
SELECT 'after','compute_member_operation',COUNT(*) FROM `compute_member_operation`
UNION ALL
SELECT 'after','compute_member_session',COUNT(*) FROM `compute_member_session`
UNION ALL
SELECT 'after','compute_merchant_snapshot',COUNT(*) FROM `compute_merchant_snapshot`
UNION ALL
SELECT 'after','compute_model',COUNT(*) FROM `compute_model`
UNION ALL
SELECT 'after','compute_model_price',COUNT(*) FROM `compute_model_price`
UNION ALL
SELECT 'after','compute_order',COUNT(*) FROM `compute_order`
UNION ALL
SELECT 'after','compute_order_settlement',COUNT(*) FROM `compute_order_settlement`
UNION ALL
SELECT 'after','compute_product',COUNT(*) FROM `compute_product`
UNION ALL
SELECT 'after','compute_product_quote',COUNT(*) FROM `compute_product_quote`
UNION ALL
SELECT 'after','compute_provider',COUNT(*) FROM `compute_provider`
UNION ALL
SELECT 'after','compute_recovery_request',COUNT(*) FROM `compute_recovery_request`
UNION ALL
SELECT 'after','compute_relation_state',COUNT(*) FROM `compute_relation_state`
UNION ALL
SELECT 'after','compute_request',COUNT(*) FROM `compute_request`
UNION ALL
SELECT 'after','compute_reward_award',COUNT(*) FROM `compute_reward_award`
UNION ALL
SELECT 'after','compute_reward_event',COUNT(*) FROM `compute_reward_event`
UNION ALL
SELECT 'after','compute_system_message_delivery',COUNT(*) FROM `compute_system_message_delivery`
UNION ALL
SELECT 'after','compute_system_message_template',COUNT(*) FROM `compute_system_message_template`
UNION ALL
SELECT 'after','compute_transfer',COUNT(*) FROM `compute_transfer`
UNION ALL
SELECT 'after','compute_transfer_rule',COUNT(*) FROM `compute_transfer_rule`
UNION ALL
SELECT 'after','compute_translation_form',COUNT(*) FROM `compute_translation_form`
UNION ALL
SELECT 'after','compute_wallet',COUNT(*) FROM `compute_wallet`
UNION ALL
SELECT 'after','compute_wheel_adjustment',COUNT(*) FROM `compute_wheel_adjustment`
UNION ALL
SELECT 'after','compute_wheel_member',COUNT(*) FROM `compute_wheel_member`
UNION ALL
SELECT 'after','compute_withdrawal',COUNT(*) FROM `compute_withdrawal`
UNION ALL
SELECT 'after','ip_whitelist',COUNT(*) FROM `ip_whitelist`
UNION ALL
SELECT 'after','material_asset',COUNT(*) FROM `material_asset`
UNION ALL
SELECT 'after','memu',COUNT(*) FROM `memu`
UNION ALL
SELECT 'after','notification_automation_state',COUNT(*) FROM `notification_automation_state`
UNION ALL
SELECT 'after','notification_channel_config',COUNT(*) FROM `notification_channel_config`
UNION ALL
SELECT 'after','notification_event_delivery',COUNT(*) FROM `notification_event_delivery`
UNION ALL
SELECT 'after','notification_finance_event',COUNT(*) FROM `notification_finance_event`
UNION ALL
SELECT 'after','notification_telegram_binding',COUNT(*) FROM `notification_telegram_binding`
UNION ALL
SELECT 'after','notification_template',COUNT(*) FROM `notification_template`
UNION ALL
SELECT 'after','system_notification',COUNT(*) FROM `system_notification`
UNION ALL
SELECT 'after','system_setting',COUNT(*) FROM `system_setting`
UNION ALL
SELECT 'after','translate_language',COUNT(*) FROM `translate_language`
UNION ALL
SELECT 'after','translates',COUNT(*) FROM `translates`
UNION ALL
SELECT 'after','translation_completion_task',COUNT(*) FROM `translation_completion_task`
UNION ALL
SELECT 'after','user_role',COUNT(*) FROM `user_role`;

-- 断言失败以主键重复错误中止：member_business_empty_after_cleanup
INSERT INTO token_member_cleanup_guard(assertion)
SELECT 'member_business_empty_after_cleanup' WHERE COALESCE((@token_cleanup_run=0 OR (SELECT COALESCE(SUM(row_count),0) FROM token_member_cleanup_counts
  WHERE phase='after' AND table_name IN ('compute_activity_attempt_grant','compute_activity_invite_grant','compute_activity_participation','compute_wheel_adjustment','compute_wheel_member','compute_checkin','compute_content_popup_seen','compute_content_recipient','compute_system_message_delivery','compute_member_login_ticket','compute_member_operation','compute_member_session','compute_recovery_request','compute_admin_member_scope','compute_product_quote','compute_order_settlement','compute_order','compute_auto_settlement','compute_auto_contract','compute_reward_award','compute_reward_event','compute_request','compute_transfer','compute_finance_snapshot','compute_ledger','compute_wallet','compute_mcag_address_current','compute_mcag_deposit_order','compute_mcag_collection_batch','compute_mcag_payout','notification_finance_event','compute_deposit','compute_withdrawal','compute_address','compute_address_guard','compute_member'))=0),0)=0;

-- 断言失败以主键重复错误中止：content_sources_and_counters_clean
INSERT INTO token_member_cleanup_guard(assertion)
SELECT 'content_sources_and_counters_clean' WHERE COALESCE((@token_cleanup_run=0 OR (
  (SELECT COUNT(*) FROM compute_content c JOIN token_member_cleanup_generated_content g ON g.id=c.id)=0
  AND (SELECT COUNT(*) FROM compute_content WHERE recipient_count<>0 OR read_count<>0 OR completed_count<>0)=0
  AND (SELECT COUNT(*) FROM compute_activity WHERE used_stock<>0 OR used_budget<>0)=0
  AND (SELECT COUNT(*) FROM compute_activity_prize WHERE used_stock<>0)=0)),0)=0;

-- 断言失败以主键重复错误中止：content_task_sources_clean
INSERT INTO token_member_cleanup_guard(assertion)
SELECT 'content_task_sources_clean' WHERE COALESCE((@token_cleanup_run=0 OR (SELECT COUNT(*) FROM compute_content_translation_task t
  JOIN token_member_cleanup_content_tasks c ON c.id=t.id)=0),0)=0;

-- 断言失败以主键重复错误中止：content_item_sources_clean
INSERT INTO token_member_cleanup_guard(assertion)
SELECT 'content_item_sources_clean' WHERE COALESCE((@token_cleanup_run=0 OR (SELECT COUNT(*) FROM compute_content_translation_item i
  JOIN token_member_cleanup_content_tasks c ON c.id=i.task_id)=0),0)=0;

-- 断言失败以主键重复错误中止：retired_invite_reservations_clean
INSERT INTO token_member_cleanup_guard(assertion)
SELECT 'retired_invite_reservations_clean' WHERE COALESCE((@token_cleanup_run=0 OR (SELECT COUNT(*) FROM compute_member_invite_code c
  JOIN token_member_cleanup_members m ON m.id=c.member_id WHERE c.retired_at IS NULL)=0),0)=0;

-- 断言失败以主键重复错误中止：uid_highwater_not_decreased
INSERT INTO token_member_cleanup_guard(assertion)
SELECT 'uid_highwater_not_decreased' WHERE COALESCE(((SELECT CAST(setting_value AS UNSIGNED) FROM system_setting WHERE setting_key='memberUidLastAssigned')
  >=GREATEST(@token_cleanup_uid_before,IF(@token_cleanup_run=1,@token_cleanup_expected_member_max,0))),0)=0;

-- 断言失败以主键重复错误中止：relation_increment_exact
INSERT INTO token_member_cleanup_guard(assertion)
SELECT 'relation_increment_exact' WHERE COALESCE(((SELECT version FROM compute_relation_state WHERE singleton_id=1)=@token_cleanup_relation_before+@token_cleanup_run),0)=0;
COMMIT;
SELECT JSON_OBJECT('phase','after','database',DATABASE(),'executed',@token_cleanup_run,'deletedMembers',@token_cleanup_member_count,'uidHighwater',(SELECT CAST(setting_value AS UNSIGNED) FROM system_setting WHERE setting_key='memberUidLastAssigned'),'relationVersion',(SELECT version FROM compute_relation_state WHERE singleton_id=1),'tables',JSON_OBJECTAGG(table_name,row_count)) AS token_member_cleanup_after FROM token_member_cleanup_counts WHERE phase='after';
SELECT JSON_OBJECTAGG(operation,affected_rows) AS token_member_cleanup_changes FROM token_member_cleanup_changes;
DROP TEMPORARY TABLE token_member_cleanup_changes;
DROP TEMPORARY TABLE token_member_cleanup_counts;
DROP TEMPORARY TABLE token_member_cleanup_withdrawals;
DROP TEMPORARY TABLE token_member_cleanup_deposits;
DROP TEMPORARY TABLE token_member_cleanup_content_tasks;
DROP TEMPORARY TABLE token_member_cleanup_generated_content;
DROP TEMPORARY TABLE token_member_cleanup_members;
DROP TEMPORARY TABLE token_member_cleanup_guard;
