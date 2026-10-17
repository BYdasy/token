-- 2026-10-18 公司视频与PDF独立上传、字段校验及播放预览：10个键、20项中英词条。
-- 仅补缺失/空白译文，保留其他非空人工译文；不删除词条或修改订单、业务配置、资金。
-- 独立连接执行，遇首错停止并回滚，不使用 --force。本文件仅交付，尚未执行。
USE token;
SET NAMES utf8mb4 COLLATE utf8mb4_bin;
SET time_zone = '+00:00';
SET @token_video_ui_i18n_old_isolation = @@SESSION.transaction_isolation;
SET SESSION TRANSACTION ISOLATION LEVEL SERIALIZABLE;

CREATE TEMPORARY TABLE token_video_ui_i18n_guard (
  verified TINYINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO token_video_ui_i18n_guard (verified) VALUES (1);
-- 通过主键冲突中止，不依赖MySQL 8.0.12中不强制执行的CHECK。
INSERT INTO token_video_ui_i18n_guard (verified)
SELECT 1 WHERE NOT (BINARY DATABASE() = BINARY 'token');
INSERT INTO token_video_ui_i18n_guard (verified)
SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM information_schema.tables
  WHERE BINARY table_schema = BINARY 'token' AND table_name IN ('translates', 'system_setting')
    AND engine = 'InnoDB' AND table_type = 'BASE TABLE') = 2);
INSERT INTO token_video_ui_i18n_guard (verified)
SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM information_schema.columns
  WHERE BINARY table_schema = BINARY 'token' AND table_name = 'translates'
    AND column_name IN ('id', 'tkey', 'tvalue', 'lang', 'type', 'prop', 'title', 'is_del', 'action_time')) = 9);
INSERT INTO token_video_ui_i18n_guard (verified)
SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM information_schema.columns
  WHERE BINARY table_schema = BINARY 'token' AND table_name = 'system_setting'
    AND column_name IN ('setting_key', 'setting_value', 'revision', 'updated_at')) = 4);
INSERT INTO token_video_ui_i18n_guard (verified)
SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM information_schema.statistics
  WHERE BINARY table_schema = BINARY 'token' AND table_name = 'translates'
    AND index_name = 'key_lang' AND non_unique = 0
    AND ((seq_in_index = 1 AND column_name = 'tkey')
      OR (seq_in_index = 2 AND column_name = 'lang')
      OR (seq_in_index = 3 AND column_name = 'type'))) = 3);

CREATE TEMPORARY TABLE token_video_ui_i18n_seed (
  template_type INT NOT NULL,
  tkey VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  lang VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  tvalue TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (template_type, tkey, lang)
) ENGINE=InnoDB;
INSERT INTO token_video_ui_i18n_seed (template_type, tkey, lang, tvalue) VALUES
(5, CONVERT(0x61646d696e2e636f6d707574652e636f6d70616e79566964656f2e75706c6f6164566964656f USING utf8mb4), CONVERT(0x7a68 USING utf8mb4), CONVERT(0xe4b88ae4bca0e8a786e9a291 USING utf8mb4)),
(5, CONVERT(0x61646d696e2e636f6d707574652e636f6d70616e79566964656f2e70646655706c6f616448656c70 USING utf8mb4), CONVERT(0x7a68 USING utf8mb4), CONVERT(0xe8afb7e98089e68ba92050444620e69687e4bbb6efbc8ce69c80e5a4a7207b73697a657defbc8ce69687e4bbb6e5908de4b88de8b685e8bf872032303020e5ad97e38082 USING utf8mb4)),
(5, CONVERT(0x61646d696e2e636f6d707574652e636f6d70616e79566964656f2e766964656f55706c6f616448656c70 USING utf8mb4), CONVERT(0x7a68 USING utf8mb4), CONVERT(0xe8afb7e98089e68ba9204d503420e68896205765624d20e8a786e9a291efbc8ce69c80e5a4a7207b73697a657defbc8ce69687e4bbb6e5908de4b88de8b685e8bf872032303020e5ad97e38082 USING utf8mb4)),
(5, CONVERT(0x61646d696e2e636f6d707574652e636f6d70616e79566964656f2e70726576696577 USING utf8mb4), CONVERT(0x7a68 USING utf8mb4), CONVERT(0xe692ade694bee9a284e8a788 USING utf8mb4)),
(5, CONVERT(0x61646d696e2e636f6d707574652e636f6d70616e79566964656f2e707265766965774661696c6564 USING utf8mb4), CONVERT(0x7a68 USING utf8mb4), CONVERT(0xe8a786e9a291e9a284e8a788e58aa0e8bdbde5a4b1e8b4a5efbc8ce8afb7e9878de8af95e38082 USING utf8mb4)),
(5, CONVERT(0x61646d696e2e636f6d707574652e636f6d70616e79566964656f2e696e76616c69645469746c65 USING utf8mb4), CONVERT(0x7a68 USING utf8mb4), CONVERT(0xe8afb7e5a1abe58699e4b88de8b685e8bf872032303020e5ad97e79a84e69c89e69588e6a087e9a298e38082 USING utf8mb4)),
(5, CONVERT(0x61646d696e2e636f6d707574652e636f6d70616e79566964656f2e696e76616c6964566964656f4e616d65 USING utf8mb4), CONVERT(0x7a68 USING utf8mb4), CONVERT(0xe8afb7e5a1abe58699e69c89e69588e79a84e8a786e9a291e69687e4bbb6e5908defbc8ce5b9b6e4bf9de79599e58e9fe69687e4bbb6e79a84204d503420e68896205765624d20e689a9e5b195e5908de38082 USING utf8mb4)),
(5, CONVERT(0x61646d696e2e636f6d707574652e636f6d70616e79566964656f2e696e76616c69645064664e616d65 USING utf8mb4), CONVERT(0x7a68 USING utf8mb4), CONVERT(0xe8afb7e5a1abe58699e4bba5202e70646620e7bb93e5b0bee79a84e69c89e69588e69687e4bbb6e5908defbc8ce4b88de8b685e8bf872032303020e5ad97e38082 USING utf8mb4)),
(5, CONVERT(0x61646d696e2e636f6d707574652e636f6d70616e79566964656f2e696e76616c69644c696e6b USING utf8mb4), CONVERT(0x7a68 USING utf8mb4), CONVERT(0xe8afb7e5a1abe58699e69c89e69588e79a84204854545020e6889620485454505320e993bee68ea5efbc8ce4b88de8b685e8bf87203230303020e5ad97e38082 USING utf8mb4)),
(5, CONVERT(0x61646d696e2e636f6d707574652e636f6d70616e79566964656f2e696e76616c6964536f7274 USING utf8mb4), CONVERT(0x7a68 USING utf8mb4), CONVERT(0xe68e92e5ba8fe5bf85e9a1bbe4b8ba203020e887b3203130303030303020e79a84e695b4e695b0e38082 USING utf8mb4)),
(5, CONVERT(0x61646d696e2e636f6d707574652e636f6d70616e79566964656f2e75706c6f6164566964656f USING utf8mb4), CONVERT(0x656e USING utf8mb4), CONVERT(0x55706c6f616420766964656f USING utf8mb4)),
(5, CONVERT(0x61646d696e2e636f6d707574652e636f6d70616e79566964656f2e70646655706c6f616448656c70 USING utf8mb4), CONVERT(0x656e USING utf8mb4), CONVERT(0x43686f6f73652061205044462066696c6520757020746f207b73697a657d2c207769746820612066696c656e616d65206e6f206c6f6e676572207468616e2032303020636861726163746572732e USING utf8mb4)),
(5, CONVERT(0x61646d696e2e636f6d707574652e636f6d70616e79566964656f2e766964656f55706c6f616448656c70 USING utf8mb4), CONVERT(0x656e USING utf8mb4), CONVERT(0x43686f6f736520616e204d5034206f72205765624d20766964656f20757020746f207b73697a657d2c207769746820612066696c656e616d65206e6f206c6f6e676572207468616e2032303020636861726163746572732e USING utf8mb4)),
(5, CONVERT(0x61646d696e2e636f6d707574652e636f6d70616e79566964656f2e70726576696577 USING utf8mb4), CONVERT(0x656e USING utf8mb4), CONVERT(0x5072657669657720766964656f USING utf8mb4)),
(5, CONVERT(0x61646d696e2e636f6d707574652e636f6d70616e79566964656f2e707265766965774661696c6564 USING utf8mb4), CONVERT(0x656e USING utf8mb4), CONVERT(0x566964656f207072657669657720636f756c64206e6f74206c6f61642e20506c656173652074727920616761696e2e USING utf8mb4)),
(5, CONVERT(0x61646d696e2e636f6d707574652e636f6d70616e79566964656f2e696e76616c69645469746c65 USING utf8mb4), CONVERT(0x656e USING utf8mb4), CONVERT(0x456e74657220612076616c6964207469746c65206f6620757020746f2032303020636861726163746572732e USING utf8mb4)),
(5, CONVERT(0x61646d696e2e636f6d707574652e636f6d70616e79566964656f2e696e76616c6964566964656f4e616d65 USING utf8mb4), CONVERT(0x656e USING utf8mb4), CONVERT(0x456e74657220612076616c696420766964656f2066696c656e616d6520616e64206b65657020746865206f726967696e616c204d5034206f72205765624d20657874656e73696f6e2e USING utf8mb4)),
(5, CONVERT(0x61646d696e2e636f6d707574652e636f6d70616e79566964656f2e696e76616c69645064664e616d65 USING utf8mb4), CONVERT(0x656e USING utf8mb4), CONVERT(0x456e74657220612076616c69642066696c656e616d6520656e64696e6720696e202e7064662c20757020746f2032303020636861726163746572732e USING utf8mb4)),
(5, CONVERT(0x61646d696e2e636f6d707574652e636f6d70616e79566964656f2e696e76616c69644c696e6b USING utf8mb4), CONVERT(0x656e USING utf8mb4), CONVERT(0x456e74657220612076616c69642048545450206f722048545450532055524c206f6620757020746f20322c30303020636861726163746572732e USING utf8mb4)),
(5, CONVERT(0x61646d696e2e636f6d707574652e636f6d70616e79566964656f2e696e76616c6964536f7274 USING utf8mb4), CONVERT(0x656e USING utf8mb4), CONVERT(0x536f7274206f72646572206d75737420626520616e20696e74656765722066726f6d203020746f20312c3030302c3030302e USING utf8mb4));
INSERT INTO token_video_ui_i18n_guard (verified)
SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM token_video_ui_i18n_seed) = 20);

START TRANSACTION;
-- 锁定翻译版本，避免与后台词条编辑并行时丢失缓存失效版本。
INSERT INTO token_video_ui_i18n_guard (verified)
SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM token.system_setting
  WHERE BINARY setting_key = BINARY 'i18n.translations.revision' AND revision >= 0) = 1);
SELECT revision INTO @token_video_ui_i18n_revision_before FROM token.system_setting
WHERE BINARY setting_key = BINARY 'i18n.translations.revision' FOR UPDATE;
-- 拒绝仅大小写/排序规则等价的异常键，不能把它们当作准确命中的词条。
INSERT INTO token_video_ui_i18n_guard (verified)
SELECT 1 WHERE EXISTS (SELECT 1 FROM token.translates t JOIN token_video_ui_i18n_seed s
  ON t.type = s.template_type
  AND CONVERT(t.tkey USING utf8mb4) COLLATE utf8mb4_general_ci = s.tkey COLLATE utf8mb4_general_ci
  AND CONVERT(t.lang USING utf8mb4) COLLATE utf8mb4_unicode_ci = s.lang COLLATE utf8mb4_unicode_ci
  WHERE NOT (BINARY t.tkey = BINARY s.tkey AND BINARY t.lang = BINARY s.lang));

INSERT INTO token.translates (tkey, lang, tvalue, type, prop, title, is_del, action_time)
SELECT s.tkey, s.lang, s.tvalue, s.template_type, 0, '公司视频与文件上传优化', 0, UTC_TIMESTAMP()
FROM token_video_ui_i18n_seed s
WHERE NOT EXISTS (SELECT 1 FROM token.translates t
  WHERE t.type = s.template_type
    AND CONVERT(t.tkey USING utf8mb4) COLLATE utf8mb4_bin = s.tkey
    AND CONVERT(t.lang USING utf8mb4) COLLATE utf8mb4_bin = s.lang);
SET @token_video_ui_i18n_inserted = ROW_COUNT();
UPDATE token.translates t JOIN token_video_ui_i18n_seed s
  ON t.type = s.template_type
  AND CONVERT(t.tkey USING utf8mb4) COLLATE utf8mb4_bin = s.tkey
  AND CONVERT(t.lang USING utf8mb4) COLLATE utf8mb4_bin = s.lang
SET t.tvalue = s.tvalue, t.action_time = UTC_TIMESTAMP()
WHERE TRIM(COALESCE(t.tvalue, '')) = '';
SET @token_video_ui_i18n_filled = ROW_COUNT();

INSERT INTO token_video_ui_i18n_guard (verified)
SELECT 1 WHERE EXISTS (SELECT 1 FROM token_video_ui_i18n_seed s LEFT JOIN token.translates t
  ON t.type = s.template_type
  AND CONVERT(t.tkey USING utf8mb4) COLLATE utf8mb4_bin = s.tkey
  AND CONVERT(t.lang USING utf8mb4) COLLATE utf8mb4_bin = s.lang
  WHERE t.id IS NULL OR TRIM(COALESCE(t.tvalue, '')) = '');
SET @token_video_ui_i18n_changed = @token_video_ui_i18n_inserted + @token_video_ui_i18n_filled;
UPDATE token.system_setting
SET setting_value = LOWER(UUID()), revision = revision + 1, updated_at = UTC_TIMESTAMP(6)
WHERE BINARY setting_key = BINARY 'i18n.translations.revision' AND @token_video_ui_i18n_changed > 0;
SET @token_video_ui_i18n_revision_changed = ROW_COUNT();
INSERT INTO token_video_ui_i18n_guard (verified)
SELECT 1 WHERE NOT (@token_video_ui_i18n_revision_changed = IF(@token_video_ui_i18n_changed > 0, 1, 0));
INSERT INTO token_video_ui_i18n_guard (verified)
SELECT 1 WHERE NOT ((SELECT revision FROM token.system_setting
  WHERE BINARY setting_key = BINARY 'i18n.translations.revision')
  = @token_video_ui_i18n_revision_before + IF(@token_video_ui_i18n_changed > 0, 1, 0));
COMMIT;

DROP TEMPORARY TABLE token_video_ui_i18n_seed;
DROP TEMPORARY TABLE token_video_ui_i18n_guard;
SET SESSION transaction_isolation = @token_video_ui_i18n_old_isolation;
SELECT JSON_OBJECT('inserted', @token_video_ui_i18n_inserted, 'filled', @token_video_ui_i18n_filled,
  'revisionChanged', @token_video_ui_i18n_revision_changed) AS token_video_ui_i18n_result;
