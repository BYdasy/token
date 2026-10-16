-- AI Vora 手机认证页：密码规则独立帮助文字。仅补缺和补空，保留非空人工译文。
-- 不修改品牌、权限、资金和业务结构；MySQL 客户端必须遇首错停止。
USE token;
SET NAMES utf8mb4;
SET time_zone = '+00:00';
SET @token_password_sql_mode=@@SESSION.sql_mode;
SET SESSION sql_mode=CONCAT_WS(',',@@SESSION.sql_mode,'STRICT_ALL_TABLES');
CREATE TEMPORARY TABLE token_password_guard (verified TINYINT NOT NULL);
INSERT INTO token_password_guard SELECT IF(BINARY DATABASE()=BINARY 'token' AND (SELECT COUNT(*) FROM system_setting WHERE setting_key='i18n.translations.revision')=1,1,NULL);
START TRANSACTION;
SELECT setting_key FROM system_setting WHERE setting_key='i18n.translations.revision' FOR UPDATE;
SET @token_password_inserted=0, @token_password_filled=0;
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT CONVERT(0x757365722e6d6f62696c652e617574682e70617373776f726448656c70 USING utf8mb4),CONVERT(0x7a68 USING utf8mb4),CONVERT(0xe5af86e7a081e4b8ba2036efbd9e363420e4b8aae5ad97e7aca6efbc8c5554462d3820e7bc96e7a081e4b88de8b685e8bf8720373220e5ad97e88a82e38082 USING utf8mb4),7,0,CONVERT(0x414920566f726120e6898be69cbae7abaf USING utf8mb4),0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=7 AND BINARY tkey=BINARY CONVERT(0x757365722e6d6f62696c652e617574682e70617373776f726448656c70 USING utf8mb4) AND BINARY lang=BINARY CONVERT(0x7a68 USING utf8mb4));
SET @token_password_inserted=@token_password_inserted+ROW_COUNT();
UPDATE translates SET tvalue=CONVERT(0xe5af86e7a081e4b8ba2036efbd9e363420e4b8aae5ad97e7aca6efbc8c5554462d3820e7bc96e7a081e4b88de8b685e8bf8720373220e5ad97e88a82e38082 USING utf8mb4),action_time=UTC_TIMESTAMP(6) WHERE type=7 AND BINARY tkey=BINARY CONVERT(0x757365722e6d6f62696c652e617574682e70617373776f726448656c70 USING utf8mb4) AND BINARY lang=BINARY CONVERT(0x7a68 USING utf8mb4) AND TRIM(COALESCE(tvalue,''))='';
SET @token_password_filled=@token_password_filled+ROW_COUNT();
INSERT INTO translates(tkey,lang,tvalue,type,prop,title,is_del,action_time) SELECT CONVERT(0x757365722e6d6f62696c652e617574682e70617373776f726448656c70 USING utf8mb4),CONVERT(0x656e USING utf8mb4),CONVERT(0x5573652036e280933634206368617261637465727320616e64206e6f206d6f7265207468616e203732205554462d382062797465732e USING utf8mb4),7,0,CONVERT(0x414920566f726120e6898be69cbae7abaf USING utf8mb4),0,UTC_TIMESTAMP(6) WHERE NOT EXISTS(SELECT 1 FROM translates WHERE type=7 AND BINARY tkey=BINARY CONVERT(0x757365722e6d6f62696c652e617574682e70617373776f726448656c70 USING utf8mb4) AND BINARY lang=BINARY CONVERT(0x656e USING utf8mb4));
SET @token_password_inserted=@token_password_inserted+ROW_COUNT();
UPDATE translates SET tvalue=CONVERT(0x5573652036e280933634206368617261637465727320616e64206e6f206d6f7265207468616e203732205554462d382062797465732e USING utf8mb4),action_time=UTC_TIMESTAMP(6) WHERE type=7 AND BINARY tkey=BINARY CONVERT(0x757365722e6d6f62696c652e617574682e70617373776f726448656c70 USING utf8mb4) AND BINARY lang=BINARY CONVERT(0x656e USING utf8mb4) AND TRIM(COALESCE(tvalue,''))='';
SET @token_password_filled=@token_password_filled+ROW_COUNT();
UPDATE system_setting SET setting_value=LOWER(UUID()),revision=revision+1,updated_at=UTC_TIMESTAMP(6) WHERE setting_key='i18n.translations.revision' AND @token_password_inserted+@token_password_filled>0;
COMMIT;
DROP TEMPORARY TABLE token_password_guard;
SET SESSION sql_mode=@token_password_sql_mode;
SELECT JSON_OBJECT('inserted',@token_password_inserted,'filled',@token_password_filled) AS token_password_result;
