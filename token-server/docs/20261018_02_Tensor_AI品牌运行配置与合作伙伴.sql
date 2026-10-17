-- 2026-10-18 Tensor AI 默认品牌、USD显示名称、两项运行数据及9家合作伙伴默认Logo。
-- 只修改明确白名单配置和供应方目录，不修改会员、余额、账本、模型或资金规则。
-- 运行开关/模式/同步周期、已有新指标配置、人工品牌/Logo、供应方名称/启停均保留。
-- 独立连接执行，遇首错停止并ROLLBACK，不使用--force。建议暂停后台配置编辑后执行。
-- 本文件仅交付，尚未执行。MySQL 8.0.12，无CHECK依赖，无DDL业务表修改。
USE token;
SET NAMES utf8mb4 COLLATE utf8mb4_bin;
SET time_zone = '+00:00';
SET @tensor_config_old_isolation = @@SESSION.transaction_isolation;
SET SESSION TRANSACTION ISOLATION LEVEL SERIALIZABLE;

CREATE TEMPORARY TABLE tensor_config_guard (verified TINYINT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO tensor_config_guard VALUES (1);
INSERT INTO tensor_config_guard SELECT 1 WHERE NOT (BINARY DATABASE()=BINARY 'token');
INSERT INTO tensor_config_guard SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM information_schema.tables
 WHERE BINARY table_schema=BINARY 'token' AND table_name IN ('system_setting','compute_provider') AND engine='InnoDB')=2);
INSERT INTO tensor_config_guard SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM information_schema.columns
 WHERE BINARY table_schema=BINARY 'token' AND table_name='system_setting'
 AND column_name IN ('setting_key','setting_value','group_name','value_type','description','revision','updated_at'))=7);
INSERT INTO tensor_config_guard SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM information_schema.columns
 WHERE BINARY table_schema=BINARY 'token' AND table_name='compute_provider'
 AND column_name IN ('id','code','name','image_url','enabled','created_at','updated_at'))=7);
INSERT INTO tensor_config_guard SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM information_schema.statistics
 WHERE BINARY table_schema=BINARY 'token' AND table_name='compute_provider' AND non_unique=0
 AND ((index_name='uk_provider_code' AND column_name='code' AND seq_in_index=1)
 OR (index_name='uk_provider_name' AND column_name='name' AND seq_in_index=1)))=2);
INSERT INTO tensor_config_guard SELECT 1 WHERE NOT ((SELECT COUNT(*) FROM information_schema.statistics
 WHERE BINARY table_schema=BINARY 'token' AND table_name='compute_provider'
 AND index_name IN ('uk_provider_code','uk_provider_name') AND sub_part IS NULL)=2);

CREATE TEMPORARY TABLE tensor_partner_seed (
 code VARCHAR(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
 name VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 alias_code VARCHAR(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 logo VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL
) ENGINE=InnoDB;
INSERT INTO tensor_partner_seed VALUES
 ('openai','OpenAI','openai','/api/compute/public/partner-logos/openai.svg?v=20261018'),
 ('google','Google','google','/api/compute/public/partner-logos/google.svg?v=20261018'),
 ('microsoft','Microsoft','microsoft','/api/compute/public/partner-logos/microsoft.svg?v=20261018'),
 ('meta','Meta','facebook','/api/compute/public/partner-logos/meta.svg?v=20261018'),
 ('apple','Apple','apple','/api/compute/public/partner-logos/apple.svg?v=20261018'),
 ('amazon','Amazon / AWS','aws','/api/compute/public/partner-logos/amazon.svg?v=20261018'),
 ('deepseek','DeepSeek','deepseek','/api/compute/public/partner-logos/deepseek.svg?v=20261018'),
 ('anthropic','Anthropic','anthropic','/api/compute/public/partner-logos/anthropic.svg?v=20261018'),
 ('xai','xAI','xai','/api/compute/public/partner-logos/xai.svg?v=20261018');

START TRANSACTION;
-- 大小写仅等价但不精确的配置键不得造成插入冲突或误改。
INSERT INTO tensor_config_guard SELECT 1 WHERE EXISTS (SELECT 1 FROM token.system_setting
 WHERE CONVERT(setting_key USING utf8mb4) COLLATE utf8mb4_unicode_ci='userPresentation' COLLATE utf8mb4_unicode_ci
 AND NOT (BINARY setting_key=BINARY 'userPresentation'));
INSERT INTO token.system_setting(setting_key,setting_value,group_name,value_type,description)
 SELECT 'userPresentation','{}','compute','json','用户端展示配置'
 WHERE NOT EXISTS (SELECT 1 FROM token.system_setting WHERE BINARY setting_key=BINARY 'userPresentation');
SELECT setting_value INTO @tensor_presentation FROM token.system_setting
 WHERE BINARY setting_key=BINARY 'userPresentation' FOR UPDATE;
INSERT INTO tensor_config_guard SELECT 1 WHERE COALESCE(JSON_VALID(@tensor_presentation),0)<>1;
INSERT INTO tensor_config_guard SELECT 1 WHERE JSON_TYPE(CAST(@tensor_presentation AS JSON))<>'OBJECT';
INSERT INTO tensor_config_guard SELECT 1 WHERE JSON_CONTAINS_PATH(@tensor_presentation,'one','$.runtime')=1
 AND JSON_TYPE(JSON_EXTRACT(@tensor_presentation,'$.runtime'))<>'OBJECT';
SET @tensor_runtime_defaults=JSON_OBJECT('enabled',FALSE,'mode','AUTO','refreshSeconds',60,'changeSeconds',2,
 'totalExaflops','5.0','exaflops','4.30','exaflopsMin','4.15','exaflopsMax','4.50',
 'onlineGpus',15000,'onlineGpusMin',10900,'onlineGpusMax',20000);
-- 缺字段或JSON null一次补默认，已有非空新配置优先；不使用会删null字段的JSON_MERGE_PATCH。
SET @tensor_runtime=COALESCE(JSON_EXTRACT(@tensor_presentation,'$.runtime'),JSON_OBJECT());
SET @tensor_runtime=IF(JSON_CONTAINS_PATH(@tensor_runtime,'one','$.enabled')=0 OR JSON_TYPE(JSON_EXTRACT(@tensor_runtime,'$.enabled'))='NULL', JSON_SET(@tensor_runtime,'$.enabled',JSON_EXTRACT(@tensor_runtime_defaults,'$.enabled')),@tensor_runtime);
SET @tensor_runtime=IF(JSON_CONTAINS_PATH(@tensor_runtime,'one','$.mode')=0 OR JSON_TYPE(JSON_EXTRACT(@tensor_runtime,'$.mode'))='NULL', JSON_SET(@tensor_runtime,'$.mode',JSON_EXTRACT(@tensor_runtime_defaults,'$.mode')),@tensor_runtime);
SET @tensor_runtime=IF(JSON_CONTAINS_PATH(@tensor_runtime,'one','$.refreshSeconds')=0 OR JSON_TYPE(JSON_EXTRACT(@tensor_runtime,'$.refreshSeconds'))='NULL', JSON_SET(@tensor_runtime,'$.refreshSeconds',JSON_EXTRACT(@tensor_runtime_defaults,'$.refreshSeconds')),@tensor_runtime);
SET @tensor_runtime=IF(JSON_CONTAINS_PATH(@tensor_runtime,'one','$.changeSeconds')=0 OR JSON_TYPE(JSON_EXTRACT(@tensor_runtime,'$.changeSeconds'))='NULL', JSON_SET(@tensor_runtime,'$.changeSeconds',JSON_EXTRACT(@tensor_runtime_defaults,'$.changeSeconds')),@tensor_runtime);
SET @tensor_runtime=IF(JSON_CONTAINS_PATH(@tensor_runtime,'one','$.totalExaflops')=0 OR JSON_TYPE(JSON_EXTRACT(@tensor_runtime,'$.totalExaflops'))='NULL', JSON_SET(@tensor_runtime,'$.totalExaflops',JSON_EXTRACT(@tensor_runtime_defaults,'$.totalExaflops')),@tensor_runtime);
SET @tensor_runtime=IF(JSON_CONTAINS_PATH(@tensor_runtime,'one','$.exaflops')=0 OR JSON_TYPE(JSON_EXTRACT(@tensor_runtime,'$.exaflops'))='NULL', JSON_SET(@tensor_runtime,'$.exaflops',JSON_EXTRACT(@tensor_runtime_defaults,'$.exaflops')),@tensor_runtime);
SET @tensor_runtime=IF(JSON_CONTAINS_PATH(@tensor_runtime,'one','$.exaflopsMin')=0 OR JSON_TYPE(JSON_EXTRACT(@tensor_runtime,'$.exaflopsMin'))='NULL', JSON_SET(@tensor_runtime,'$.exaflopsMin',JSON_EXTRACT(@tensor_runtime_defaults,'$.exaflopsMin')),@tensor_runtime);
SET @tensor_runtime=IF(JSON_CONTAINS_PATH(@tensor_runtime,'one','$.exaflopsMax')=0 OR JSON_TYPE(JSON_EXTRACT(@tensor_runtime,'$.exaflopsMax'))='NULL', JSON_SET(@tensor_runtime,'$.exaflopsMax',JSON_EXTRACT(@tensor_runtime_defaults,'$.exaflopsMax')),@tensor_runtime);
SET @tensor_runtime=IF(JSON_CONTAINS_PATH(@tensor_runtime,'one','$.onlineGpus')=0 OR JSON_TYPE(JSON_EXTRACT(@tensor_runtime,'$.onlineGpus'))='NULL', JSON_SET(@tensor_runtime,'$.onlineGpus',JSON_EXTRACT(@tensor_runtime_defaults,'$.onlineGpus')),@tensor_runtime);
SET @tensor_runtime=IF(JSON_CONTAINS_PATH(@tensor_runtime,'one','$.onlineGpusMin')=0 OR JSON_TYPE(JSON_EXTRACT(@tensor_runtime,'$.onlineGpusMin'))='NULL', JSON_SET(@tensor_runtime,'$.onlineGpusMin',JSON_EXTRACT(@tensor_runtime_defaults,'$.onlineGpusMin')),@tensor_runtime);
SET @tensor_runtime=IF(JSON_CONTAINS_PATH(@tensor_runtime,'one','$.onlineGpusMax')=0 OR JSON_TYPE(JSON_EXTRACT(@tensor_runtime,'$.onlineGpusMax'))='NULL', JSON_SET(@tensor_runtime,'$.onlineGpusMax',JSON_EXTRACT(@tensor_runtime_defaults,'$.onlineGpusMax')),@tensor_runtime);
SET @tensor_next=JSON_SET(@tensor_presentation,'$.runtime',CAST(@tensor_runtime AS JSON));
SET @tensor_unit=JSON_UNQUOTE(JSON_EXTRACT(@tensor_next,'$.unitName'));
SET @tensor_next=IF(@tensor_unit IS NULL OR JSON_TYPE(JSON_EXTRACT(@tensor_next,'$.unitName'))='NULL' OR TRIM(@tensor_unit)='' OR BINARY @tensor_unit IN (BINARY 'token',BINARY 'Token'),
 JSON_SET(@tensor_next,'$.unitName','USD'),@tensor_next);
UPDATE token.system_setting SET setting_value=@tensor_next,revision=revision+1,updated_at=UTC_TIMESTAMP(6)
 WHERE BINARY setting_key=BINARY 'userPresentation'
 AND NOT (CAST(setting_value AS JSON)=CAST(@tensor_next AS JSON));
SET @tensor_presentation_changed=ROW_COUNT();

UPDATE token.system_setting SET setting_value='Tensor AI',revision=revision+1,updated_at=UTC_TIMESTAMP(6)
 WHERE BINARY setting_key IN (BINARY 'site.name',BINARY 'user.site.name',BINARY 'user.share.title')
 AND BINARY setting_value IN (BINARY 'AI Vora',BINARY 'AI VORA',BINARY 'duotaivora',BINARY 'Token');
SET @tensor_names_changed=ROW_COUNT();
-- 仅解除已核实的旧默认素材覆盖，使新版静态品牌按实际部署BASE_URL生效。
UPDATE token.system_setting SET setting_value='',revision=revision+1,updated_at=UTC_TIMESTAMP(6)
 WHERE (BINARY setting_key=BINARY 'user.site.logo' AND BINARY setting_value IN
   (BINARY '/vora-mark.svg',BINARY '/api/material/image/mat_11caef240b774c52a34c413641643ab5.webp'))
 OR (BINARY setting_key=BINARY 'user.site.icon' AND BINARY setting_value IN
   (BINARY '/favicon.svg',BINARY '/api/material/image/mat_11caef240b774c52a34c413641643ab5.webp'));
SET @tensor_logos_changed=ROW_COUNT();

-- 同一品牌若已对应多个供应方，先停止让运营人员核对，不能猜测合并或移动模型关系。
INSERT INTO tensor_config_guard SELECT 1 WHERE EXISTS (
 SELECT s.code FROM tensor_partner_seed s JOIN token.compute_provider p ON
 CONVERT(LOWER(p.code) USING utf8mb4) COLLATE utf8mb4_bin IN (s.code,s.alias_code)
 OR CONVERT(p.name USING utf8mb4) COLLATE utf8mb4_unicode_ci=s.name COLLATE utf8mb4_unicode_ci
 OR (s.code='amazon' AND CONVERT(p.name USING utf8mb4) COLLATE utf8mb4_unicode_ci IN ('Amazon','AWS'))
 GROUP BY s.code HAVING COUNT(*)>1);
-- 同一供应方也不能同时被两个品牌命中，拒绝编码与名称归属冲突。
INSERT INTO tensor_config_guard SELECT 1 WHERE EXISTS (
 SELECT p.id FROM tensor_partner_seed s JOIN token.compute_provider p ON
 CONVERT(LOWER(p.code) USING utf8mb4) COLLATE utf8mb4_bin IN (s.code,s.alias_code)
 OR CONVERT(p.name USING utf8mb4) COLLATE utf8mb4_unicode_ci=s.name COLLATE utf8mb4_unicode_ci
 OR (s.code='amazon' AND CONVERT(p.name USING utf8mb4) COLLATE utf8mb4_unicode_ci IN ('Amazon','AWS'))
 GROUP BY p.id HAVING COUNT(DISTINCT s.code)>1);
UPDATE token.compute_provider p JOIN tensor_partner_seed s ON
 CONVERT(LOWER(p.code) USING utf8mb4) COLLATE utf8mb4_bin IN (s.code,s.alias_code)
 OR CONVERT(p.name USING utf8mb4) COLLATE utf8mb4_unicode_ci=s.name COLLATE utf8mb4_unicode_ci
 OR (s.code='amazon' AND CONVERT(p.name USING utf8mb4) COLLATE utf8mb4_unicode_ci IN ('Amazon','AWS'))
 SET p.image_url=s.logo,p.updated_at=UTC_TIMESTAMP(6) WHERE TRIM(COALESCE(p.image_url,''))='';
SET @tensor_partner_logos_filled=ROW_COUNT();
INSERT INTO token.compute_provider(code,name,image_url,enabled,created_at)
 SELECT s.code,s.name,s.logo,1,UTC_TIMESTAMP(6) FROM tensor_partner_seed s
 WHERE NOT EXISTS (SELECT 1 FROM token.compute_provider p WHERE
 CONVERT(LOWER(p.code) USING utf8mb4) COLLATE utf8mb4_bin IN (s.code,s.alias_code)
 OR CONVERT(p.name USING utf8mb4) COLLATE utf8mb4_unicode_ci=s.name COLLATE utf8mb4_unicode_ci
 OR (s.code='amazon' AND CONVERT(p.name USING utf8mb4) COLLATE utf8mb4_unicode_ci IN ('Amazon','AWS')));
SET @tensor_partners_inserted=ROW_COUNT();
COMMIT;

DROP TEMPORARY TABLE tensor_partner_seed;
DROP TEMPORARY TABLE tensor_config_guard;
SET SESSION transaction_isolation=@tensor_config_old_isolation;
SELECT JSON_OBJECT('presentationChanged',@tensor_presentation_changed,'namesChanged',@tensor_names_changed,
 'logosChanged',@tensor_logos_changed,'partnerLogosFilled',@tensor_partner_logos_filled,'partnersInserted',@tensor_partners_inserted) AS tensor_configuration_result;
