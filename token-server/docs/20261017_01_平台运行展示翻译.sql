-- 2026-10-17 平台运行展示：8个新键的中英文，共16条词条。
-- 仅新增缺失项、补充空白正文；不覆盖非空人工译文，不删除旧词条，不修改业务配置或资金。
-- 请使用独立MySQL连接执行，遇首错停止并回滚；不可使用客户端--force继续执行。
-- 词条与i18n.translations.revision在同一事务更新，幂等重放不变更版本。
USE token;
SET NAMES utf8mb4 COLLATE utf8mb4_bin;
SET time_zone = '+00:00';
SET @token_runtime_i18n_old_isolation = @@SESSION.transaction_isolation;
SET SESSION TRANSACTION ISOLATION LEVEL SERIALIZABLE;

CREATE TEMPORARY TABLE token_runtime_i18n_guard (
  verified TINYINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO token_runtime_i18n_guard (verified) VALUES (1);
-- 通过主键冲突中止，不依赖MySQL 8.0.12中不强制执行的CHECK。
INSERT INTO token_runtime_i18n_guard (verified)
SELECT 1 WHERE NOT (BINARY DATABASE() = BINARY 'token');
INSERT INTO token_runtime_i18n_guard (verified)
SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM information_schema.tables
  WHERE BINARY table_schema = BINARY 'token' AND table_name IN ('translates', 'system_setting')
    AND engine = 'InnoDB' AND table_type = 'BASE TABLE') = 2);
INSERT INTO token_runtime_i18n_guard (verified)
SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM information_schema.columns
  WHERE BINARY table_schema = BINARY 'token' AND table_name = 'translates'
    AND column_name IN ('id', 'tkey', 'tvalue', 'lang', 'type', 'prop', 'title', 'is_del', 'action_time')) = 9);
INSERT INTO token_runtime_i18n_guard (verified)
SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM information_schema.columns
  WHERE BINARY table_schema = BINARY 'token' AND table_name = 'system_setting'
    AND column_name IN ('setting_key', 'setting_value', 'revision', 'updated_at')) = 4);
INSERT INTO token_runtime_i18n_guard (verified)
SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM information_schema.statistics
  WHERE BINARY table_schema = BINARY 'token' AND table_name = 'translates'
    AND index_name = 'key_lang' AND non_unique = 0
    AND ((seq_in_index = 1 AND column_name = 'tkey')
      OR (seq_in_index = 2 AND column_name = 'lang')
      OR (seq_in_index = 3 AND column_name = 'type'))) = 3);

CREATE TEMPORARY TABLE token_runtime_i18n_seed (
  template_type INT NOT NULL,
  tkey VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  lang VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  tvalue TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (template_type, tkey, lang)
) ENGINE=InnoDB;
INSERT INTO token_runtime_i18n_seed (template_type, tkey, lang, tvalue) VALUES
(5, 'admin.compute.runtimeDisplay.help', 'zh', '自动模式每2秒更新一次展示快照，配置按以下间隔同步。所有展示值受配置范围限制，不参与资产、收益或报表计算。'),
(5, 'admin.compute.runtimeDisplay.help', 'en', 'Automatic mode updates the display snapshot every 2 seconds. Configuration syncs at the interval below. All display values stay within the configured limits and do not affect assets, earnings or reports.'),
(5, 'admin.compute.runtimeDisplay.syncSeconds', 'zh', '配置同步间隔（秒）'),
(5, 'admin.compute.runtimeDisplay.syncSeconds', 'en', 'Configuration sync interval (seconds)'),
(7, 'user.vora.runtime.availableCapacity', 'zh', '可用算力'),
(7, 'user.vora.runtime.availableCapacity', 'en', 'Available capacity'),
(7, 'user.vora.runtime.load', 'zh', '算力负载'),
(7, 'user.vora.runtime.load', 'en', 'Compute load'),
(7, 'user.vora.runtime.nodeActivity', 'zh', '节点活跃率'),
(7, 'user.vora.runtime.nodeActivity', 'en', 'Node activity'),
(7, 'user.vora.runtime.pause', 'zh', '暂停动态展示'),
(7, 'user.vora.runtime.pause', 'en', 'Pause updates'),
(7, 'user.vora.runtime.poolOccupancy', 'zh', '运行池占用率'),
(7, 'user.vora.runtime.poolOccupancy', 'en', 'Pool occupancy'),
(7, 'user.vora.runtime.resume', 'zh', '继续动态展示'),
(7, 'user.vora.runtime.resume', 'en', 'Resume updates');
INSERT INTO token_runtime_i18n_guard (verified)
SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM token_runtime_i18n_seed) = 16);

START TRANSACTION;
-- 锁定翻译版本，避免与后台词条编辑并行时丢失缓存失效版本。
INSERT INTO token_runtime_i18n_guard (verified)
SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM token.system_setting
  WHERE BINARY setting_key = BINARY 'i18n.translations.revision' AND revision >= 0) = 1);
SELECT revision INTO @token_runtime_i18n_revision_before FROM token.system_setting
WHERE BINARY setting_key = BINARY 'i18n.translations.revision' FOR UPDATE;
-- 拒绝仅大小写/排序规则等价的异常键，不能把它们当作准确命中的词条。
INSERT INTO token_runtime_i18n_guard (verified)
SELECT 1 WHERE EXISTS (SELECT 1 FROM token.translates t JOIN token_runtime_i18n_seed s
  ON t.type = s.template_type
  AND CONVERT(t.tkey USING utf8mb4) COLLATE utf8mb4_general_ci = s.tkey COLLATE utf8mb4_general_ci
  AND CONVERT(t.lang USING utf8mb4) COLLATE utf8mb4_unicode_ci = s.lang COLLATE utf8mb4_unicode_ci
  WHERE NOT (BINARY t.tkey = BINARY s.tkey AND BINARY t.lang = BINARY s.lang));

INSERT INTO token.translates (tkey, lang, tvalue, type, prop, title, is_del, action_time)
SELECT s.tkey, s.lang, s.tvalue, s.template_type, 0, '平台运行展示', 0, UTC_TIMESTAMP()
FROM token_runtime_i18n_seed s
WHERE NOT EXISTS (SELECT 1 FROM token.translates t
  WHERE t.type = s.template_type
    AND CONVERT(t.tkey USING utf8mb4) COLLATE utf8mb4_bin = s.tkey
    AND CONVERT(t.lang USING utf8mb4) COLLATE utf8mb4_bin = s.lang);
SET @token_runtime_i18n_inserted = ROW_COUNT();
UPDATE token.translates t JOIN token_runtime_i18n_seed s
  ON t.type = s.template_type
  AND CONVERT(t.tkey USING utf8mb4) COLLATE utf8mb4_bin = s.tkey
  AND CONVERT(t.lang USING utf8mb4) COLLATE utf8mb4_bin = s.lang
SET t.tvalue = s.tvalue, t.action_time = UTC_TIMESTAMP()
WHERE TRIM(COALESCE(t.tvalue, '')) = '';
SET @token_runtime_i18n_filled = ROW_COUNT();

INSERT INTO token_runtime_i18n_guard (verified)
SELECT 1 WHERE EXISTS (SELECT 1 FROM token_runtime_i18n_seed s LEFT JOIN token.translates t
  ON t.type = s.template_type
  AND CONVERT(t.tkey USING utf8mb4) COLLATE utf8mb4_bin = s.tkey
  AND CONVERT(t.lang USING utf8mb4) COLLATE utf8mb4_bin = s.lang
  WHERE t.id IS NULL OR TRIM(COALESCE(t.tvalue, '')) = '');
SET @token_runtime_i18n_changed = @token_runtime_i18n_inserted + @token_runtime_i18n_filled;
UPDATE token.system_setting
SET setting_value = LOWER(UUID()), revision = revision + 1, updated_at = UTC_TIMESTAMP(6)
WHERE BINARY setting_key = BINARY 'i18n.translations.revision' AND @token_runtime_i18n_changed > 0;
SET @token_runtime_i18n_revision_changed = ROW_COUNT();
INSERT INTO token_runtime_i18n_guard (verified)
SELECT 1 WHERE NOT (@token_runtime_i18n_revision_changed = IF(@token_runtime_i18n_changed > 0, 1, 0));
INSERT INTO token_runtime_i18n_guard (verified)
SELECT 1 WHERE NOT ((SELECT revision FROM token.system_setting
  WHERE BINARY setting_key = BINARY 'i18n.translations.revision')
  = @token_runtime_i18n_revision_before + IF(@token_runtime_i18n_changed > 0, 1, 0));
COMMIT;

DROP TEMPORARY TABLE token_runtime_i18n_seed;
DROP TEMPORARY TABLE token_runtime_i18n_guard;
SET SESSION transaction_isolation = @token_runtime_i18n_old_isolation;
SELECT JSON_OBJECT('inserted', @token_runtime_i18n_inserted, 'filled', @token_runtime_i18n_filled,
  'revisionChanged', @token_runtime_i18n_revision_changed) AS token_runtime_i18n_result;
