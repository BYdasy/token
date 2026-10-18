-- 2026-10-18 个人中心资产分布、安全分与邀请导航，以及账本金额筛选漏键：9个键、18项中英词条。
-- 仅补缺失/空白译文，保留其他非空人工译文；不删除词条或修改订单、业务配置、资金。
-- 独立连接执行，遇首错停止并回滚，不使用 --force。本文件仅交付，尚未执行。
USE token;
SET NAMES utf8mb4 COLLATE utf8mb4_bin;
SET time_zone = '+00:00';
SET @token_profile_ui_i18n_old_isolation = @@SESSION.transaction_isolation;
SET SESSION TRANSACTION ISOLATION LEVEL SERIALIZABLE;

CREATE TEMPORARY TABLE token_profile_ui_i18n_guard (
  verified TINYINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO token_profile_ui_i18n_guard (verified) VALUES (1);
-- 通过主键冲突中止，不依赖MySQL 8.0.12中不强制执行的CHECK。
INSERT INTO token_profile_ui_i18n_guard (verified)
SELECT 1 WHERE NOT (BINARY DATABASE() = BINARY 'token');
INSERT INTO token_profile_ui_i18n_guard (verified)
SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM information_schema.tables
  WHERE BINARY table_schema = BINARY 'token' AND table_name IN ('translates', 'system_setting')
    AND engine = 'InnoDB' AND table_type = 'BASE TABLE') = 2);
INSERT INTO token_profile_ui_i18n_guard (verified)
SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM information_schema.columns
  WHERE BINARY table_schema = BINARY 'token' AND table_name = 'translates'
    AND column_name IN ('id', 'tkey', 'tvalue', 'lang', 'type', 'prop', 'title', 'is_del', 'action_time')) = 9);
INSERT INTO token_profile_ui_i18n_guard (verified)
SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM information_schema.columns
  WHERE BINARY table_schema = BINARY 'token' AND table_name = 'system_setting'
    AND column_name IN ('setting_key', 'setting_value', 'revision', 'updated_at')) = 4);
INSERT INTO token_profile_ui_i18n_guard (verified)
SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM information_schema.columns
  WHERE BINARY table_schema = BINARY 'token' AND table_name = 'system_setting'
    AND column_name = 'revision' AND data_type = 'bigint' AND is_nullable = 'NO'
    AND COALESCE(generation_expression, '') = '') = 1);
-- 管理端与用户端必须仍属于当前受保护的内置模板，不能插入孤立词条。
INSERT INTO token_profile_ui_i18n_guard (verified)
SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM token.translates WHERE id IN (5, 7) AND type = 0) = 2);
INSERT INTO token_profile_ui_i18n_guard (verified)
SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM information_schema.statistics
  WHERE BINARY table_schema = BINARY 'token' AND table_name = 'translates'
    AND index_name = 'key_lang' AND non_unique = 0
    AND ((seq_in_index = 1 AND column_name = 'tkey')
      OR (seq_in_index = 2 AND column_name = 'lang')
      OR (seq_in_index = 3 AND column_name = 'type'))) = 3);

CREATE TEMPORARY TABLE token_profile_ui_i18n_seed (
  template_type INT NOT NULL,
  tkey VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  lang VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  tvalue TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (template_type, tkey, lang)
) ENGINE=InnoDB;
INSERT INTO token_profile_ui_i18n_seed (template_type, tkey, lang, tvalue) VALUES
(7, CONVERT(0x757365722e766f72612e6e61762e696e76697465 USING utf8mb4), CONVERT(0x7a68 USING utf8mb4), CONVERT(0xe98280e8afb7 USING utf8mb4)),
(7, CONVERT(0x757365722e70726f66696c654f766572766965772e736563757269747953636f7265 USING utf8mb4), CONVERT(0x7a68 USING utf8mb4), CONVERT(0xe5ae89e585a8e58886 USING utf8mb4)),
(7, CONVERT(0x757365722e70726f66696c654f766572766965772e6173736574446973747269627574696f6e USING utf8mb4), CONVERT(0x7a68 USING utf8mb4), CONVERT(0xe8b584e4baa7e58886e5b883 USING utf8mb4)),
(7, CONVERT(0x757365722e70726f66696c654f766572766965772e617661696c61626c65417373657473 USING utf8mb4), CONVERT(0x7a68 USING utf8mb4), CONVERT(0xe992b1e58c85e58fafe794a8e8b584e4baa7 USING utf8mb4)),
(7, CONVERT(0x757365722e70726f66696c654f766572766965772e696e7669746174696f6e496e636f6d65 USING utf8mb4), CONVERT(0x7a68 USING utf8mb4), CONVERT(0xe98280e8afb7e694b6e79b8a USING utf8mb4)),
(7, CONVERT(0x757365722e70726f66696c654f766572766965772e746f74616c496e7669746573 USING utf8mb4), CONVERT(0x7a68 USING utf8mb4), CONVERT(0xe7b4afe8aea1e98280e8afb7e4babae695b0 USING utf8mb4)),
(7, CONVERT(0x757365722e70726f66696c654f766572766965772e717569636b4c6564676572 USING utf8mb4), CONVERT(0x7a68 USING utf8mb4), CONVERT(0xe6b581e6b0b4 USING utf8mb4)),
(5, CONVERT(0x61646d696e2e636f6d707574652e7633354c65646765722e6d696e427573696e657373416d6f756e74 USING utf8mb4), CONVERT(0x7a68 USING utf8mb4), CONVERT(0xe69c80e4bd8ee4b89ae58aa1e98791e9a29d USING utf8mb4)),
(5, CONVERT(0x61646d696e2e636f6d707574652e7633354c65646765722e6d6178427573696e657373416d6f756e74 USING utf8mb4), CONVERT(0x7a68 USING utf8mb4), CONVERT(0xe69c80e9ab98e4b89ae58aa1e98791e9a29d USING utf8mb4)),
(7, CONVERT(0x757365722e766f72612e6e61762e696e76697465 USING utf8mb4), CONVERT(0x656e USING utf8mb4), CONVERT(0x496e76697465 USING utf8mb4)),
(7, CONVERT(0x757365722e70726f66696c654f766572766965772e736563757269747953636f7265 USING utf8mb4), CONVERT(0x656e USING utf8mb4), CONVERT(0x53656375726974792073636f7265 USING utf8mb4)),
(7, CONVERT(0x757365722e70726f66696c654f766572766965772e6173736574446973747269627574696f6e USING utf8mb4), CONVERT(0x656e USING utf8mb4), CONVERT(0x417373657420616c6c6f636174696f6e USING utf8mb4)),
(7, CONVERT(0x757365722e70726f66696c654f766572766965772e617661696c61626c65417373657473 USING utf8mb4), CONVERT(0x656e USING utf8mb4), CONVERT(0x417661696c61626c652077616c6c657420617373657473 USING utf8mb4)),
(7, CONVERT(0x757365722e70726f66696c654f766572766965772e696e7669746174696f6e496e636f6d65 USING utf8mb4), CONVERT(0x656e USING utf8mb4), CONVERT(0x496e7669746174696f6e206561726e696e6773 USING utf8mb4)),
(7, CONVERT(0x757365722e70726f66696c654f766572766965772e746f74616c496e7669746573 USING utf8mb4), CONVERT(0x656e USING utf8mb4), CONVERT(0x546f74616c20696e7669746564206d656d62657273 USING utf8mb4)),
(7, CONVERT(0x757365722e70726f66696c654f766572766965772e717569636b4c6564676572 USING utf8mb4), CONVERT(0x656e USING utf8mb4), CONVERT(0x5472616e73616374696f6e73 USING utf8mb4)),
(5, CONVERT(0x61646d696e2e636f6d707574652e7633354c65646765722e6d696e427573696e657373416d6f756e74 USING utf8mb4), CONVERT(0x656e USING utf8mb4), CONVERT(0x4d696e696d756d20627573696e65737320616d6f756e74 USING utf8mb4)),
(5, CONVERT(0x61646d696e2e636f6d707574652e7633354c65646765722e6d6178427573696e657373416d6f756e74 USING utf8mb4), CONVERT(0x656e USING utf8mb4), CONVERT(0x4d6178696d756d20627573696e65737320616d6f756e74 USING utf8mb4));
INSERT INTO token_profile_ui_i18n_guard (verified)
SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM token_profile_ui_i18n_seed) = 18);

START TRANSACTION;
-- 锁定翻译版本，避免与后台词条编辑并行时丢失缓存失效版本。
INSERT INTO token_profile_ui_i18n_guard (verified)
SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM token.system_setting
  WHERE BINARY setting_key = BINARY 'i18n.translations.revision' AND revision >= 0
    AND revision < 9223372036854775807) = 1);
SELECT revision INTO @token_profile_ui_i18n_revision_before FROM token.system_setting
WHERE BINARY setting_key = BINARY 'i18n.translations.revision' FOR UPDATE;
-- 拒绝仅大小写/排序规则等价的异常键，不能把它们当作准确命中的词条。
INSERT INTO token_profile_ui_i18n_guard (verified)
SELECT 1 WHERE EXISTS (SELECT 1 FROM token.translates t JOIN token_profile_ui_i18n_seed s
  ON t.type = s.template_type
  AND CONVERT(t.tkey USING utf8mb4) COLLATE utf8mb4_general_ci = s.tkey COLLATE utf8mb4_general_ci
  AND CONVERT(t.lang USING utf8mb4) COLLATE utf8mb4_unicode_ci = s.lang COLLATE utf8mb4_unicode_ci
  WHERE NOT (BINARY t.tkey = BINARY s.tkey AND BINARY t.lang = BINARY s.lang));

INSERT INTO token.translates (tkey, lang, tvalue, type, prop, title, is_del, action_time)
SELECT s.tkey, s.lang, s.tvalue, s.template_type, 0, '个人中心与多语言补全', 0, UTC_TIMESTAMP()
FROM token_profile_ui_i18n_seed s
WHERE NOT EXISTS (SELECT 1 FROM token.translates t
  WHERE t.type = s.template_type
    AND CONVERT(t.tkey USING utf8mb4) COLLATE utf8mb4_bin = s.tkey
    AND CONVERT(t.lang USING utf8mb4) COLLATE utf8mb4_bin = s.lang);
SET @token_profile_ui_i18n_inserted = ROW_COUNT();
UPDATE token.translates t JOIN token_profile_ui_i18n_seed s
  ON t.type = s.template_type
  AND CONVERT(t.tkey USING utf8mb4) COLLATE utf8mb4_bin = s.tkey
  AND CONVERT(t.lang USING utf8mb4) COLLATE utf8mb4_bin = s.lang
SET t.tvalue = s.tvalue, t.action_time = UTC_TIMESTAMP()
WHERE TRIM(COALESCE(t.tvalue, '')) = '';
SET @token_profile_ui_i18n_filled = ROW_COUNT();

INSERT INTO token_profile_ui_i18n_guard (verified)
SELECT 1 WHERE EXISTS (SELECT 1 FROM token_profile_ui_i18n_seed s LEFT JOIN token.translates t
  ON t.type = s.template_type
  AND CONVERT(t.tkey USING utf8mb4) COLLATE utf8mb4_bin = s.tkey
  AND CONVERT(t.lang USING utf8mb4) COLLATE utf8mb4_bin = s.lang
  WHERE t.id IS NULL OR TRIM(COALESCE(t.tvalue, '')) = '');
SET @token_profile_ui_i18n_changed = @token_profile_ui_i18n_inserted + @token_profile_ui_i18n_filled;
UPDATE token.system_setting
SET setting_value = LOWER(UUID()), revision = revision + 1, updated_at = UTC_TIMESTAMP(6)
WHERE BINARY setting_key = BINARY 'i18n.translations.revision' AND @token_profile_ui_i18n_changed > 0;
SET @token_profile_ui_i18n_revision_changed = ROW_COUNT();
INSERT INTO token_profile_ui_i18n_guard (verified)
SELECT 1 WHERE NOT (@token_profile_ui_i18n_revision_changed = IF(@token_profile_ui_i18n_changed > 0, 1, 0));
INSERT INTO token_profile_ui_i18n_guard (verified)
SELECT 1 WHERE NOT ((SELECT revision FROM token.system_setting
  WHERE BINARY setting_key = BINARY 'i18n.translations.revision')
  = @token_profile_ui_i18n_revision_before + IF(@token_profile_ui_i18n_changed > 0, 1, 0));
COMMIT;

DROP TEMPORARY TABLE token_profile_ui_i18n_seed;
DROP TEMPORARY TABLE token_profile_ui_i18n_guard;
SET SESSION transaction_isolation = @token_profile_ui_i18n_old_isolation;
SELECT JSON_OBJECT('inserted', @token_profile_ui_i18n_inserted, 'filled', @token_profile_ui_i18n_filled,
  'revisionChanged', @token_profile_ui_i18n_revision_changed) AS token_profile_ui_i18n_result;
