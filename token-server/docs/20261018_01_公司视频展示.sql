-- 2026-10-18 公司视频：私有MP4/WebM文件、明确的视频链接及首页发布开关。
-- 仅交付脚本，未执行真实数据库。请先备份，独立连接执行，遇首错停止；禁止 --force。
-- DDL隐式提交，不能通过事务回滚；所有旧资源默认不公开，不改启停、名称、排序及译文。
USE token;
SET NAMES utf8mb4 COLLATE utf8mb4_bin;
SET time_zone = '+00:00';

CREATE TEMPORARY TABLE token_company_video_guard (verified TINYINT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO token_company_video_guard VALUES (1);
INSERT INTO token_company_video_guard SELECT 1 WHERE NOT (BINARY DATABASE() = BINARY 'token');
INSERT INTO token_company_video_guard SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM information_schema.tables
  WHERE BINARY table_schema=BINARY 'token' AND table_name='compute_company_file' AND engine='InnoDB' AND table_type='BASE TABLE')=1);
INSERT INTO token_company_video_guard SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM information_schema.columns
  WHERE BINARY table_schema=BINARY 'token' AND table_name='compute_company_file'
  AND column_name IN ('id','file_name','stored_name','file_size','enabled','sort_no','version','created_by','updated_by','created_at','updated_at','resource_type','external_url','names_json'))=14);
INSERT INTO token_company_video_guard SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM information_schema.columns
  WHERE BINARY table_schema=BINARY 'token' AND table_name='compute_company_file' AND column_name='resource_type'
  AND data_type='varchar' AND character_maximum_length IN (8,16) AND character_set_name='ascii' AND collation_name='ascii_bin'
  AND is_nullable='NO' AND column_default='PDF')=1);
INSERT INTO token_company_video_guard SELECT 1 WHERE EXISTS (SELECT 1 FROM token.compute_company_file
  WHERE resource_type NOT IN ('PDF','LINK','VIDEO','VIDEO_LINK'));

-- 重跑时新列必须与目标定义一致，不能把同名的未知字段当成已迁移。
INSERT INTO token_company_video_guard SELECT 1 WHERE EXISTS (SELECT 1 FROM information_schema.columns
  WHERE BINARY table_schema=BINARY 'token' AND table_name='compute_company_file' AND column_name='homepage'
  AND NOT COALESCE(data_type='tinyint' AND column_type LIKE '%unsigned%' AND is_nullable='NO' AND column_default='0', 0));
INSERT INTO token_company_video_guard SELECT 1 WHERE EXISTS (SELECT 1 FROM information_schema.columns
  WHERE BINARY table_schema=BINARY 'token' AND table_name='compute_company_file' AND column_name='mime_type'
  AND NOT(data_type='varchar' AND character_maximum_length=40 AND character_set_name='ascii' AND collation_name='ascii_bin' AND is_nullable='YES' AND column_default IS NULL));
INSERT INTO token_company_video_guard SELECT 1 WHERE EXISTS (SELECT 1 FROM information_schema.statistics
  WHERE BINARY table_schema=BINARY 'token' AND table_name='compute_company_file' AND index_name='idx_company_home_video')
  AND NOT ((SELECT COUNT(*) FROM information_schema.statistics
  WHERE BINARY table_schema=BINARY 'token' AND table_name='compute_company_file' AND index_name='idx_company_home_video'
  AND non_unique=1 AND sub_part IS NULL AND index_type='BTREE' AND ((seq_in_index=1 AND column_name='enabled') OR (seq_in_index=2 AND column_name='homepage')
  OR (seq_in_index=3 AND column_name='resource_type') OR (seq_in_index=4 AND column_name='sort_no') OR (seq_in_index=5 AND column_name='id')))=5
  AND (SELECT COUNT(*) FROM information_schema.statistics WHERE BINARY table_schema=BINARY 'token' AND table_name='compute_company_file' AND index_name='idx_company_home_video')=5);

SET @token_company_ddl = IF((SELECT character_maximum_length FROM information_schema.columns WHERE BINARY table_schema=BINARY 'token'
  AND table_name='compute_company_file' AND column_name='resource_type')=8,
  'ALTER TABLE token.compute_company_file MODIFY COLUMN resource_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''PDF'' COMMENT ''公司资源类型：PDF文件、LINK网页、VIDEO视频文件、VIDEO_LINK视频链接''', 'DO 0');
PREPARE token_company_stmt FROM @token_company_ddl; EXECUTE token_company_stmt; DEALLOCATE PREPARE token_company_stmt;
SET @token_company_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE BINARY table_schema=BINARY 'token'
  AND table_name='compute_company_file' AND column_name='homepage')=0,
  'ALTER TABLE token.compute_company_file ADD COLUMN homepage TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''是否明确发布至游客首页，仅视频可启用，1是0否'' AFTER names_json', 'DO 0');
PREPARE token_company_stmt FROM @token_company_ddl; EXECUTE token_company_stmt; DEALLOCATE PREPARE token_company_stmt;
SET @token_company_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE BINARY table_schema=BINARY 'token'
  AND table_name='compute_company_file' AND column_name='mime_type')=0,
  'ALTER TABLE token.compute_company_file ADD COLUMN mime_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT ''经服务端验证的视频或PDF MIME；网页及嵌入平台链接为空'' AFTER homepage', 'DO 0');
PREPARE token_company_stmt FROM @token_company_ddl; EXECUTE token_company_stmt; DEALLOCATE PREPARE token_company_stmt;
SET @token_company_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE BINARY table_schema=BINARY 'token'
  AND table_name='compute_company_file' AND index_name='idx_company_home_video')=0,
  'ALTER TABLE token.compute_company_file ADD INDEX idx_company_home_video (enabled,homepage,resource_type,sort_no,id) COMMENT ''首页已发布启用视频有序读取''', 'DO 0');
PREPARE token_company_stmt FROM @token_company_ddl; EXECUTE token_company_stmt; DEALLOCATE PREPARE token_company_stmt;

INSERT INTO token_company_video_guard SELECT 1 WHERE EXISTS (SELECT 1 FROM token.compute_company_file
  WHERE homepage NOT IN (0,1) OR (homepage=1 AND resource_type NOT IN ('VIDEO','VIDEO_LINK')));
DROP TEMPORARY TABLE token_company_video_guard;
SET @token_company_ddl = NULL;
