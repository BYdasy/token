-- 2026-10-18 / 06：Telegram默认模板正文分行；固定4模板、8条中英文正文。
-- 对照 ref 的151号修复，已适配Token的语言字段及配置revision；本文件独立执行，不依赖ref。
-- 可读正文清单：docs/i18n/telegram-template-layout-20261018.json。本文件仅交付，未执行。
-- 仅精确命中旧默认正文时替换；人工非空正文、标题、名称、开关、通知规则和投递记录全部保留。
-- 基础模板已自定义或不存在时跳过整组词条，避免补缺后掩盖自定义正文回退。
-- 换行内容按UTF-8十六进制写入，不依赖NO_BACKSLASH_ESCAPES；同事务按实际变化更新缓存版本。
-- 使用支持DELIMITER的MySQL客户端完整执行，遇首错停止，不使用--force。
USE token;
SET NAMES utf8mb4 COLLATE utf8mb4_bin;
SET time_zone = '+00:00';

DELIMITER $$
DROP PROCEDURE IF EXISTS token_migrate_20261018_06_telegram_template_layout$$
CREATE PROCEDURE token_migrate_20261018_06_telegram_template_layout()
BEGIN
    DECLARE v_locked INT DEFAULT 0;
    DECLARE v_templates_updated INT DEFAULT 0;
    DECLARE v_translations_updated INT DEFAULT 0;
    DECLARE v_inserted INT DEFAULT 0;
    DECLARE v_revision_rows INT DEFAULT 0;
    DECLARE v_revision_inserted INT DEFAULT 0;
    DECLARE v_revision_advanced INT DEFAULT 0;
    DECLARE v_revision_number BIGINT;
    DECLARE v_revision TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
    DECLARE v_bad_fields VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
    DECLARE v_guard_message VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        DROP TEMPORARY TABLE IF EXISTS token_20261018_06_template_seed;
        DROP TEMPORARY TABLE IF EXISTS token_20261018_06_translation_seed;
        IF v_locked=1 THEN DO RELEASE_LOCK('token:migration:20261018:06'); END IF;
        RESIGNAL;
    END;

    IF DATABASE() IS NULL OR BINARY DATABASE()<>BINARY 'token' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：必须在token库执行';
    END IF;
    SELECT GET_LOCK('token:migration:20261018:06',15) INTO v_locked;
    IF COALESCE(v_locked,0)<>1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：未取得迁移锁';
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='token'
        AND table_name='translates' AND table_type='BASE TABLE' AND engine='InnoDB')<>1
       OR (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='token' AND table_name='translates'
        AND column_name IN('id','tkey','tvalue','lang','type','prop','title','is_del','action_time'))<>9
       OR NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='token' AND table_name='translates'
        AND column_name='id' AND extra LIKE '%auto_increment%') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：翻译基础表或自增主键结构不符';
    END IF;
    -- 按已核实的翻译表排序规则检查等价冲突，不修改表/字段排序规则。
    IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='token' AND table_name='translates'
        AND ((column_name='tkey' AND data_type='varchar' AND character_maximum_length=100 AND collation_name='utf8mb4_general_ci')
          OR (column_name='lang' AND data_type='varchar' AND character_maximum_length=40 AND collation_name='utf8mb4_unicode_ci')
          OR (column_name='tvalue' AND data_type='text' AND collation_name='utf8mb4_general_ci'))
        AND character_set_name='utf8mb4'
        AND COALESCE(generation_expression,'')='')<>3 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：翻译字段类型或排序规则变化，请核对';
    END IF;
    IF EXISTS(SELECT 1 FROM information_schema.triggers WHERE event_object_schema='token' AND event_object_table='translates') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：翻译表存在触发器，需核对写入影响';
    END IF;
    IF (SELECT COUNT(*) FROM translates WHERE id=6 AND type=0)<>1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：缺少消息通知内置模板id6';
    END IF;
    IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='token' AND table_name='translates'
        AND column_name IN('id','tkey','tvalue','lang','type','prop','title','is_del','action_time')
        AND COALESCE(generation_expression,'')<>'') THEN
        SELECT GROUP_CONCAT(column_name ORDER BY ordinal_position SEPARATOR ',') INTO v_bad_fields
        FROM information_schema.columns WHERE table_schema='token' AND table_name='translates'
            AND column_name IN('id','tkey','tvalue','lang','type','prop','title','is_del','action_time')
            AND COALESCE(generation_expression,'')<>'';
        SET v_guard_message=LEFT(CONCAT('06拒绝：翻译写入字段为生成列:',v_bad_fields),128);
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=v_guard_message;
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='token'
        AND table_name='system_setting' AND table_type='BASE TABLE' AND engine='InnoDB')<>1
       OR (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='token' AND table_name='system_setting'
        AND column_name IN('setting_key','setting_value','value_type','group_name','description','is_public','is_editable','updated_by','updated_at'))<>9 THEN
        SELECT GROUP_CONCAT(required_columns.column_name ORDER BY required_columns.position SEPARATOR ',') INTO v_bad_fields
        FROM (
            SELECT 1 AS position,_utf8mb4'setting_key' COLLATE utf8mb4_bin AS column_name
            UNION ALL SELECT 2,'setting_value' UNION ALL SELECT 3,'value_type'
            UNION ALL SELECT 4,'group_name' UNION ALL SELECT 5,'description'
            UNION ALL SELECT 6,'is_public' UNION ALL SELECT 7,'is_editable'
            UNION ALL SELECT 8,'updated_by' UNION ALL SELECT 9,'updated_at'
        ) required_columns LEFT JOIN information_schema.columns actual_columns
          ON actual_columns.table_schema='token' AND actual_columns.table_name='system_setting'
         AND CONVERT(actual_columns.column_name USING utf8mb4) COLLATE utf8mb4_bin=required_columns.column_name COLLATE utf8mb4_bin
        WHERE actual_columns.column_name IS NULL;
        SET v_guard_message=LEFT(CONCAT('06拒绝：配置表/字段缺失:',COALESCE(v_bad_fields,'system_setting需为InnoDB基础表')),128);
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=v_guard_message;
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='token' AND table_name='system_setting'
        AND ((column_name='setting_key' AND data_type='varchar' AND character_maximum_length>=26
               AND character_set_name='utf8mb4' AND collation_name='utf8mb4_unicode_ci')
          OR (column_name='setting_value' AND data_type IN('varchar','text','mediumtext','longtext') AND character_maximum_length>=36)
          OR (column_name='value_type' AND data_type='varchar' AND character_maximum_length>=6)
          OR (column_name='group_name' AND data_type='varchar' AND character_maximum_length>=5)
          OR (column_name='description' AND data_type IN('varchar','text') AND character_maximum_length>=24)
          OR (column_name IN('is_public','is_editable') AND data_type IN('tinyint','smallint','int','bit'))
          OR (column_name='updated_by' AND is_nullable='YES')
          OR (column_name='updated_at' AND data_type IN('datetime','timestamp')))
        AND COALESCE(generation_expression,'')='')<>9 THEN
        -- 与上方守卫使用相同谓词，拒绝时列出字段；只输出结构名字，不包含任何配置值。
        SELECT GROUP_CONCAT(column_name ORDER BY ordinal_position SEPARATOR ',') INTO v_bad_fields
        FROM information_schema.columns WHERE table_schema='token' AND table_name='system_setting'
          AND column_name IN('setting_key','setting_value','value_type','group_name','description','is_public','is_editable','updated_by','updated_at')
          AND COALESCE((
             ((column_name='setting_key' AND data_type='varchar' AND character_maximum_length>=26
                 AND character_set_name='utf8mb4' AND collation_name='utf8mb4_unicode_ci')
              OR (column_name='setting_value' AND data_type IN('varchar','text','mediumtext','longtext') AND character_maximum_length>=36)
              OR (column_name='value_type' AND data_type='varchar' AND character_maximum_length>=6)
              OR (column_name='group_name' AND data_type='varchar' AND character_maximum_length>=5)
              OR (column_name='description' AND data_type IN('varchar','text') AND character_maximum_length>=24)
              OR (column_name IN('is_public','is_editable') AND data_type IN('tinyint','smallint','int','bit'))
              OR (column_name='updated_by' AND is_nullable='YES')
              OR (column_name='updated_at' AND data_type IN('datetime','timestamp')))
             AND COALESCE(generation_expression,'')=''),0)=0;
        SET v_guard_message=LEFT(CONCAT('06拒绝：配置字段不符:',COALESCE(v_bad_fields,'未知字段')),128);
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT=v_guard_message;
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.columns WHERE BINARY table_schema=BINARY 'token'
        AND table_name='system_setting' AND column_name='revision' AND data_type='bigint'
        AND is_nullable='NO' AND COALESCE(generation_expression,'')='')<>1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：缺少配置revision并发版本';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema='token' AND table_name='system_setting'
        GROUP BY index_name HAVING COUNT(*)=1 AND SUM(CASE WHEN column_name='setting_key' AND non_unique=0
            AND sub_part IS NULL AND index_type='BTREE' THEN 1 ELSE 0 END)=1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：配置键缺少完整唯一索引';
    END IF;
    IF EXISTS(SELECT 1 FROM information_schema.triggers WHERE event_object_schema='token' AND event_object_table='system_setting') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：配置表存在触发器，需核对写入影响';
    END IF;


    IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='token' AND table_name='translates'
        AND COALESCE(generation_expression,'')=''
        AND ((column_name='id' AND data_type='int' AND extra LIKE '%auto_increment%')
          OR (column_name IN('type','prop','is_del') AND data_type='int')
          OR (column_name='title' AND data_type='varchar' AND character_maximum_length>=255)
          OR (column_name='action_time' AND data_type IN('datetime','timestamp'))))<>6 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：翻译元数据字段类型或容量不符';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema='token' AND table_name='translates'
        GROUP BY index_name HAVING COUNT(*)=3 AND SUM(non_unique=0 AND sub_part IS NULL AND index_type='BTREE')=3
        AND GROUP_CONCAT(CONVERT(column_name USING utf8mb4) COLLATE utf8mb4_bin ORDER BY seq_in_index SEPARATOR ',')
            =_utf8mb4'tkey,lang,type' COLLATE utf8mb4_bin) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：翻译键缺少完整唯一索引';
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='token' AND table_name='notification_template'
        AND table_type='BASE TABLE' AND engine='InnoDB')<>1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：缺少通知模板InnoDB基础表';
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='token' AND table_name='notification_template'
        AND COALESCE(generation_expression,'')=''
        AND ((column_name='id' AND data_type='bigint' AND extra LIKE '%auto_increment%')
          OR (column_name IN('code','name') AND data_type='varchar' AND character_maximum_length>=100
              AND character_set_name='utf8mb4' AND collation_name='utf8mb4_unicode_ci')
          OR (column_name='channel_type' AND data_type='varchar' AND character_maximum_length>=20
              AND character_set_name='utf8mb4' AND collation_name='utf8mb4_unicode_ci')
          OR (column_name='title_template' AND data_type='varchar' AND character_maximum_length>=300)
          OR (column_name='content_template' AND data_type='longtext' AND character_set_name='utf8mb4'
              AND collation_name='utf8mb4_unicode_ci')
          OR (column_name='enabled' AND data_type IN('tinyint','int'))
          OR (column_name IN('created_at','updated_at') AND data_type IN('datetime','timestamp'))))<>9 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：通知模板字段类型或容量不符';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema='token' AND table_name='notification_template'
        GROUP BY index_name HAVING COUNT(*)=1 AND SUM(column_name='code' AND non_unique=0 AND sub_part IS NULL AND index_type='BTREE')=1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：通知模板code缺少完整唯一索引';
    END IF;
    IF EXISTS(SELECT 1 FROM information_schema.triggers WHERE event_object_schema='token' AND event_object_table='notification_template') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：通知模板表存在触发器，需核对写入影响';
    END IF;

    DROP TEMPORARY TABLE IF EXISTS token_20261018_06_template_seed;
    DROP TEMPORARY TABLE IF EXISTS token_20261018_06_translation_seed;
    CREATE TEMPORARY TABLE token_20261018_06_template_seed(
        code VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY COMMENT '固定Telegram模板编码',
        old_content TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '145号原始中文默认正文',
        new_content TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '分行中文默认正文',
        eligible TINYINT NOT NULL DEFAULT 0 COMMENT '锁定后确认基础模板仍为原始或新默认正文'
    ) ENGINE=InnoDB COMMENT='本轮固定Telegram模板正文清单';
    CREATE TEMPORARY TABLE token_20261018_06_translation_seed(
        code VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '固定Telegram模板编码',
        template_type INT NOT NULL COMMENT '消息通知模板类型6',
        tkey VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '固定正文翻译键',
        lang VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '中文或英语',
        old_content TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '145号原始默认正文',
        new_content TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '分行默认正文',
        PRIMARY KEY(tkey,lang)
    ) ENGINE=InnoDB COMMENT='本轮固定Telegram中英文正文清单';
    INSERT INTO token_20261018_06_template_seed(code,old_content,new_content) VALUES
      (CONVERT(0x4c5f4445505f5447 USING utf8mb4),CONVERT(0xe794a8e688b7207b7b757365726e616d657d7d20e79a84e58585e580bce8aea2e58d95207b7b6f726465724e6f7d7d20e5b7b2e588b0e8b4a6207b7b616d6f756e747d7d207b7b63757272656e63797d7de38082 USING utf8mb4),CONVERT(0xe794a8e688b73a203c636f64653e7b7b757365726e616d657d7d3c2f636f64653e0ae58585e580bce8aea2e58d953a203c636f64653e7b7b6f726465724e6f7d7d3c2f636f64653e0ae588b0e8b4a6e98791e9a29d3a203c623e7b7b616d6f756e747d7d207b7b63757272656e63797d7d3c2f623e USING utf8mb4)),
      (CONVERT(0x4c5f57445f5447 USING utf8mb4),CONVERT(0xe794a8e688b7207b7b757365726e616d657d7d20e68f90e4baa4e68f90e6acbee8aea2e58d95207b7b6f726465724e6f7d7defbc8ce794b3e8afb7e680bbe9a29d207b7b616d6f756e747d7d207b7b63757272656e63797d7de38082 USING utf8mb4),CONVERT(0xe794a8e688b73a203c636f64653e7b7b757365726e616d657d7d3c2f636f64653e0ae68f90e6acbee8aea2e58d953a203c636f64653e7b7b6f726465724e6f7d7d3c2f636f64653e0ae794b3e8afb7e680bbe9a29d3a203c623e7b7b616d6f756e747d7d207b7b63757272656e63797d7d3c2f623e USING utf8mb4)),
      (CONVERT(0x57445f4c4154455f5447 USING utf8mb4),CONVERT(0xe794a8e688b7207b7b757365726e616d657d7d20e79a84e68f90e6acbee8aea2e58d95207b7b6f726465724e6f7d7d20e5b7b2e7ad89e5be85e5aea1e6a0b8e8b685e8bf87207b7b6d696e757465737d7d20e58886e9929fe38082 USING utf8mb4),CONVERT(0xe794a8e688b73a203c636f64653e7b7b757365726e616d657d7d3c2f636f64653e0ae68f90e6acbee8aea2e58d953a203c636f64653e7b7b6f726465724e6f7d7d3c2f636f64653e0ae5b7b2e7ad89e5be85e5aea1e6a0b8e8b685e8bf87203c623e7b7b6d696e757465737d7d3c2f623e20e58886e9929fe38082 USING utf8mb4)),
      (CONVERT(0x454e455247595f5447 USING utf8mb4),CONVERT(0x7b7b70726f76696465724e616d657d7d20e5bd93e5898de4bd99e9a29d207b7b62616c616e63657d7d207b7b63757272656e63797d7defbc8ce4bd8ee4ba8ee68f90e98692e99888e580bc207b7b7468726573686f6c647d7d207b7b63757272656e63797d7de38082 USING utf8mb4),CONVERT(0xe4be9be5ba94e595863a203c636f64653e7b7b70726f76696465724e616d657d7d3c2f636f64653e0ae5bd93e5898de4bd99e9a29d3a203c623e7b7b62616c616e63657d7d207b7b63757272656e63797d7d3c2f623e0ae68f90e98692e99888e580bc3a203c623e7b7b7468726573686f6c647d7d207b7b63757272656e63797d7d3c2f623e0ae4bd99e9a29de5b7b2e4bd8ee4ba8ee68f90e98692e99888e580bcefbc8ce8afb7e58f8ae697b6e8a1a5e58585e38082 USING utf8mb4));
    INSERT INTO token_20261018_06_translation_seed(code,template_type,tkey,lang,old_content,new_content) VALUES
      (CONVERT(0x4c5f4445505f5447 USING utf8mb4),6,CONVERT(0x6e6f74696669636174696f6e2e74656d706c6174652e6c5f6465705f74672e636f6e74656e74 USING utf8mb4),CONVERT(0x7a68 USING utf8mb4),CONVERT(0xe794a8e688b7207b7b757365726e616d657d7d20e79a84e58585e580bce8aea2e58d95207b7b6f726465724e6f7d7d20e5b7b2e588b0e8b4a6207b7b616d6f756e747d7d207b7b63757272656e63797d7de38082 USING utf8mb4),CONVERT(0xe794a8e688b73a203c636f64653e7b7b757365726e616d657d7d3c2f636f64653e0ae58585e580bce8aea2e58d953a203c636f64653e7b7b6f726465724e6f7d7d3c2f636f64653e0ae588b0e8b4a6e98791e9a29d3a203c623e7b7b616d6f756e747d7d207b7b63757272656e63797d7d3c2f623e USING utf8mb4)),
      (CONVERT(0x4c5f4445505f5447 USING utf8mb4),6,CONVERT(0x6e6f74696669636174696f6e2e74656d706c6174652e6c5f6465705f74672e636f6e74656e74 USING utf8mb4),CONVERT(0x656e USING utf8mb4),CONVERT(0x4465706f736974207b7b6f726465724e6f7d7d20666f72207b7b757365726e616d657d7d207761732063726564697465642077697468207b7b616d6f756e747d7d207b7b63757272656e63797d7d2e USING utf8mb4),CONVERT(0x557365723a203c636f64653e7b7b757365726e616d657d7d3c2f636f64653e0a4465706f736974206f726465723a203c636f64653e7b7b6f726465724e6f7d7d3c2f636f64653e0a416d6f756e742063726564697465643a203c623e7b7b616d6f756e747d7d207b7b63757272656e63797d7d3c2f623e USING utf8mb4)),
      (CONVERT(0x4c5f57445f5447 USING utf8mb4),6,CONVERT(0x6e6f74696669636174696f6e2e74656d706c6174652e6c5f77645f74672e636f6e74656e74 USING utf8mb4),CONVERT(0x7a68 USING utf8mb4),CONVERT(0xe794a8e688b7207b7b757365726e616d657d7d20e68f90e4baa4e68f90e6acbee8aea2e58d95207b7b6f726465724e6f7d7defbc8ce794b3e8afb7e680bbe9a29d207b7b616d6f756e747d7d207b7b63757272656e63797d7de38082 USING utf8mb4),CONVERT(0xe794a8e688b73a203c636f64653e7b7b757365726e616d657d7d3c2f636f64653e0ae68f90e6acbee8aea2e58d953a203c636f64653e7b7b6f726465724e6f7d7d3c2f636f64653e0ae794b3e8afb7e680bbe9a29d3a203c623e7b7b616d6f756e747d7d207b7b63757272656e63797d7d3c2f623e USING utf8mb4)),
      (CONVERT(0x4c5f57445f5447 USING utf8mb4),6,CONVERT(0x6e6f74696669636174696f6e2e74656d706c6174652e6c5f77645f74672e636f6e74656e74 USING utf8mb4),CONVERT(0x656e USING utf8mb4),CONVERT(0x7b7b757365726e616d657d7d207375626d6974746564207769746864726177616c207b7b6f726465724e6f7d7d20666f72206120746f74616c206f66207b7b616d6f756e747d7d207b7b63757272656e63797d7d2e USING utf8mb4),CONVERT(0x557365723a203c636f64653e7b7b757365726e616d657d7d3c2f636f64653e0a5769746864726177616c206f726465723a203c636f64653e7b7b6f726465724e6f7d7d3c2f636f64653e0a52657175657374656420746f74616c3a203c623e7b7b616d6f756e747d7d207b7b63757272656e63797d7d3c2f623e USING utf8mb4)),
      (CONVERT(0x57445f4c4154455f5447 USING utf8mb4),6,CONVERT(0x6e6f74696669636174696f6e2e74656d706c6174652e77645f6c6174655f74672e636f6e74656e74 USING utf8mb4),CONVERT(0x7a68 USING utf8mb4),CONVERT(0xe794a8e688b7207b7b757365726e616d657d7d20e79a84e68f90e6acbee8aea2e58d95207b7b6f726465724e6f7d7d20e5b7b2e7ad89e5be85e5aea1e6a0b8e8b685e8bf87207b7b6d696e757465737d7d20e58886e9929fe38082 USING utf8mb4),CONVERT(0xe794a8e688b73a203c636f64653e7b7b757365726e616d657d7d3c2f636f64653e0ae68f90e6acbee8aea2e58d953a203c636f64653e7b7b6f726465724e6f7d7d3c2f636f64653e0ae5b7b2e7ad89e5be85e5aea1e6a0b8e8b685e8bf87203c623e7b7b6d696e757465737d7d3c2f623e20e58886e9929fe38082 USING utf8mb4)),
      (CONVERT(0x57445f4c4154455f5447 USING utf8mb4),6,CONVERT(0x6e6f74696669636174696f6e2e74656d706c6174652e77645f6c6174655f74672e636f6e74656e74 USING utf8mb4),CONVERT(0x656e USING utf8mb4),CONVERT(0x5769746864726177616c207b7b6f726465724e6f7d7d20666f72207b7b757365726e616d657d7d2068617320617761697465642072657669657720666f72206d6f7265207468616e207b7b6d696e757465737d7d206d696e757465732e USING utf8mb4),CONVERT(0x557365723a203c636f64653e7b7b757365726e616d657d7d3c2f636f64653e0a5769746864726177616c206f726465723a203c636f64653e7b7b6f726465724e6f7d7d3c2f636f64653e0a4177616974696e672072657669657720666f72206d6f7265207468616e203c623e7b7b6d696e757465737d7d3c2f623e206d696e757465732e USING utf8mb4)),
      (CONVERT(0x454e455247595f5447 USING utf8mb4),6,CONVERT(0x6e6f74696669636174696f6e2e74656d706c6174652e656e657267795f74672e636f6e74656e74 USING utf8mb4),CONVERT(0x7a68 USING utf8mb4),CONVERT(0x7b7b70726f76696465724e616d657d7d20e5bd93e5898de4bd99e9a29d207b7b62616c616e63657d7d207b7b63757272656e63797d7defbc8ce4bd8ee4ba8ee68f90e98692e99888e580bc207b7b7468726573686f6c647d7d207b7b63757272656e63797d7de38082 USING utf8mb4),CONVERT(0xe4be9be5ba94e595863a203c636f64653e7b7b70726f76696465724e616d657d7d3c2f636f64653e0ae5bd93e5898de4bd99e9a29d3a203c623e7b7b62616c616e63657d7d207b7b63757272656e63797d7d3c2f623e0ae68f90e98692e99888e580bc3a203c623e7b7b7468726573686f6c647d7d207b7b63757272656e63797d7d3c2f623e0ae4bd99e9a29de5b7b2e4bd8ee4ba8ee68f90e98692e99888e580bcefbc8ce8afb7e58f8ae697b6e8a1a5e58585e38082 USING utf8mb4)),
      (CONVERT(0x454e455247595f5447 USING utf8mb4),6,CONVERT(0x6e6f74696669636174696f6e2e74656d706c6174652e656e657267795f74672e636f6e74656e74 USING utf8mb4),CONVERT(0x656e USING utf8mb4),CONVERT(0x7b7b70726f76696465724e616d657d7d2068617320612062616c616e6365206f66207b7b62616c616e63657d7d207b7b63757272656e63797d7d2c2062656c6f772074686520616c657274207468726573686f6c64206f66207b7b7468726573686f6c647d7d207b7b63757272656e63797d7d2e USING utf8mb4),CONVERT(0x50726f76696465723a203c636f64653e7b7b70726f76696465724e616d657d7d3c2f636f64653e0a43757272656e742062616c616e63653a203c623e7b7b62616c616e63657d7d207b7b63757272656e63797d7d3c2f623e0a416c657274207468726573686f6c643a203c623e7b7b7468726573686f6c647d7d207b7b63757272656e63797d7d3c2f623e0a5468652062616c616e63652069732062656c6f772074686520616c657274207468726573686f6c642e20506c6561736520746f702075702e USING utf8mb4));

    IF DATABASE() IS NULL OR BINARY DATABASE()<>BINARY 'token' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：写入前数据库二次校验失败';
    END IF;
    START TRANSACTION;
    -- 与模板编辑共用行锁。只输出固定模板编码，不输出人工正文或通知接收信息。
    SELECT n.code AS locked_template_code FROM notification_template n JOIN token_20261018_06_template_seed s
        ON n.code=s.code COLLATE utf8mb4_unicode_ci ORDER BY n.id FOR UPDATE;
    IF EXISTS(SELECT 1 FROM notification_template n JOIN token_20261018_06_template_seed s
        ON n.code=s.code COLLATE utf8mb4_unicode_ci
        WHERE NOT(BINARY n.code<=>BINARY s.code) OR NOT(BINARY n.channel_type<=>BINARY 'TELEGRAM')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：固定模板code存在等价拼写或频道冲突';
    END IF;
    IF EXISTS(SELECT 1 FROM notification_template n JOIN token_20261018_06_template_seed s ON BINARY n.code=BINARY s.code
        GROUP BY BINARY n.code HAVING COUNT(*)>1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：固定模板code存在重复记录';
    END IF;
    -- UPDATE使用当前读：若编辑事务在等待行锁前已完成，不按旧一致性快照判断默认正文。
    UPDATE token_20261018_06_template_seed s JOIN notification_template n
        ON BINARY n.code=BINARY s.code AND BINARY n.channel_type=BINARY 'TELEGRAM'
    SET s.eligible=CASE WHEN BINARY n.content_template=BINARY s.old_content
        OR BINARY n.content_template=BINARY s.new_content THEN 1 ELSE 0 END;
    IF EXISTS(SELECT 1 FROM system_setting WHERE CONVERT(setting_key USING utf8mb4) COLLATE utf8mb4_unicode_ci
        =_utf8mb4'i18n.translations.revision' COLLATE utf8mb4_unicode_ci
        AND NOT(BINARY setting_key<=>BINARY 'i18n.translations.revision')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：内部词条版本键存在等价拼写冲突';
    END IF;
    SELECT COUNT(*) INTO v_revision_rows FROM system_setting WHERE BINARY setting_key=BINARY 'i18n.translations.revision';
    IF v_revision_rows>1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：内部词条版本键存在重复记录';
    END IF;
    IF v_revision_rows=1 THEN
        -- 锁定并读取完整原值后再校验，不截断、不隐式归一化或覆盖格式错误的人工值。
        SELECT setting_value,revision INTO v_revision,v_revision_number FROM system_setting
            WHERE BINARY setting_key=BINARY 'i18n.translations.revision' FOR UPDATE;
        IF EXISTS(SELECT 1 FROM system_setting WHERE BINARY setting_key=BINARY 'i18n.translations.revision'
            AND (NOT(BINARY value_type<=>BINARY 'string') OR NOT(is_public<=>0) OR NOT(is_editable<=>0))) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：已有词条版本必须是私有不可编辑的字符串';
        END IF;
        IF v_revision_number IS NULL OR v_revision_number<0 OR v_revision_number=9223372036854775807 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：配置revision无效或已达上限';
        END IF;
        IF v_revision IS NULL OR CHAR_LENGTH(v_revision)<>36
           OR NOT REGEXP_LIKE(CONVERT(v_revision USING utf8mb4),
              _utf8mb4'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$','c') THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：内部词条版本不是有效小写UUID';
        END IF;
    END IF;
    IF EXISTS(SELECT 1 FROM translates t JOIN token_20261018_06_translation_seed s
        ON CONVERT(t.tkey USING utf8mb4) COLLATE utf8mb4_general_ci=s.tkey COLLATE utf8mb4_general_ci
       AND CONVERT(t.lang USING utf8mb4) COLLATE utf8mb4_unicode_ci=s.lang COLLATE utf8mb4_unicode_ci
        WHERE NOT(BINARY t.tkey<=>BINARY s.tkey) OR NOT(BINARY t.lang<=>BINARY s.lang) OR NOT(t.type<=>s.template_type)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：正文词条存在等价拼写或模板类型冲突';
    END IF;
    IF EXISTS(SELECT 1 FROM translates t JOIN token_20261018_06_translation_seed s
        ON BINARY t.tkey=BINARY s.tkey AND BINARY t.lang=BINARY s.lang
        GROUP BY BINARY t.tkey,BINARY t.lang HAVING COUNT(*)>1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：正文词条存在多个版本，请先核对';
    END IF;
    IF EXISTS(SELECT 1 FROM translates t JOIN token_20261018_06_translation_seed s
        ON BINARY t.tkey=BINARY s.tkey AND BINARY t.lang=BINARY s.lang WHERE t.id<=7) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：正文词条占用受保护内置模板编号';
    END IF;

    SELECT s.code,CASE WHEN s.eligible=0 THEN '保留自定义或缺失模板，不补默认词条'
        WHEN BINARY n.content_template=BINARY s.old_content THEN '更新旧默认正文' ELSE '默认正文已更新' END AS planned_action
    FROM token_20261018_06_template_seed s LEFT JOIN notification_template n ON BINARY n.code=BINARY s.code
    ORDER BY s.code;

    UPDATE notification_template n JOIN token_20261018_06_template_seed s
        ON BINARY n.code=BINARY s.code AND BINARY n.channel_type=BINARY 'TELEGRAM'
    SET n.content_template=s.new_content,n.updated_at=UTC_TIMESTAMP()
    WHERE s.eligible=1 AND BINARY n.content_template=BINARY s.old_content;
    SET v_templates_updated=ROW_COUNT();
    UPDATE translates t JOIN token_20261018_06_translation_seed s
        ON t.type=s.template_type AND BINARY t.tkey=BINARY s.tkey AND BINARY t.lang=BINARY s.lang
    JOIN token_20261018_06_template_seed b ON BINARY b.code=BINARY s.code AND b.eligible=1
    SET t.tvalue=s.new_content,t.action_time=UTC_TIMESTAMP()
    WHERE t.id>7 AND (BINARY t.tvalue=BINARY s.old_content
        OR COALESCE(TRIM(REPLACE(REPLACE(REPLACE(t.tvalue,CHAR(9),''),CHAR(10),''),CHAR(13),'')),'')='');
    SET v_translations_updated=ROW_COUNT();
    INSERT INTO translates(tkey,tvalue,lang,type,prop,title,is_del,action_time)
    SELECT s.tkey,s.new_content,s.lang,s.template_type,2,'Telegram通知正文',0,UTC_TIMESTAMP()
    FROM token_20261018_06_translation_seed s JOIN token_20261018_06_template_seed b ON BINARY b.code=BINARY s.code AND b.eligible=1
    WHERE NOT EXISTS(SELECT 1 FROM translates t WHERE BINARY t.tkey=BINARY s.tkey AND BINARY t.lang=BINARY s.lang);
    SET v_inserted=ROW_COUNT();
    IF v_templates_updated+v_translations_updated+v_inserted>0 THEN
        IF v_revision_rows=0 THEN
            INSERT INTO system_setting(setting_key,setting_value,value_type,group_name,description,is_public,is_editable,updated_by,updated_at,revision)
            VALUES('i18n.translations.revision',LOWER(UUID()),'string','cache','翻译词条正文缓存版本：修改译文后更新',0,0,NULL,UTC_TIMESTAMP(6),1);
            SET v_revision_inserted=1;
        ELSE
            UPDATE system_setting SET setting_value=LOWER(UUID()),revision=revision+1,updated_at=UTC_TIMESTAMP(6)
            WHERE BINARY setting_key=BINARY 'i18n.translations.revision' AND BINARY setting_value=BINARY v_revision AND revision=v_revision_number;
            IF ROW_COUNT()<>1 THEN
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：内部词条版本已变化，正文迁移回滚';
            END IF;
            SET v_revision_advanced=1;
        END IF;
    END IF;
    IF EXISTS(SELECT 1 FROM token_20261018_06_translation_seed s JOIN token_20261018_06_template_seed b
        ON BINARY b.code=BINARY s.code AND b.eligible=1 WHERE NOT EXISTS(
        SELECT 1 FROM translates t WHERE t.type=s.template_type AND BINARY t.tkey=BINARY s.tkey AND BINARY t.lang=BINARY s.lang
        AND COALESCE(TRIM(REPLACE(REPLACE(REPLACE(t.tvalue,CHAR(9),''),CHAR(10),''),CHAR(13),'')),'')<>'')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='06拒绝：默认正文词条补缺不完整，已回滚';
    END IF;
    COMMIT;
    SELECT v_templates_updated AS templates_updated,v_translations_updated AS translations_updated,v_inserted AS translations_inserted,
        (SELECT COUNT(*) FROM token_20261018_06_template_seed WHERE eligible=0) AS custom_or_missing_templates_preserved,
        v_revision_inserted AS internal_version_inserted,v_revision_advanced AS internal_version_advanced;
    DROP TEMPORARY TABLE token_20261018_06_template_seed;
    DROP TEMPORARY TABLE token_20261018_06_translation_seed;
    DO RELEASE_LOCK('token:migration:20261018:06');
    SET v_locked=0;
END$$
CALL token_migrate_20261018_06_telegram_template_layout()$$
DROP PROCEDURE token_migrate_20261018_06_telegram_template_layout$$
DELIMITER ;
