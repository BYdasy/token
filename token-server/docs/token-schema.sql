USE token;
-- Token 当前结构，无账号、密码、订单或业务数据。
SET NAMES utf8mb4;
SET time_zone='+00:00';
SET FOREIGN_KEY_CHECKS=0;

CREATE TABLE `ad_master` (
  `uid` int(11) NOT NULL AUTO_INCREMENT COMMENT '后台账号编号',
  `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '后台登录密码BCrypt散列',
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '管理员登录用户名，大小写不敏感且唯一',
  `role` int(11) DEFAULT NULL COMMENT '后台角色编号',
  `state` int(11) DEFAULT NULL COMMENT '启用状态',
  `avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '头像素材地址',
  `tag` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '账号标签',
  `locale` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '管理员语言标签',
  `time_zone` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '账号独立展示时区，空值使用系统默认时区',
  `phone` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '管理员联系电话',
  `contact_email` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '管理员联系邮箱',
  `remark` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '管理员备注',
  `country` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '管理员保存的国家或地区摘要',
  `login_count` int(11) DEFAULT NULL COMMENT '管理员累计登录次数',
  `last_login_ip` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '管理员最近登录 IP',
  `last_login_client` varchar(100) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '管理员最近登录客户端',
  `profile_updated_at` datetime(6) DEFAULT NULL COMMENT '资料及登录摘要更新时间，UTC，非登录发生时间',
  `scope_configured_at` datetime(6) DEFAULT NULL COMMENT '会员范围配置时间；空为全局，非空无根节点为禁止访问',
  PRIMARY KEY (`uid`) USING BTREE,
  UNIQUE KEY `uk_admin_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='后台管理员账号';

CREATE TABLE `ad_record` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `uid` int(11) DEFAULT NULL COMMENT '后台账号编号',
  `login_ip` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '管理员登录或操作来源IP',
  `clientos` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '管理员登录或操作设备类型',
  `actiontime` datetime DEFAULT NULL COMMENT '操作审计时间，数据库统一UTC',
  `domain` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '操作来源域名，不保存URL查询或凭据',
  `address` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '地址',
  `status` int(11) DEFAULT NULL COMMENT '操作结果：0失败，1成功',
  `remarks` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '脱敏后的操作摘要，不保存密码、令牌、密钥或完整业务正文',
  `device_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '设备id',
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '账号名称',
  `type` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '记录类型',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_uid_ad_record` (`uid`) USING BTREE,
  KEY `idx_ad_record_actiontime_id` (`actiontime`,`id`) USING BTREE,
  KEY `idx_ad_record_type_actiontime_id` (`type`,`actiontime`,`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='后台操作审计日志';

CREATE TABLE `admin_login_session` (
  `token_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '后台完整访问凭据SHA256摘要，禁止保存明文令牌',
  `admin_uid` int(11) NOT NULL COMMENT '后台账号UID，关联ad_master.uid；不作为业务展示编号',
  `credential_hash` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '签发时账号密码散列快照；密码变更立即使会话失效',
  `created_at` datetime(6) NOT NULL COMMENT '该凭据签发时间，UTC',
  `last_active_at` datetime(6) NOT NULL COMMENT '最近一次成功的主动业务请求时间，UTC；后台轮询和SSE不更新',
  `expires_at` datetime(6) NOT NULL COMMENT '空闲到期时间，UTC；有效主动请求按当前配置向后滑动',
  `absolute_expires_at` datetime(6) DEFAULT NULL COMMENT '旧JWT或TOTP绑定短凭据的绝对截止时间，UTC；新空闲sid会话为空',
  `revoked_at` datetime(6) DEFAULT NULL COMMENT '退出或撤销时间，UTC；墓碑防止旧JWT重新登记复活',
  PRIMARY KEY (`token_hash`) USING BTREE,
  KEY `idx_admin_session_account` (`admin_uid`) USING BTREE,
  KEY `idx_admin_session_expiry` (`expires_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='后台持久登录会话与撤销记录；身份和空闲期限每次直接校验';

CREATE TABLE `admin_security` (
  `admin_uid` int(11) NOT NULL COMMENT '管理员 UID，对应 ad_master.uid',
  `totp_secret_ciphertext` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'AES-GCM 加密的 TOTP 密钥',
  `totp_enabled` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否已完成 TOTP 绑定',
  `totp_required` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否强制 TOTP 验证',
  `totp_last_used_counter` bigint(20) DEFAULT NULL COMMENT '已消费的 TOTP 时间步计数，防止验证码重放',
  `totp_confirmed_at` datetime(6) DEFAULT NULL COMMENT 'TOTP 绑定确认时间，UTC',
  `failed_count` int(11) NOT NULL DEFAULT '0' COMMENT '本轮连续认证失败次数',
  `locked_until` datetime(6) DEFAULT NULL COMMENT '临时登录锁定截止时间，UTC',
  `invalid_before` datetime(6) DEFAULT NULL COMMENT '会话及在途认证失效边界，UTC',
  `login_attempt_updated_at` datetime(6) DEFAULT NULL COMMENT '登录失败状态最近更新时间，UTC',
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '安全记录最近更新时间，UTC',
  PRIMARY KEY (`admin_uid`),
  KEY `idx_admin_security_totp` (`totp_required`,`totp_enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理员统一安全状态';

CREATE TABLE `compute_activity` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `name` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '名称',
  `type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '记录类型',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '说明内容',
  `currency` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '货币资产代码',
  `enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否启用',
  `starts_at` datetime(6) NOT NULL COMMENT '开始时间，UTC',
  `ends_at` datetime(6) NOT NULL COMMENT '结束时间，UTC',
  `min_level` int(11) NOT NULL DEFAULT '1' COMMENT '最低会员等级编号',
  `min_deposit` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '最低累计充值金额',
  `min_active_principal` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '最低有效投入本金',
  `per_user_total_limit` int(11) NOT NULL DEFAULT '1' COMMENT '每位用户累计参与次数上限',
  `per_user_daily_limit` int(11) NOT NULL DEFAULT '1' COMMENT '每位用户每日参与次数上限',
  `total_stock` bigint(20) NOT NULL COMMENT '总名额或库存',
  `used_stock` bigint(20) NOT NULL DEFAULT '0' COMMENT '已使用名额或库存',
  `total_budget` decimal(28,8) NOT NULL COMMENT '活动总奖励预算',
  `used_budget` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '活动已使用奖励预算',
  `packet_mode` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'FIXED' COMMENT '红包金额模式',
  `min_reward` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '最小奖励金额',
  `max_reward` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '最大奖励金额',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(6) NOT NULL COMMENT '更新时间，UTC',
  `wheel_config` json DEFAULT NULL COMMENT '转盘规则配置：轮次、次数发放、分组和展示',
  `floating_enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '活动有效期间是否展示首页悬浮入口',
  `floating_image` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '首页悬浮入口图片地址，与活动正文图片独立',
  `floating_priority` int(11) NOT NULL DEFAULT '0' COMMENT '首页多活动悬浮排序优先级，数值越大越靠前',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `ix_activity_public` (`enabled`,`type`,`starts_at`,`ends_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='用户活动配置与预算库存';

CREATE TABLE `compute_activity_attempt_grant` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '充值阶梯次数凭据编号',
  `activity_id` bigint(20) NOT NULL COMMENT '活动编号，包含转盘与宝箱',
  `round_no` int(11) NOT NULL COMMENT '活动轮次',
  `source_member_id` bigint(20) NOT NULL COMMENT '产生资格的充值会员编号；兼容基线为受益会员',
  `beneficiary_member_id` bigint(20) NOT NULL COMMENT '获得次数的会员编号，首次发放后固定',
  `beneficiary_role` varchar(10) NOT NULL COMMENT 'SELF本人、INVITER直属邀请者、LEGACY旧资格基线',
  `threshold_amount` decimal(26,8) NOT NULL COMMENT '本次资格对应的Token阶梯门槛；旧基线为0',
  `awarded_count` bigint(20) NOT NULL COMMENT '本凭据确认的奖励次数',
  `deposit_amount` decimal(26,8) NOT NULL COMMENT '首次核验资格时成功充值的Token金额快照',
  `created_at` datetime NOT NULL COMMENT 'UTC凭据建立时间',
  `source_deposit_id` bigint(20) NOT NULL DEFAULT '0' COMMENT '宝箱单笔资格来源充值订单编号；累计及历史资格为0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_activity_attempt_source` (`activity_id`,`round_no`,`source_member_id`,`beneficiary_role`,`threshold_amount`,`source_deposit_id`),
  KEY `idx_activity_attempt_member` (`activity_id`,`round_no`,`beneficiary_member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='活动阶梯次数去重凭据与旧邀请资格基线，不能作为日志清理';

CREATE TABLE `compute_activity_invite_grant` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '邀请有效成员阶梯次数凭据编号',
  `activity_id` bigint(20) NOT NULL COMMENT '转盘活动编号',
  `round_no` int(11) NOT NULL COMMENT '发放所属活动轮次',
  `member_id` bigint(20) NOT NULL COMMENT '获得次数的直属邀请者编号',
  `member_threshold` bigint(20) NOT NULL COMMENT '本档要求的有效直属成员人数',
  `awarded_count` bigint(20) NOT NULL COMMENT '本档独立奖励的抽奖次数，不是累计额度',
  `qualified_count` bigint(20) NOT NULL COMMENT '本次核验的有效直属成员人数，最多取未发放档位要求的最高人数',
  `minimum_first_deposit` decimal(26,8) NOT NULL COMMENT '成员全历史首笔成功充值所需Token金额快照',
  `qualification_snapshot` json NOT NULL COMMENT '本档资格来源会员、首笔充值订单与固定Token金额快照',
  `created_at` datetime NOT NULL COMMENT 'UTC次数凭据创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_activity_invite_tier` (`activity_id`,`round_no`,`member_id`,`member_threshold`),
  KEY `idx_activity_invite_member` (`member_id`,`activity_id`,`round_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='转盘邀请有效成员人数阶梯发放凭据，历史不可作为日志清理';

CREATE TABLE `compute_activity_participation` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `activity_id` bigint(20) NOT NULL COMMENT '活动编号',
  `activity_name` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '参与时的活动名称',
  `activity_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '参与时的活动类型',
  `member_id` bigint(20) NOT NULL COMMENT '用户端账号编号',
  `request_id` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '调用方幂等请求编号',
  `prize_id` bigint(20) DEFAULT NULL COMMENT '奖品编号',
  `prize_name` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '中奖时的奖品名称',
  `amount` decimal(28,8) NOT NULL COMMENT '金额，币种以本行货币字段为准',
  `currency` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '货币资产代码',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '业务处理状态',
  `activity_snapshot` json NOT NULL COMMENT '参与时活动配置JSON快照',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  `order_no` varchar(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '独立唯一业务编号，与记录主键无关',
  `redemption_code` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '唯一兑奖码，历史即时奖励为空',
  `redemption_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT 'AUTO自动入账或MANUAL仅确认兑奖',
  `redeemed_at` datetime DEFAULT NULL COMMENT 'UTC兑奖时间',
  `redeemed_by` int(11) DEFAULT NULL COMMENT '兑奖后台账号',
  `wheel_round` int(11) DEFAULT NULL COMMENT '抽奖轮次，重置不删除历史',
  `reward_range_id` varchar(80) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '宝箱本次命中奖励区间标识，金额及配置快照以订单为准',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_activity_member_request` (`member_id`,`request_id`) USING BTREE,
  UNIQUE KEY `uk_v6_order_no` (`order_no`) USING BTREE,
  UNIQUE KEY `uk_activity_redemption_code` (`redemption_code`) USING BTREE,
  KEY `ix_activity_member` (`activity_id`,`member_id`,`created_at`) USING BTREE,
  KEY `ix_activity_record_member` (`member_id`,`created_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='用户活动参与和奖励记录';

CREATE TABLE `compute_activity_prize` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `activity_id` bigint(20) NOT NULL COMMENT '活动编号',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '名称',
  `amount` decimal(28,8) NOT NULL COMMENT '金额，币种以本行货币字段为准',
  `stock` bigint(20) NOT NULL COMMENT '奖品总库存',
  `used_stock` bigint(20) NOT NULL DEFAULT '0' COMMENT '已使用名额或库存',
  `sort_order` int(11) NOT NULL DEFAULT '0' COMMENT '显示排序值',
  `min_draw` int(11) NOT NULL DEFAULT '0' COMMENT '统一规则指定中奖抽数，零为不指定',
  `probability` decimal(13,6) NOT NULL DEFAULT '0.000000' COMMENT '相对概率权重，0不参与抽取',
  `image_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '奖品转盘图片',
  `display_image_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '中奖结果展示图片',
  `is_no_prize` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否未中奖奖位；金额必须为零，仅记录结果不生成待兑奖或账本',
  `auto_credit` tinyint(4) NOT NULL DEFAULT '0' COMMENT '中奖后自动入收益钱包；默认0保留人工兑奖，历史中奖记录不重算',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `ix_activity_prize` (`activity_id`,`sort_order`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='活动奖品及库存';

CREATE TABLE `compute_address` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `member_id` bigint(20) NOT NULL COMMENT '用户端账号编号',
  `label` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '收款资料标签',
  `currency` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '货币资产代码',
  `network` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '网络或银行通道代码',
  `address` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '收款地址或银行账号',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `updated_at` datetime(6) DEFAULT NULL COMMENT '更新时间，UTC',
  `removed` tinyint(1) NOT NULL DEFAULT '0' COMMENT '用户是否移除该记录',
  `memo` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '链上备注或标签',
  `fields_json` json DEFAULT NULL COMMENT '结构化收款资料JSON',
  `address_key` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '网络归属范围、规范地址及Memo摘要；EVM三链统一范围',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_member_address` (`member_id`,`currency`,`network`,`address`,`memo`) USING BTREE,
  KEY `idx_address_owner` (`address_key`,`removed`,`member_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='用户提款收款地址和银行资料';

CREATE TABLE `compute_address_guard` (
  `guard_key` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '链归属范围、规范地址与Memo的SHA256摘要',
  `created_at` datetime(6) NOT NULL COMMENT '首次使用该归属锁的UTC时间',
  PRIMARY KEY (`guard_key`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='跨用户提款地址绑定的事务互斥锁，不保存私钥';

CREATE TABLE `compute_admin_member_scope` (
  `admin_uid` int(11) NOT NULL COMMENT '后台管理员账号编号',
  `root_member_id` bigint(20) NOT NULL COMMENT '绑定团队入口的真实用户编号',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  PRIMARY KEY (`admin_uid`,`root_member_id`) USING BTREE,
  KEY `idx_compute_scope_root` (`root_member_id`,`admin_uid`) USING BTREE,
  CONSTRAINT `compute_admin_member_scope_ibfk_1` FOREIGN KEY (`root_member_id`) REFERENCES `compute_member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='后台账号绑定真实用户团队的多根范围';

CREATE TABLE `compute_admin_table_columns` (
  `admin_uid` int(11) NOT NULL COMMENT '后台账号编号',
  `page_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '页面标识',
  `columns_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '依次排列的栏位键数组，不保存业务数据',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'UTC 更新时间',
  PRIMARY KEY (`admin_uid`,`page_key`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin ROW_FORMAT=DYNAMIC COMMENT='后台账号个人栏位配置';

CREATE TABLE `compute_auto_contract` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '自动算力协议主键',
  `order_no` varchar(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '独立唯一的自动算力业务订单号，与用户编号及主键无关',
  `member_id` bigint(20) NOT NULL COMMENT '协议用户，关联compute_member.id',
  `level_id` int(11) NOT NULL COMMENT '执行时VIP等级ID，对应compute_level.id',
  `level_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '激活时VIP名称快照',
  `request_id` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '客户端幂等请求号，同用户相同请求只激活一次',
  `currency` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '协议计价币种快照',
  `amount` decimal(26,8) NOT NULL COMMENT 'Token计算基数快照；引擎3为算力钱包可用额且不扣不冻结，历史引擎按原本金规则',
  `daily_rate` decimal(14,8) NOT NULL COMMENT '收益百分比快照：DAILY为日利率，CLICK为单次点击收益率',
  `rule_snapshot` json NOT NULL COMMENT 'VIP自动算力完整规则快照，配置变更不追溯',
  `expected_income` decimal(26,8) NOT NULL COMMENT '规则快照预计收益：DAILY为全周期，CLICK为单次执行',
  `paid_income` decimal(26,8) NOT NULL DEFAULT '0.00000000' COMMENT '已结算至收益钱包的累计收益',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ACTIVE' COMMENT 'RUNNING算力执行中、ACTIVE每日协议计息中、COMPLETED已结算完成；历史状态保持原值',
  `starts_at` datetime(6) NOT NULL COMMENT 'UTC开始时间：新DAILY在执行结果可用时开始计息，CLICK为提交时间；历史协议保持原开始时间',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  `result_available_at` datetime(6) DEFAULT NULL COMMENT 'UTC结果可用时间：引擎3仅算力使用明细生成时间，不延迟收益；引擎2保留原执行到期语义',
  `execution_finished_at` datetime(6) DEFAULT NULL COMMENT '本次资金结算成功时间，UTC；引擎3在点击时即时入账，使用明细是否完成另见details_status',
  `execution_result` json DEFAULT NULL COMMENT '运行明细JSON快照：模型/供应方、分配比例、Token总用量及输入输出分量、调用批次、生成时间；来源保留模拟标记，不参与钱包和收益计算',
  `execution_time_zone` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '提交执行时使用的系统 IANA 时区快照，供限额与运行详情核验；时间字段仍保存 UTC',
  `animation_ends_at` datetime(6) DEFAULT NULL COMMENT '用户端收益展示效果结束时间，UTC；只控制动画，不影响已即时发放的收益',
  `details_status` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '第九版算力使用明细状态：PENDING待生成，READY已生成；为空为历史引擎，独立于资金结算状态',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_auto_order_no` (`order_no`) USING BTREE,
  UNIQUE KEY `uk_auto_request` (`member_id`,`request_id`) USING BTREE,
  KEY `idx_auto_due` (`status`,`starts_at`) USING BTREE,
  KEY `idx_auto_member` (`member_id`,`created_at`) USING BTREE,
  KEY `idx_v8_auto_due` (`status`,`result_available_at`) USING BTREE,
  KEY `idx_v9_usage_due` (`details_status`,`result_available_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='VIP 独立算力协议与执行记录；历史每日结算及单次执行均通过幂等账本记账';

CREATE TABLE `compute_auto_settlement` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '自动算力结算主键',
  `order_no` varchar(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '独立唯一的自动算力结算流水号',
  `contract_id` bigint(20) NOT NULL COMMENT '自动算力协议，关联compute_auto_contract.id',
  `member_id` bigint(20) NOT NULL COMMENT '结算用户，关联compute_member.id',
  `settlement_day` int(11) NOT NULL COMMENT '结算幂等序号：历史DAILY为计息天数，CLICK固定1；引擎3代表点击即时结算，不代表额外周期',
  `settlement_date` date NOT NULL COMMENT '结算归属日期：历史协议保持UTC，新引擎按提交执行时的系统时区快照计算',
  `income` decimal(26,8) NOT NULL COMMENT '本次发放收益',
  `principal` decimal(26,8) NOT NULL COMMENT '本次返还收益钱包的本金；不返本模式及CLICK为0，CLICK的Token释放在钱包账本记录',
  `created_at` datetime(6) NOT NULL COMMENT '结算记录创建时间，UTC',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_auto_settlement_no` (`order_no`) USING BTREE,
  UNIQUE KEY `uk_auto_settlement_day` (`contract_id`,`settlement_day`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='VIP自动算力逐期结算明细';

CREATE TABLE `compute_checkin` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '签到记录内部主键',
  `order_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '独立唯一签到业务编号',
  `member_id` bigint(20) NOT NULL COMMENT '签到用户，关联compute_member.id',
  `checkin_date` date NOT NULL COMMENT '签到时系统时区对应自然日，历史日期不随时区变更',
  `system_time_zone` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '签到时采用的系统IANA时区快照',
  `streak_days` int(11) NOT NULL COMMENT '本次连续签到天数，中断后从一重新累计',
  `reward_token` decimal(28,8) NOT NULL COMMENT '日奖励与连续奖励合计，单位Token',
  `currency` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '收益钱包原币种编码',
  `reward_amount` decimal(28,8) NOT NULL COMMENT '本次实际入账原币金额',
  `asset_rate` decimal(28,18) NOT NULL COMMENT '入账时每单位原币对应Token汇率快照；零奖励无需换算时记0',
  `created_at` datetime(6) NOT NULL COMMENT '签到入账UTC时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_checkin_order` (`order_no`) USING BTREE,
  UNIQUE KEY `uk_checkin_member_day` (`member_id`,`checkin_date`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='每日签到及连续奖励记录，测试用户正常留痕，资金与原账本同事务';

CREATE TABLE `compute_company_file` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '公司文件编号',
  `file_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户可见的PDF文件名，不作为磁盘路径',
  `stored_name` varchar(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '随机私有存储文件名，仅服务端使用',
  `file_size` bigint(20) unsigned NOT NULL COMMENT '已验证PDF文件大小，单位字节',
  `enabled` tinyint(3) unsigned NOT NULL DEFAULT '1' COMMENT '是否启用，1启用0停用；停用保留文件',
  `sort_no` int(10) unsigned NOT NULL DEFAULT '0' COMMENT '展示排序，升序；同序按编号升序',
  `version` bigint(20) unsigned NOT NULL DEFAULT '1' COMMENT '编辑版本号，用于并发更新复核',
  `created_by` int(10) unsigned NOT NULL COMMENT '上传管理员编号，仅用于审计',
  `updated_by` int(10) unsigned NOT NULL COMMENT '最后编辑管理员编号，仅用于审计',
  `created_at` datetime(6) NOT NULL COMMENT '上传时间UTC',
  `updated_at` datetime(6) NOT NULL COMMENT '最近编辑时间UTC',
  `resource_type` varchar(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PDF' COMMENT '公司资源类型：PDF私有文件或LINK外部链接',
  `external_url` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '外部HTTP(S)链接，仅LINK使用，不由服务端下载',
  `names_json` json DEFAULT NULL COMMENT '公司资源多语言显示名称，语言代码到名称的映射，缺失回退原文件名',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_compute_company_file_storage` (`stored_name`) USING BTREE COMMENT '随机存储名称不可重复',
  KEY `idx_compute_company_file_visible` (`enabled`,`sort_no`,`id`) USING BTREE COMMENT '启用公司文件有序读取'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='公司PDF文件目录，私有存储并按实时启停状态授权读取';

CREATE TABLE `compute_content` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `kind` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '业务类别',
  `title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标题',
  `body_html` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '安全HTML正文',
  `translations_json` json DEFAULT NULL COMMENT '按语言保存的翻译JSON',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `pinned` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否置顶',
  `show_home` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否在用户首页展示',
  `sort` int(11) NOT NULL DEFAULT '0' COMMENT '显示排序值',
  `jump_url` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '跳转地址',
  `starts_at` datetime(6) DEFAULT NULL COMMENT '开始时间，UTC',
  `ends_at` datetime(6) DEFAULT NULL COMMENT '结束时间，UTC',
  `task_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'GENERAL' COMMENT '用户任务类型',
  `target_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ALL' COMMENT '内容接收对象类型',
  `member_ids` json DEFAULT NULL COMMENT '指定接收用户编号JSON',
  `group_id` bigint(20) DEFAULT NULL COMMENT '用户分组编号，空表示未分组',
  `created_by` int(11) NOT NULL COMMENT '创建记录的后台账号编号',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT '内容状态：草稿、已发布或召回',
  `recipient_count` int(11) NOT NULL DEFAULT '0' COMMENT '已投递接收人数',
  `read_count` int(11) NOT NULL DEFAULT '0' COMMENT '已阅读人数',
  `completed_count` int(11) NOT NULL DEFAULT '0' COMMENT '已完成任务人数',
  `processed_id` bigint(20) NOT NULL DEFAULT '0' COMMENT '批量处理已完成的用户编号游标',
  `audience_max_id` bigint(20) DEFAULT NULL COMMENT '发布时锁定的接收用户编号上限',
  `error_key` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '可多语言显示的错误消息键',
  `deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否逻辑删除',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(6) NOT NULL COMMENT '更新时间，UTC',
  `content_version` bigint(20) NOT NULL DEFAULT '1' COMMENT '中文标题和正文版本',
  `owner_member_id` bigint(20) DEFAULT NULL COMMENT '配置归属的真实用户编号',
  `target_member_id` bigint(20) DEFAULT NULL COMMENT '目标团队入口的真实用户编号',
  `source_language` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'zh' COMMENT '原文语言标签',
  `task_mode` int(11) DEFAULT NULL COMMENT '1投资2提款3投资提款4个人投资提款5个人投资6个人提款',
  `content_key` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'GENERAL' COMMENT '平台文章用途：GENERAL普通文章、TERMS用户协议、PRIVACY隐私说明、ABOUT关于我们、FAQ常见问题',
  `fixed_content_key` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci GENERATED ALWAYS AS ((case when ((`deleted` = 0) and (`kind` = _utf8mb4'ARTICLE') and (`content_key` in (_utf8mb4'TERMS',_utf8mb4'PRIVACY',_utf8mb4'ABOUT'))) then `content_key` else NULL end)) STORED COMMENT '有效固定系统文章唯一键：用户协议、隐私政策、关于我们；其他文章及历史已删除记录为空',
  `home_popup_enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否在首页排队弹窗；公告、信息和用户任务分别配置，弹窗正文复用当前多语言内容，与首页滚动展示独立',
  `jump_mode` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NEW_WINDOW' COMMENT '内容链接打开方式：NEW_WINDOW新窗口，INTERNAL站内路由',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_compute_content_fixed` (`fixed_content_key`) USING BTREE,
  KEY `idx_compute_content_list` (`kind`,`deleted`,`status`,`created_at`) USING BTREE,
  KEY `idx_compute_content_worker` (`status`,`updated_at`) USING BTREE,
  KEY `idx_compute_content_member_owner` (`owner_member_id`,`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='用户消息任务公告文章内容';

CREATE TABLE `compute_content_popup_seen` (
  `content_id` bigint(20) NOT NULL COMMENT '已展示内容编号，关联 compute_content.id',
  `member_id` bigint(20) NOT NULL COMMENT '已确认首页弹窗的用户编号，关联 compute_member.id',
  `content_version` bigint(20) NOT NULL COMMENT '用户确认的内容版本；正文更新后新版本可以再次弹出',
  `seen_at` datetime(6) NOT NULL COMMENT '用户确认首页弹窗的时间，按 UTC 保存',
  PRIMARY KEY (`content_id`,`member_id`,`content_version`) USING BTREE,
  KEY `ix_content_popup_member` (`member_id`,`seen_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='首页弹窗确认凭据；与个人信已读及行为任务完成分离，重复确认幂等';

CREATE TABLE `compute_content_recipient` (
  `content_id` bigint(20) NOT NULL COMMENT '内容编号',
  `member_id` bigint(20) NOT NULL COMMENT '用户端账号编号',
  `read_at` datetime(6) DEFAULT NULL COMMENT '阅读时间，UTC',
  `completed_at` datetime(6) DEFAULT NULL COMMENT '任务完成时间，UTC',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  `restriction_released_at` datetime(6) DEFAULT NULL COMMENT '后台单独解除此用户任务限制的UTC时间，用户已读或确认不解除',
  `restriction_released_by` int(11) DEFAULT NULL COMMENT '后台单独解除任务限制的管理员编号，仅供审计',
  PRIMARY KEY (`content_id`,`member_id`) USING BTREE,
  KEY `idx_compute_recipient_member` (`member_id`,`content_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='内容接收阅读与任务完成记录';

CREATE TABLE `compute_content_translation_item` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `task_id` bigint(20) NOT NULL COMMENT '持久任务编号',
  `language` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '目标语言标签',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '业务处理状态',
  `attempts` int(11) NOT NULL DEFAULT '0' COMMENT '已尝试执行次数',
  `run_token` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '当前执行租约令牌',
  `lease_until` datetime(6) DEFAULT NULL COMMENT '当前执行租约到期时间，UTC',
  `retry_after` datetime(6) DEFAULT NULL COMMENT '允许重试时间，UTC',
  `error_key` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '可多语言显示的错误消息键',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(6) NOT NULL COMMENT '更新时间，UTC',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_content_translation_language` (`task_id`,`language`) USING BTREE,
  KEY `ix_content_translation_pending` (`status`,`retry_after`,`updated_at`) USING BTREE,
  KEY `ix_content_translation_lease` (`status`,`lease_until`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='用户内容按语言补译执行项';

CREATE TABLE `compute_content_translation_task` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `content_id` bigint(20) NOT NULL COMMENT '资源主键，按resource_type关联内容、产品、等级或表单快照表',
  `content_version` bigint(20) NOT NULL COMMENT '内容版本号',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '业务处理状态',
  `requested_by` int(11) DEFAULT NULL COMMENT '请求操作的后台账号编号',
  `source_title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '翻译源标题快照',
  `source_body` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '翻译源正文快照',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(6) NOT NULL COMMENT '更新时间，UTC',
  `resource_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'CONTENT' COMMENT '翻译资源类型：CONTENT业务内容、PRODUCT产品、LEVEL会员等级、FORM未保存表单快照',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_content_translation_version` (`resource_type`,`content_id`,`content_version`) USING BTREE,
  KEY `ix_content_translation_task` (`status`,`updated_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='用户内容、目录资源和未保存表单的版本翻译任务';

CREATE TABLE `compute_currency` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `currency` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '货币资产代码',
  `network` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '网络或银行通道代码',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `token_standard` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '资产代币标准',
  `token_contract` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '代币合约地址',
  `support_account_types` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'COMPUTE,PROJECT,EARNINGS' COMMENT '通道支持的钱包类型JSON',
  `deposit_enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否开放充值',
  `withdraw_enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否开放提款',
  `deposit_min` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '单笔充值下限',
  `withdraw_min` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '单笔提款下限',
  `withdraw_max` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '单笔提款上限，空表示不限制',
  `withdraw_fee_fixed` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '通道固定提款手续费',
  `withdraw_fee_rate` decimal(12,8) NOT NULL DEFAULT '0.00000000' COMMENT '通道比例手续费，1表示百分之百',
  `amount_scale` int(11) NOT NULL DEFAULT '8' COMMENT '金额展示小数位数，记账精度仍为8位',
  `need_memo` tinyint(1) NOT NULL DEFAULT '0' COMMENT '收款是否要求备注或标签',
  `fee_mode` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ADVANCED_RULE' COMMENT '手续费来源：高级规则或通道费用',
  `sort_no` int(11) NOT NULL DEFAULT '0' COMMENT '显示排序值',
  `remark` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '操作或配置备注',
  `created_at` datetime(6) DEFAULT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(6) DEFAULT NULL COMMENT '更新时间，UTC',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_currency_network` (`currency`,`network`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='资产与网络组合的收款提款通道';

CREATE TABLE `compute_currency_asset` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `asset_code` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '货币资产代码',
  `asset_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '货币资产名称',
  `asset_type` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'CRYPTO' COMMENT '货币资产类别',
  `icon_url` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '图标素材地址',
  `amount_scale` int(11) NOT NULL DEFAULT '8' COMMENT '金额展示小数位数，记账精度仍为8位',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `sort_no` int(11) NOT NULL DEFAULT '0' COMMENT '显示排序值',
  `remark` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '操作或配置备注',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(6) NOT NULL COMMENT '更新时间，UTC',
  `coingecko_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT 'CoinGecko 唯一币种标识；为空不请求行情，稳定基准币固定为一美元',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `asset_code` (`asset_code`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='货币资产元数据';

CREATE TABLE `compute_currency_network` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `network_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '网络或银行通道代码',
  `network_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '网络或银行通道名称',
  `network_type` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'CRYPTO' COMMENT '网络类别',
  `chain_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'OTHER' COMMENT '链地址或银行资料校验类别',
  `native_asset_code` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '网络原生资产代码',
  `required_fields_json` json DEFAULT NULL COMMENT '必填收款资料字段JSON',
  `confirm_blocks` int(11) NOT NULL DEFAULT '0' COMMENT '所需链上确认区块数',
  `block_explorer_url` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '区块浏览器地址',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `sort_no` int(11) NOT NULL DEFAULT '0' COMMENT '显示排序值',
  `remark` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '操作或配置备注',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(6) NOT NULL COMMENT '更新时间，UTC',
  `icon_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '网络图标，支持外部HTTP图片链接或已上传素材地址',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `network_code` (`network_code`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='区块链及银行网络元数据';

CREATE TABLE `compute_deposit` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `member_id` bigint(20) NOT NULL COMMENT '用户端账号编号',
  `wallet_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '钱包类型：算力、项目或收益',
  `currency` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '货币资产代码',
  `network` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '网络或银行通道代码',
  `amount` decimal(50,30) NOT NULL COMMENT '金额，币种以本行货币字段为准',
  `status` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'PENDING' COMMENT '充值状态：待审核、通过或拒绝',
  `payment_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'MANUAL_REVIEW' COMMENT '充值付款核验状态',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '操作或配置备注',
  `reviewed_by` int(11) DEFAULT NULL COMMENT '审核后台账号编号',
  `reviewed_at` datetime(6) DEFAULT NULL COMMENT '审核时间，UTC',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  `record_source` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'USER' COMMENT '记录来源：USER用户申请、ADMIN_MANUAL后台手工添加',
  `created_by` int(11) DEFAULT NULL COMMENT '手工创建记录的后台账号UID；用户申请为空',
  `creation_remark` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '手工添加说明，与后续审核备注分开保留',
  `order_no` varchar(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '独立唯一业务编号，与记录主键无关',
  `sender_address` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '充值链上发送地址；人工或未取得链上凭据时为空',
  `receiver_address` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '充值链上接收地址快照；未分配真实收款地址时为空',
  `tx_hash` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '充值链上交易哈希；只保存实际链上凭据，人工记录为空',
  `chain_status` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'NOT_APPLICABLE' COMMENT '充值链上状态：NOT_APPLICABLE无链上操作，WAITING等待，BROADCAST已广播，CONFIRMED已确认，FAILED失败；独立于审核状态',
  `chain_confirmations` int(11) DEFAULT NULL COMMENT '充值链上已确认区块数量；未接入或未查询时为空',
  `chain_updated_at` datetime(6) DEFAULT NULL COMMENT '充值链上状态最近核验时间，按 UTC 保存',
  `external_order_no` varchar(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '支付供应商真实充值业务编号，人工充值为空',
  `receiver_memo` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '真实收款地址附言快照，无附言为空',
  `accounting_version` int(11) NOT NULL DEFAULT '1' COMMENT '计价版本：1保留历史原币义务，2为新业务USD统一计价；新业务显式写入2',
  `accounting_currency` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '业务记账币种，版本2固定为USD；原币币种仍保留在currency字段',
  `accounting_amount` decimal(28,8) DEFAULT NULL COMMENT '按固定汇率折合的USD总额：充值入账额或提款冻结扣款总额；是否已入账由计价状态决定',
  `accounting_rate` decimal(36,18) DEFAULT NULL COMMENT '固定计价汇率，1单位原币等于此数额USD；历史原币记录不补写现价',
  `accounting_quote_json` json DEFAULT NULL COMMENT '本笔USD计价报价快照，保留汇率来源和报价时间；不覆盖旧记录或重算历史金额',
  `accounting_status` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'LEGACY' COMMENT '充值计价状态：LEGACY历史原币、PENDING待入账、RATE_PENDING待汇率、AMOUNT_PRECISION金额精度异常、POSTED已入账',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_v6_order_no` (`order_no`) USING BTREE,
  KEY `ix_deposit_member` (`member_id`,`created_at`) USING BTREE,
  KEY `ix_deposit_review` (`status`,`created_at`) USING BTREE,
  KEY `ix_compute_deposit_currency_member` (`currency`,`member_id`,`status`) USING BTREE,
  KEY `idx_compute_deposit_accounting_state` (`accounting_status`,`status`,`id`) USING BTREE COMMENT '按计价状态和充值状态有序重试待计价订单'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='用户充值申请及审核记录';

CREATE TABLE `compute_exchange_rate` (
  `rate_type` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '汇率方向：ASSET表示一单位资产对应美元，FIAT表示一美元对应法币',
  `currency` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '资产或法币的标准编码，与汇率方向共同唯一定位',
  `rate` decimal(36,18) NOT NULL COMMENT '最近一次成功获取的正数汇率；异常或无效响应不得覆盖',
  `provider` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '成功汇率的数据来源：FRANKFURTER或COINGECKO',
  `source_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '行情供应方唯一币种标识或法币编码，不包含密钥',
  `source_at` datetime(6) NOT NULL COMMENT '供应方行情的时间或法币参考日期，转换为UTC保存',
  `refreshed_at` datetime(6) NOT NULL COMMENT '本系统最近成功保存此行情的UTC时间',
  PRIMARY KEY (`rate_type`,`currency`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='平台Token与美元固定一比一，外币使用最近成功行情及刷新状态';

CREATE TABLE `compute_exchange_refresh` (
  `provider` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '行情供应方编码，作为跨节点刷新租约唯一标识',
  `last_attempt_at` datetime(6) DEFAULT NULL COMMENT '最近尝试更新的UTC时间，失败仍保留用于排查',
  `last_success_at` datetime(6) DEFAULT NULL COMMENT '最近成功完成更新的UTC时间，失败不覆盖',
  `next_attempt_at` datetime(6) DEFAULT NULL COMMENT '下次尝试时间，UTC；加密行情30分钟，法币12小时，失败沿用各自周期',
  `last_status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'PENDING' COMMENT '刷新状态：PENDING待执行，RUNNING执行中，SUCCEEDED成功，FAILED失败，DISABLED停用，MISSING_KEY缺密钥，NO_ASSETS无启用资产，CONFIG_CHANGED配置已变化',
  PRIMARY KEY (`provider`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='汇率供应方三十分钟刷新租约及状态，不存储API密钥';

CREATE TABLE `compute_fee_rule` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '名称',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `priority` int(11) NOT NULL DEFAULT '0' COMMENT '匹配优先级，越大越优先',
  `member_id` bigint(20) DEFAULT NULL COMMENT '指定用户编号，空表示通用规则',
  `level` int(11) DEFAULT NULL COMMENT '指定会员等级编号，空表示通用规则',
  `currency` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '*' COMMENT '货币资产代码',
  `network` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '*' COMMENT '网络或银行通道代码',
  `min_amount` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '规则或产品金额下限',
  `max_amount` decimal(28,8) DEFAULT NULL COMMENT '规则或产品金额上限',
  `rate` decimal(12,6) NOT NULL DEFAULT '0.000000' COMMENT '手续费百分比，1表示百分之一',
  `fixed_fee` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '固定手续费',
  `minimum_fee` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '手续费最低收取金额',
  `free_count` int(11) NOT NULL DEFAULT '0' COMMENT '统计周期内免费次数',
  `period` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'MONTH' COMMENT '费用次数统计周期',
  `open_time` time DEFAULT NULL COMMENT '提款开始时间，按系统业务时区',
  `close_time` time DEFAULT NULL COMMENT '提款结束时间不包含边界，跨午夜归开始日，按系统业务时区',
  `free_start_time` time DEFAULT NULL COMMENT '免费时段开始时间，按系统业务时区',
  `free_end_time` time DEFAULT NULL COMMENT '免费时段结束时间，按系统业务时区',
  `updated_at` datetime(6) NOT NULL COMMENT '更新时间，UTC',
  `rule_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'GLOBAL' COMMENT '提款类型：GLOBAL全局、PERSONAL个人、LEVEL等级、CHANNEL币种与网络、AMOUNT金额阶梯',
  `open_weekdays` varchar(13) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '1,2,3,4,5,6,7' COMMENT '开放星期，ISO星期一至星期日为1至7，逗号分隔；按系统时区，跨午夜时段归开始日',
  `withdrawal_limit_mode` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'INHERIT' COMMENT '提款次数规则：INHERIT继承唯一全局配置，UNLIMITED不限制，DAY每天，WEEK每周',
  `withdrawal_limit_count` int(11) NOT NULL DEFAULT '1' COMMENT '用户每自然日或自然周最多提款次数，仅DAY和WEEK模式使用',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `ix_fee_rule_selection` (`enabled`,`priority`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='提款多维手续费规则';

CREATE TABLE `compute_finance_snapshot` (
  `business_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '原业务类型：DEPOSIT充值、WITHDRAWAL提款',
  `business_id` bigint(20) NOT NULL COMMENT '原业务表内部主键，仅用于同一订单幂等关联，不用于界面展示',
  `order_no` varchar(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '原业务独立订单号快照，保留历史长度与空值，不用数据库主键伪造编号',
  `member_id` bigint(20) NOT NULL COMMENT '原订单所属用户内部主键，沿用后台代理范围校验',
  `currency` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '原币标准编码，与支付订单原币一致',
  `native_amount` decimal(50,30) NOT NULL COMMENT '成功记账时的原币申请总额',
  `native_fee` decimal(28,8) NOT NULL COMMENT '成功记账时的原币手续费，充值为零',
  `native_net_amount` decimal(50,30) NOT NULL COMMENT '成功记账时原币净额，提款统计采用此净额',
  `usd_rate` decimal(38,18) DEFAULT NULL COMMENT '一单位原币对应美元的成功报价，缺行情为空且不得以后静默补价',
  `usd_amount` decimal(38,8) DEFAULT NULL COMMENT '按快照汇率折算的美元总额，稳定计价单位与美元一比一',
  `usd_fee` decimal(38,8) DEFAULT NULL COMMENT '按快照汇率折算的美元手续费',
  `usd_net_amount` decimal(38,8) DEFAULT NULL COMMENT '按快照汇率折算的美元净额，提款统计采用此字段',
  `rate_provider` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '汇率实际来源，固定基准FIXED、缺行情UNAVAILABLE或缓存供应方',
  `rate_source_at` datetime(6) DEFAULT NULL COMMENT '报价来源时间，UTC保存；缺少来源时间不得编造',
  `rate_refreshed_at` datetime(6) DEFAULT NULL COMMENT '该报价最近成功缓存的UTC时间',
  `snapshot_source` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '固定计价来源：订单快照、首次回填或USD_ACCOUNTING_V2入账快照',
  `posted_at` datetime(6) NOT NULL COMMENT '原订单成功记账UTC时间，旧单缺审核时间时使用原创建时间',
  `captured_at` datetime(6) NOT NULL COMMENT '本快照实际保存的UTC时间，历史基线与原记账时间明确区分',
  PRIMARY KEY (`business_type`,`business_id`) USING BTREE,
  KEY `idx_finance_snapshot_member_posted` (`member_id`,`posted_at`) USING BTREE,
  KEY `idx_finance_snapshot_posted` (`posted_at`,`business_type`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='财务成功记账的统一计价快照，历史基线明确标记且禁止随行情重估';

CREATE TABLE `compute_finance_sound_config` (
  `id` tinyint(3) unsigned NOT NULL COMMENT '全局唯一配置行，固定为1',
  `config_json` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '充值和提款独立开关、需关注状态、MP3数据及文件名；只用于后台提示',
  `revision` bigint(20) unsigned NOT NULL DEFAULT '0' COMMENT '配置乐观锁版本；客户端按版本获取，避免重复传输MP3',
  `updated_by` int(11) DEFAULT NULL COMMENT '最后修改的后台账号UID，初始配置为空',
  `updated_at` datetime(6) NOT NULL COMMENT 'UTC最后修改时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='全局后台充值提款声音提醒配置；不影响资金与审核流程';

CREATE TABLE `compute_job` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `job_code` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '调度任务代码',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '名称',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `cron_expression` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '调度Cron表达式',
  `next_run_at` datetime(6) NOT NULL COMMENT '下次执行时间，UTC',
  `last_run_at` datetime(6) DEFAULT NULL COMMENT '上次执行时间，UTC',
  `active_run_id` bigint(20) DEFAULT NULL COMMENT '当前活动执行记录编号',
  `schedule_time_zone` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '下一次定时执行时间所采用的系统IANA时区；为空或时区变更时重新计算，时间仍按UTC存储',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `job_code` (`job_code`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='用户业务统一调度任务配置';

CREATE TABLE `compute_job_run` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `job_id` bigint(20) NOT NULL COMMENT '调度任务编号',
  `job_code` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '调度任务代码',
  `status` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '业务处理状态',
  `trigger_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '触发来源类型',
  `requested_by` int(11) DEFAULT NULL COMMENT '请求操作的后台账号编号',
  `processed_count` int(11) NOT NULL DEFAULT '0' COMMENT '成功处理记录数',
  `error_key` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '可多语言显示的错误消息键',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  `started_at` datetime(6) DEFAULT NULL COMMENT '执行开始时间，UTC',
  `finished_at` datetime(6) DEFAULT NULL COMMENT '执行结束时间，UTC',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `ix_job_run_status` (`status`,`created_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='用户业务调度执行记录';

CREATE TABLE `compute_ledger` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `member_id` bigint(20) NOT NULL COMMENT '用户端账号编号',
  `wallet_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '钱包类型：算力、项目或收益',
  `currency` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '货币资产代码',
  `kind` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '业务类别',
  `amount` decimal(28,8) NOT NULL COMMENT '可用余额变动值，可正可负',
  `frozen_change` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '冻结余额变动值',
  `available_after` decimal(28,8) NOT NULL COMMENT '变动后可用余额',
  `frozen_after` decimal(28,8) NOT NULL COMMENT '变动后冻结余额',
  `reference_id` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '关联业务记录编号',
  `event_key` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '账本唯一业务事件键',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  `operator_uid` int(11) DEFAULT NULL COMMENT '操作后台账号编号',
  `remark` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '操作或配置备注',
  `available_before` decimal(28,8) DEFAULT NULL COMMENT '变动前可用余额',
  `frozen_before` decimal(28,8) DEFAULT NULL COMMENT '变动前冻结余额',
  `order_no` varchar(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '独立唯一业务编号，与记录主键无关',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_ledger_event` (`member_id`,`event_key`) USING BTREE,
  UNIQUE KEY `uk_v6_order_no` (`order_no`) USING BTREE,
  KEY `ix_ledger_member_date` (`member_id`,`created_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='用户三钱包资金变动账本';

CREATE TABLE `compute_level` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '名称',
  `threshold` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '自动升级所需COMPUTE钱包可用Token余额门槛，不含冻结或展示赠送',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `image_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '会员等级图片素材地址',
  `description` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '会员等级原文说明',
  `translations_json` json DEFAULT NULL COMMENT '会员等级多语言标题与HTML说明',
  `content_version` bigint(20) NOT NULL DEFAULT '1' COMMENT '会员等级内容版本',
  `source_language` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'zh' COMMENT '原文语言标签',
  `auto_enabled` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否允许本等级用户激活自动算力权益',
  `vip_validity_days` int(11) NOT NULL DEFAULT '0' COMMENT 'VIP 等级有效自然天数，按系统时区计算；0 表示长期有效',
  `vip_cycle_policy` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'RESET' COMMENT '变更到此等级时的周期策略：RESET重算起止时间，KEEP保留原周期含长期有效',
  `auto_display_seconds` int(11) NOT NULL DEFAULT '15' COMMENT '收益展示效果秒数，1至60；第九版仅动画，收益已在点击接受时入账',
  `auto_daily_limit` int(11) NOT NULL DEFAULT '1' COMMENT '每个系统自然日最多成功提交的 VIP 执行次数，1至1000次，失败提交不计次',
  `auto_cooldown_seconds` int(11) NOT NULL DEFAULT '0' COMMENT '每日次数用尽后的冷却秒数，0至31536000；后台按小时编辑，执行取冷却结束与系统次日零点中较晚时间',
  `auto_click_rate_min` decimal(14,8) NOT NULL DEFAULT '0.00000000' COMMENT '单次点击最低收益百分比，按本次全部可参与 Token 计算，不是日利率',
  `auto_click_rate_max` decimal(14,8) NOT NULL DEFAULT '0.00000000' COMMENT '单次点击最高收益百分比，每次接受执行时抽取并固定在规则快照',
  `reward_rules_json` json DEFAULT NULL COMMENT 'VIP按真实邀请层级配置首充、累计充值、Token收益、项目收益返点及算力余额和有效成员条件；缺失条件默认关闭',
  `vip_reward_mode` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'RATE' COMMENT '本级点击收益方式：RATE按算力钱包可用Token乘单次百分比，FIXED按固定金额；不扣除或冻结本金',
  `vip_fixed_reward` decimal(26,8) NOT NULL DEFAULT '0.00000000' COMMENT 'VIP单次固定收益金额，使用系统基础计价币；RATE模式不读取',
  `vip_details_delay_minutes` int(11) NOT NULL DEFAULT '1' COMMENT '算力使用明细生成延时，单位分钟，范围1至1380；收益在点击时发放，延时只影响明细',
  `display_return_rate` decimal(18,8) NOT NULL DEFAULT '0.00000000' COMMENT '用户端展示收益率百分数，仅用于等级与算力页面展示，不参与实际收益计算',
  `required_direct_members` int(11) NOT NULL DEFAULT '0' COMMENT 'VIP自动升级所需正式直推有效成员数，0不限制，保留人工改级权限',
  `direct_member_deposit_threshold` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '有效直推成员累计成功充值的固定Token门槛，包含测试用户，缺计价不补算',
  `auto_provider_ids` json NOT NULL COMMENT '本等级绑定的供应商ID数组，空数组表示不绑定',
  `auto_upgrade_enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '允许自动升级到本等级，关闭可跳过至更高达标等级；人工改级不受限',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='用户会员等级配置';

CREATE TABLE `compute_mcag_address` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '商户充值地址映射编号',
  `merchant_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'MCAG 商户编号',
  `environment` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'MCAG 环境，本次接入仅允许 LIVE',
  `member_id` bigint(20) NOT NULL COMMENT '本地用户编号，不接受客户端指定其他用户',
  `wallet_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '到账钱包：COMPUTE 算力或 PROJECT 项目',
  `customer_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '按用户及钱包生成的 MCAG 客户标识',
  `currency` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '分币账本币种快照',
  `network` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '本地通道网络编码快照',
  `asset_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'MCAG 币种及网络唯一资产编号',
  `network_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'MCAG 网络编号',
  `address_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'MCAG 监控地址编号',
  `address` varchar(200) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '已验证正在监控的收款地址',
  `memo` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '收款附言快照，无附言为空',
  `created_at` datetime(6) NOT NULL COMMENT 'UTC 地址绑定时间',
  `disabled_at` datetime(6) DEFAULT NULL COMMENT '本地停用时间UTC；停用后该地址到账仅记录不自动加款，永久归属仍保留',
  `disabled_by` bigint(20) DEFAULT NULL COMMENT '执行本地停用的后台账号编号，仅审计使用',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_mcag_address_asset` (`merchant_id`,`environment`,`address_id`,`asset_id`) USING BTREE,
  KEY `idx_mcag_address_member` (`member_id`) USING BTREE,
  KEY `ix_mcag_address_customer` (`merchant_id`,`environment`,`customer_id`,`asset_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='MCAG 充值地址归属';

CREATE TABLE `compute_mcag_address_current` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '当前展示地址指针编号',
  `merchant_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '网关商户编号',
  `environment` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '网关环境，仅LIVE',
  `customer_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '按用户及目标钱包隔离的客户编号',
  `asset_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '网关币网资产编号',
  `binding_id` bigint(20) DEFAULT NULL COMMENT '当前历史归属行编号，未完成申请时为空',
  `mapping_fingerprint` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '' COMMENT '本次已核验映射及版本摘要',
  `address_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'UNKNOWN' COMMENT 'ACTIVE、INITIALIZING或UNAVAILABLE',
  `monitoring_ready` tinyint(4) NOT NULL DEFAULT '0' COMMENT '该网络是否已完成监听准备',
  `verified_at` datetime(6) DEFAULT NULL COMMENT 'UTC权威状态核验时间，非监听有效期',
  `lease_token` varchar(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '短时远程核验租约令牌',
  `lease_until` datetime(6) DEFAULT NULL COMMENT 'UTC租约截止时间，不持有资金锁等待网络',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_mcag_current` (`merchant_id`,`environment`,`customer_id`,`asset_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='充值地址当前指针、状态缓存与多实例请求合并';

CREATE TABLE `compute_mcag_address_generation` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '地址代次指针主键',
  `merchant_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Crypto商户标识',
  `environment` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '商户环境',
  `member_id` bigint(20) NOT NULL COMMENT '永久所属会员编号',
  `wallet_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '充值入账钱包COMPUTE或PROJECT',
  `network_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Crypto真实链标识，同链币种共享代次',
  `generation` bigint(20) NOT NULL DEFAULT '0' COMMENT '当前地址代次；0为原客户编号，后续追加_1、_2等',
  `updated_at` datetime(6) NOT NULL COMMENT '代次更新时间UTC',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_mcag_address_generation` (`merchant_id`,`environment`,`member_id`,`wallet_type`,`network_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='充值地址当前代次，同用户钱包按真实链共享并持久化';

CREATE TABLE `compute_mcag_channel` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '映射内部主键',
  `merchant_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属Crypto商户编号',
  `environment` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '商户隔离环境',
  `currency` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '平台币种编码',
  `network` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '平台网络编码，保持现有实例值',
  `asset_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Crypto资产编号，必须结合真实网络使用',
  `network_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Crypto真实网络编号，不能填写EVM展示分组',
  `decimals` int(11) NOT NULL COMMENT '该网络资产链上精度，不代表平台账本精度',
  `deposit_enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT 'Crypto当前允许充值',
  `payout_enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT 'Crypto当前允许提款执行',
  `active` tinyint(1) NOT NULL DEFAULT '1' COMMENT '最近成功同步时仍在商户可见范围',
  `mapping_version` bigint(20) NOT NULL DEFAULT '1' COMMENT '本地映射版本，身份及能力变化后递增',
  `remote_version` bigint(20) NOT NULL DEFAULT '0' COMMENT 'Crypto资产配置版本',
  `metadata_json` json NOT NULL COMMENT 'Crypto公开币网配置快照，不含密钥或节点地址',
  `synced_at` datetime(6) NOT NULL COMMENT '最近成功同步UTC时间',
  `created_at` datetime(6) NOT NULL COMMENT '创建UTC时间',
  `updated_at` datetime(6) NOT NULL COMMENT '更新UTC时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_mcag_local_channel` (`merchant_id`,`environment`,`currency`,`network`) USING BTREE,
  UNIQUE KEY `uk_mcag_remote_channel` (`merchant_id`,`environment`,`asset_id`,`network_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='平台与Crypto币网映射及远端能力快照';

CREATE TABLE `compute_mcag_collection_batch` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '商户充值批次本地记录编号',
  `merchant_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '网关商户编号',
  `environment` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '只接收LIVE环境',
  `batch_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '网关固定归集批次，也是本地充值永久幂等键',
  `resource_version` bigint(20) NOT NULL COMMENT '最近成功应用的权威批次版本',
  `member_id` bigint(20) DEFAULT NULL COMMENT '已验证用户归属，未知地址为空',
  `customer_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定客户标识',
  `address_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定充值地址编号',
  `asset_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定资产编号',
  `network_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定网络编号',
  `amount_text` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '批次原币总金额，超精度不舍入',
  `state` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'CREDITED已入账、REVIEW待核查',
  `reason` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '待核查原因，不保存密钥或内部异常',
  `sweep_status` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '归集执行状态与用户入账独立',
  `collection_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '已验签归集公开交易明细，不含签名字节',
  `created_at` datetime(6) NOT NULL COMMENT 'UTC首次同步时间',
  `updated_at` datetime(6) NOT NULL COMMENT 'UTC最近同步时间',
  `accounting_model` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'RECEIPT_SWEEP' COMMENT 'LEGACY_BATCH旧批次入账或RECEIPT_SWEEP仅归集关联',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_mcag_batch` (`merchant_id`,`environment`,`batch_id`) USING BTREE,
  KEY `idx_mcag_batch_review` (`merchant_id`,`environment`,`state`,`updated_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='MCAG批次充值及独立归集状态';

CREATE TABLE `compute_mcag_cursor` (
  `merchant_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'MCAG 商户编号',
  `environment` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'MCAG 环境',
  `after_sequence` bigint(20) NOT NULL DEFAULT '0' COMMENT '已持久处理的最后连续事件序号，失败不前移',
  `updated_at` datetime(6) NOT NULL COMMENT 'UTC 游标更新时间',
  `batch_before_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '批次列表补拉分页游标，与连续事件游标相互独立',
  `order_before_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '逐笔订单补拉末项order_id，与事件和归集分页独立',
  PRIMARY KEY (`merchant_id`,`environment`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='MCAG 充值事件拉取游标';

CREATE TABLE `compute_mcag_customer_identity` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '充值客户永久归属主键',
  `merchant_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Crypto商户标识',
  `environment` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '商户环境',
  `customer_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原客户编号或持久代次客户编号',
  `member_id` bigint(20) NOT NULL COMMENT '永久所属会员编号',
  `wallet_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '永久所属充值入账钱包',
  `created_at` datetime(6) NOT NULL COMMENT '在网关外呼前登记的时间UTC',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_mcag_customer_identity` (`merchant_id`,`environment`,`customer_id`) USING BTREE,
  KEY `idx_mcag_customer_member` (`member_id`,`wallet_type`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='充值客户编号永久归属，地址切换和迟到回调均不得覆盖';

CREATE TABLE `compute_mcag_deposit_order` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '逐笔充值同步编号',
  `merchant_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '网关商户编号',
  `environment` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '网关环境，仅LIVE',
  `external_order_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权威逐笔充值订单编号',
  `receipt_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原始收款凭据编号',
  `transfer_event_id` varchar(200) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '链上转账事件编号，用于跨协议防重复',
  `resource_version` bigint(20) NOT NULL COMMENT '最近成功同步的权威版本',
  `member_id` bigint(20) DEFAULT NULL COMMENT '已验证的本地用户归属',
  `customer_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '按用户和钱包隔离的客户编号',
  `address_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本次实际收款的历史地址编号',
  `asset_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '网关币网资产编号',
  `network_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '实际网络编号',
  `address` varchar(200) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '实际收款地址，不随当前地址切换',
  `amount_text` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '原币金额，超精度不得舍入',
  `tx_hash` varchar(200) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '链上交易哈希',
  `transfer_locator` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '交易内转账定位，不以哈希单独去重',
  `record_id` bigint(20) DEFAULT NULL COMMENT '本地展示充值记录，存在不代表已入账',
  `local_deposit_id` bigint(20) DEFAULT NULL COMMENT '永久已入账关联，重组和再确认不清空',
  `state` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'CREDITED已入账或REVIEW待核查',
  `reason` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '脱敏核查原因',
  `remote_status` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '权威订单CONFIRMED或REVIEW',
  `snapshot_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '必要公开链上信息快照，不存凭据签名或密钥',
  `created_at` datetime(6) NOT NULL COMMENT 'UTC首次同步时间',
  `updated_at` datetime(6) NOT NULL COMMENT 'UTC最近同步时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_mcag_order` (`merchant_id`,`environment`,`external_order_id`) USING BTREE,
  UNIQUE KEY `uk_mcag_order_receipt` (`merchant_id`,`environment`,`receipt_id`) USING BTREE,
  UNIQUE KEY `uk_mcag_order_transfer` (`merchant_id`,`environment`,`transfer_event_id`) USING BTREE,
  UNIQUE KEY `uk_mcag_order_locator` (`merchant_id`,`environment`,`network_id`,`asset_id`,`tx_hash`,`transfer_locator`) USING BTREE,
  KEY `ix_mcag_order_member` (`member_id`,`created_at`) USING BTREE,
  KEY `ix_mcag_order_review` (`merchant_id`,`environment`,`state`,`updated_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='权威逐笔充值与跨协议永久幂等记录';

CREATE TABLE `compute_mcag_event` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'MCAG 事件收件编号',
  `merchant_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'MCAG 商户编号',
  `environment` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'MCAG 环境',
  `event_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'MCAG 事件编号，永久去重',
  `event_sequence` bigint(20) NOT NULL COMMENT '商户环境内连续事件序号',
  `resource_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '资源编号',
  `resource_version` bigint(20) NOT NULL COMMENT '事件关联资源版本',
  `state` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '首次处理结果：CREDITED、REVIEW、IGNORED 或 STALE',
  `received_at` datetime(6) NOT NULL COMMENT 'UTC 首次成功处理时间',
  `event_type` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '通知类型，仅对应明确充值类型才允许入账',
  `resource_type` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '通知资源类型，手续费和能量不作为充值',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_mcag_event` (`merchant_id`,`environment`,`event_id`) USING BTREE,
  UNIQUE KEY `uk_mcag_event_sequence` (`merchant_id`,`environment`,`event_sequence`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='MCAG 事件持久收件记录';

CREATE TABLE `compute_mcag_payout` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'MCAG 提款发单任务编号，内部关联使用',
  `merchant_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'MCAG 商户编号',
  `environment` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'MCAG 环境，本次只允许 LIVE',
  `withdrawal_id` bigint(20) NOT NULL COMMENT '本地已冻结资金的提款记录编号',
  `merchant_order_id` varchar(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原提款唯一业务订单号，所有重试保持不变',
  `customer_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本地用户收益钱包对应的商户客户标识',
  `currency` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '本地扣款原币种快照',
  `network` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '本地收款网络编码快照',
  `asset_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'MCAG 币种及链唯一资产编号快照',
  `network_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'MCAG 区块链网络编号快照',
  `destination_address` varchar(200) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '用户确认的实际收款地址快照',
  `memo` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '用户确认的收款附言，当前网关仅支持为空',
  `amount_text` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '用户手续费扣除后的原币净额十进制字符串，禁止浮点和再次扣费',
  `payout_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT 'MCAG 已确认创建的代付编号，未知结果时不得臆造',
  `quote_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '创建幂等请求绑定的原报价编号，超时不得擅自替换',
  `quote_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '已验签报价快照，只含公开金额、费用、资产和地址，不保存密钥或签名字节',
  `chain_details_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '已验签链上公开明细：执行状态、实际原生币链费及可得确认数，不保存签名字节',
  `quote_expires_at` datetime(6) DEFAULT NULL COMMENT 'UTC 网关报价过期时间',
  `state` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'NEW' COMMENT '本地发单阶段 NEW、QUOTED、CREATING、SUBMITTED、REVIEW、SUCCEEDED、RELEASED',
  `submission_state` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'NOT_SENT' COMMENT '创建请求安全状态 NOT_SENT未发、IN_FLIGHT结果不明、REJECTED明确拒绝、ACCEPTED已建单',
  `resource_version` bigint(20) NOT NULL DEFAULT '0' COMMENT '最近应用的第三方权威版本，旧版本不能覆盖终态',
  `remote_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT 'MCAG 原始代付状态，例如 QUEUED、UNKNOWN、SUCCEEDED、FAILED',
  `reason` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '本地核查原因编码，不保存原始异常或敏感响应',
  `attempts` int(11) NOT NULL DEFAULT '0' COMMENT '后台派发尝试次数，用于有限退避',
  `lease_token` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '当前工作者租约随机标识，避免多实例同时发单',
  `lease_until` datetime(6) DEFAULT NULL COMMENT 'UTC 当前工作者租约到期时间',
  `next_attempt_at` datetime(6) NOT NULL COMMENT 'UTC 下次允许自动恢复时间',
  `created_at` datetime(6) NOT NULL COMMENT 'UTC 审核后建立发单任务的时间',
  `updated_at` datetime(6) NOT NULL COMMENT 'UTC 最近发单或权威状态同步时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_mcag_payout_withdrawal` (`withdrawal_id`) USING BTREE,
  UNIQUE KEY `uk_mcag_payout_order` (`merchant_id`,`environment`,`merchant_order_id`) USING BTREE,
  UNIQUE KEY `uk_mcag_payout_remote` (`merchant_id`,`environment`,`payout_id`) USING BTREE,
  KEY `idx_mcag_payout_dispatch` (`merchant_id`,`environment`,`state`,`next_attempt_at`,`lease_until`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='MCAG 提款可靠发单及终态幂等记录';

CREATE TABLE `compute_member` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `username` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '账号名称',
  `email` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '电子邮箱',
  `phone` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '联系电话',
  `password_hash` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户登录密码BCrypt散列',
  `level` int(11) NOT NULL DEFAULT '1' COMMENT '会员等级编号',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `invite_code` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '系统生成的唯一邀请码',
  `inviter_id` bigint(20) DEFAULT NULL COMMENT '直属邀请用户编号，空表示物理根',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(6) NOT NULL COMMENT '更新时间，UTC',
  `group_id` bigint(20) DEFAULT NULL COMMENT '用户分组编号，空表示未分组',
  `pay_password_hash` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户支付密码BCrypt散列',
  `security_score` decimal(5,2) NOT NULL DEFAULT '100.00' COMMENT '用户安全分，范围0到100',
  `remark` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '操作或配置备注',
  `is_test` tinyint(1) NOT NULL DEFAULT '0' COMMENT '测试用户：仅后台显式设置；注册和邀请不继承；统计排除自身但遍历保留其下级',
  `address_limit` int(11) DEFAULT NULL COMMENT '单用户提款地址数量上限，空值继承平台配置',
  `registration_ip` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户注册来源IP；后台创建或历史未知记录为空',
  `registration_country` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '注册IP解析的ISO国家二字码，未知或内网为空',
  `last_login_at` datetime(6) DEFAULT NULL COMMENT '最后一次真实用户登录成功时间，UTC',
  `last_login_ip` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最后一次真实用户登录来源IP，不使用模拟登录来源覆盖',
  `last_login_country` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最后登录IP解析的ISO国家二字码，未知或内网为空',
  `vip_started_at` datetime(6) DEFAULT NULL COMMENT '当前 VIP 等级周期开始时间，UTC；历史空值表示未设置有限周期',
  `vip_expires_at` datetime(6) DEFAULT NULL COMMENT '当前 VIP 算力权益到期时间，UTC；空值为长期有效，到期禁止新执行但历史协议继续履约',
  `auto_next_at` datetime(6) DEFAULT NULL COMMENT '达到 VIP 每日次数上限后的最早再次执行时间，UTC，等级或时区修改不绕过已生效冷却',
  `user_time_zone` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户自主选择的 IANA 展示时区；为空跟随系统业务时区，不影响收益和报表规则',
  `user_language` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户最近主动选择的完整语言标签，空值表示尚未选择',
  `invitation_enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否允许使用本用户邀请码建立新下级；不影响既有关系与返点',
  `token_income_percent` decimal(5,2) NOT NULL DEFAULT '100.00' COMMENT 'Token算力收益发放比例（百分比，仅影响新发起Token收益）',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_compute_member_invite` (`invite_code`) USING BTREE,
  UNIQUE KEY `uk_compute_member_username` (`username`) USING BTREE,
  UNIQUE KEY `uk_compute_member_email` (`email`) USING BTREE,
  UNIQUE KEY `uk_compute_member_phone` (`phone`) USING BTREE,
  KEY `idx_compute_member_inviter` (`inviter_id`) USING BTREE,
  KEY `idx_compute_member_group` (`group_id`,`id`) USING BTREE,
  KEY `ix_compute_member_level_id` (`level`,`id`) USING BTREE,
  KEY `idx_compute_member_test` (`is_test`,`id`) USING BTREE,
  CONSTRAINT `compute_member_ibfk_2` FOREIGN KEY (`group_id`) REFERENCES `compute_member_group` (`id`),
  CONSTRAINT `compute_member_ibfk_3` FOREIGN KEY (`inviter_id`) REFERENCES `compute_member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='用户端独立账号和真实邀请关系';

CREATE TABLE `compute_member_group` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '名称',
  `note` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '分组说明',
  `sort_order` int(11) NOT NULL DEFAULT '0' COMMENT '显示排序值',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(6) NOT NULL COMMENT '更新时间，UTC',
  `owner_member_id` bigint(20) DEFAULT NULL COMMENT '配置归属的真实用户编号',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_compute_group_agent` (`enabled`) USING BTREE,
  KEY `idx_compute_group_member_owner` (`owner_member_id`,`enabled`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='用户单组归属配置';

CREATE TABLE `compute_member_invite_code` (
  `code` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '邀请码原文，区分大小写，永久占用且不可复用',
  `member_id` bigint(20) NOT NULL COMMENT '邀请码永久归属的会员ID',
  `previous_code` varchar(32) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '换码前邀请码，初始登记为空',
  `request_no` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '后台换码幂等请求号，同一会员不可重复',
  `created_at` datetime(6) NOT NULL COMMENT '邀请码登记时间（UTC），存量码沿用会员创建时间',
  `retired_at` datetime(6) DEFAULT NULL COMMENT '邀请码废弃时间（UTC），为空时仍须匹配会员当前码才有效',
  PRIMARY KEY (`code`) USING BTREE,
  UNIQUE KEY `uk_compute_invite_request` (`member_id`,`request_no`) USING BTREE,
  KEY `idx_compute_invite_member` (`member_id`,`created_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='会员邀请码永久归属与更换历史，废弃记录不可删除';

CREATE TABLE `compute_member_login_ticket` (
  `ticket_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '一次性模拟登录票据SHA256摘要，禁止保存明文',
  `member_id` bigint(20) NOT NULL COMMENT '目标用户内部主键，仅用于关系关联',
  `admin_uid` int(11) NOT NULL COMMENT '签发后台账号UID，兑换时重新验证账号权限及代理范围',
  `admin_credential_hash` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '签发时后台密码散列快照，密码变化后票据失效',
  `member_credential_hash` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '签发时用户密码散列快照，密码变化后票据失效',
  `expires_at` datetime(6) NOT NULL COMMENT '票据到期时间，UTC；签发后六十秒失效',
  `created_at` datetime(6) NOT NULL COMMENT '票据签发时间，UTC',
  PRIMARY KEY (`ticket_hash`) USING BTREE,
  KEY `idx_member_login_ticket_member` (`member_id`) USING BTREE,
  KEY `idx_member_login_ticket_expiry` (`expires_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='管理员模拟用户登录的一次性短期票据；成功兑换即消费';

CREATE TABLE `compute_member_operation` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `member_id` bigint(20) NOT NULL COMMENT '用户端账号编号',
  `operator_uid` int(11) NOT NULL COMMENT '操作后台账号编号',
  `operation_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '后台用户业务操作类型',
  `request_id` varchar(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '调用方幂等请求编号',
  `payload_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求内容摘要，用于验证重复请求一致性',
  `result_json` json DEFAULT NULL COMMENT '已完成请求的结果JSON快照',
  `remark` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '操作或配置备注',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  `order_no` varchar(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '独立唯一业务编号，与记录主键无关',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_compute_member_operation` (`operator_uid`,`request_id`) USING BTREE,
  UNIQUE KEY `uk_v6_order_no` (`order_no`) USING BTREE,
  KEY `idx_compute_member_operation` (`member_id`,`created_at`) USING BTREE,
  CONSTRAINT `compute_member_operation_ibfk_1` FOREIGN KEY (`member_id`) REFERENCES `compute_member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='后台用户调账迁移及手工充提幂等审计';

CREATE TABLE `compute_member_session` (
  `token_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '随机凭据摘要，不保存明文令牌',
  `member_id` bigint(20) NOT NULL COMMENT '用户端账号编号',
  `credential_hash` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '签发会话时的密码散列快照，用于密码变更后失效',
  `expires_at` datetime(6) NOT NULL COMMENT '登录空闲到期时间，UTC；有效主动请求按用户端空闲配置滑动续期',
  `last_seen_at` datetime(6) DEFAULT NULL COMMENT '最近一次成功主动业务请求时间UTC；后台轮询不续期，在线窗口由liveMonitor配置，默认10分钟',
  PRIMARY KEY (`token_hash`) USING BTREE,
  KEY `idx_compute_session_member` (`member_id`) USING BTREE,
  KEY `idx_compute_session_expiry` (`expires_at`) USING BTREE,
  KEY `idx_compute_session_activity` (`last_seen_at`,`expires_at`,`member_id`) USING BTREE COMMENT '在线活动窗口筛选及会员去重',
  CONSTRAINT `compute_member_session_ibfk_1` FOREIGN KEY (`member_id`) REFERENCES `compute_member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='用户端独立登录会话摘要';

CREATE TABLE `compute_merchant_snapshot` (
  `merchant_id` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'MCAG商户业务标识，取自服务端已配置身份',
  `environment` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'MCAG环境，目前仅允许LIVE',
  `resource` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '远端配置类型：assets资产能力，policies入账归集门槛',
  `snapshot_json` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '最近成功验签的远端公开配置快照，不包含私钥或签名信封',
  `last_attempt_at` datetime(6) NOT NULL COMMENT 'UTC最近尝试拉取时间',
  `last_success_at` datetime(6) DEFAULT NULL COMMENT 'UTC最近成功同步时间，失败时保留原值',
  `error_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '最近同步失败的中文多语言消息键，成功清空',
  PRIMARY KEY (`merchant_id`,`environment`,`resource`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='商户远端配置同步快照；只供比对，不修改用户账本或平台配置';

CREATE TABLE `compute_model` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `name` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '名称',
  `input_price` decimal(28,8) NOT NULL COMMENT '每百万输入令牌配置价格，美元',
  `output_price` decimal(28,8) NOT NULL COMMENT '每百万输出令牌配置价格，美元',
  `volatility` decimal(10,4) NOT NULL DEFAULT '0.0000' COMMENT '配置价格波动百分比',
  `current_input_price` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '当前每百万输入令牌配置价格，美元',
  `current_output_price` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '当前每百万输出令牌配置价格，美元',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `updated_at` datetime(6) NOT NULL COMMENT '更新时间，UTC',
  `image_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '模型图片素材地址',
  `provider_id` bigint(20) NOT NULL COMMENT '所属供应商ID，对应compute_provider.id',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_model_provider_name` (`provider_id`,`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='模型目录与配置计价';

CREATE TABLE `compute_model_price` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `model_id` bigint(20) NOT NULL COMMENT '模型编号',
  `price_date` date NOT NULL COMMENT '价格所属UTC日期',
  `input_price` decimal(28,8) NOT NULL COMMENT '每百万输入令牌配置价格，美元',
  `output_price` decimal(28,8) NOT NULL COMMENT '每百万输出令牌配置价格，美元',
  `change_percent` decimal(10,4) NOT NULL COMMENT '当日价格变动百分比',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_model_price_date` (`model_id`,`price_date`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='模型按日配置价格记录';

CREATE TABLE `compute_order` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `member_id` bigint(20) NOT NULL COMMENT '用户端账号编号',
  `product_id` bigint(20) NOT NULL COMMENT '投入产品编号',
  `product_name` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '购买时的产品名称',
  `product_type` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '购买时的产品类型',
  `source_wallet` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '购买扣款钱包类型',
  `currency` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '货币资产代码',
  `amount` decimal(28,8) NOT NULL COMMENT 'GPU订单固定本金，按原快照计息和返本',
  `duration_days` int(11) NOT NULL COMMENT '产品或订单期限天数',
  `settlement_mode` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '结算模式：A到期返本付息、B到期付息不返本、C每日付息到期返本、D每日付息不返本',
  `product_snapshot` json NOT NULL COMMENT '购买时产品配置JSON快照',
  `expected_income` decimal(28,8) NOT NULL COMMENT '订单预计总收益',
  `paid_income` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '订单已结算收益',
  `settled_days` int(11) NOT NULL DEFAULT '0' COMMENT '已完成结算天数',
  `status` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'ACTIVE' COMMENT '产品订单状态：ACTIVE履约中、COMPLETED已完成',
  `starts_at` datetime(6) NOT NULL COMMENT '开始时间，UTC',
  `matures_at` datetime(6) NOT NULL COMMENT '订单到期时间，UTC',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  `daily_rate` decimal(14,8) NOT NULL COMMENT '购买时固定日收益百分比，不按当前配置重算',
  `order_no` varchar(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '独立唯一业务编号，与记录主键无关',
  `product_quote_json` json DEFAULT NULL COMMENT '购买时固定报价快照，不按当前产品配置重算',
  `purchase_mode` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT 'GPU购买方式：AMOUNT金额或QUANTITY整数份数',
  `purchase_quantity` bigint(20) DEFAULT NULL COMMENT 'GPU真实购买整数份数；金额购买为0，未记录时为空',
  `purchase_unit_price` decimal(26,8) DEFAULT NULL COMMENT 'GPU购买时每份单价快照，单位Token；金额购买为0',
  `purchase_amount_token` decimal(38,8) DEFAULT NULL COMMENT '成交时固定Token计价金额，用于个人累计限购',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_v6_order_no` (`order_no`) USING BTREE,
  KEY `ix_order_member` (`member_id`,`created_at`) USING BTREE,
  KEY `ix_order_settle` (`status`,`matures_at`) USING BTREE,
  KEY `ix_compute_order_currency_member` (`currency`,`member_id`,`status`) USING BTREE,
  KEY `idx_order_member_product` (`member_id`,`product_id`,`product_type`,`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='用户投入订单及购买时配置快照';

CREATE TABLE `compute_order_settlement` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `order_id` bigint(20) NOT NULL COMMENT '投入订单编号',
  `member_id` bigint(20) NOT NULL COMMENT '用户端账号编号',
  `settlement_day` int(11) NOT NULL COMMENT '相对订单开始的结算日序号',
  `settlement_date` date NOT NULL COMMENT '结算归属日期：第九版起按系统业务时区换算计划结算时刻，历史日期保留原值；金额周期仍按UTC时长',
  `income` decimal(28,8) NOT NULL COMMENT '本次结算收益',
  `principal` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '本次结算返还本金',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  `order_no` varchar(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '独立唯一业务编号，与记录主键无关',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_order_settlement` (`order_id`,`settlement_day`) USING BTREE,
  UNIQUE KEY `uk_v6_order_no` (`order_no`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='订单逐日结算与返本账目';

CREATE TABLE `compute_product` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `name` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '名称',
  `type` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '记录类型',
  `currency` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '货币资产代码',
  `min_amount` decimal(28,8) NOT NULL COMMENT '规则或产品金额下限',
  `max_amount` decimal(28,8) NOT NULL COMMENT '规则或产品金额上限',
  `duration_days` int(11) NOT NULL COMMENT '产品或订单期限天数',
  `settlement_mode` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '结算模式：A到期返本付息、B到期付息不返本、C每日付息到期返本、D每日付息不返本',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '说明内容',
  `updated_at` datetime(6) NOT NULL COMMENT '更新时间，UTC',
  `image_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '产品图片素材地址',
  `translations_json` json DEFAULT NULL COMMENT '按语言保存标题与HTML说明',
  `content_version` bigint(20) NOT NULL DEFAULT '1' COMMENT '标题说明版本，防旧翻译覆盖',
  `source_language` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'zh' COMMENT '原文语言标签',
  `daily_rate_min` decimal(14,8) NOT NULL COMMENT '最低日收益率百分比',
  `daily_rate_max` decimal(14,8) NOT NULL COMMENT '最高日收益率百分比',
  `purchase_mode` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'AMOUNT' COMMENT 'GPU购买方式：AMOUNT输入金额、QUANTITY按整数份数购买；不影响订阅升级',
  `unit_price` decimal(26,8) NOT NULL DEFAULT '0.00000000' COMMENT 'GPU每份单价，单位Token；份数模式必须大于零',
  `max_purchase_amount` decimal(26,8) NOT NULL DEFAULT '0.00000000' COMMENT '每用户每GPU项目累计购买上限，单位Token，0不限，到期不恢复',
  `max_purchase_quantity` bigint(20) NOT NULL DEFAULT '0' COMMENT '每用户每GPU项目累计真实购买份数上限，0不限，到期不恢复，历史无份数不倒算',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_product_name` (`name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='用户投入产品配置';

CREATE TABLE `compute_product_quote` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '产品报价内部主键，不展示给业务用户',
  `quote_key` varchar(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '客户端提交使用的唯一报价凭据，不作为业务订单号',
  `member_id` bigint(20) NOT NULL COMMENT '报价所属用户内部关联，只允许该用户消费',
  `product_id` bigint(20) NOT NULL COMMENT '报价对应产品内部关联',
  `snapshot_json` json NOT NULL COMMENT '服务端计算的产品、原订阅、价格、抵扣及汇率完整快照',
  `expires_at` datetime(6) NOT NULL COMMENT '报价失效时间，UTC；创建后60秒过期',
  `used_at` datetime(6) DEFAULT NULL COMMENT '报价被成功购买事务消费的时间，UTC；失败回滚或未使用为空',
  `created_at` datetime(6) NOT NULL COMMENT '报价创建时间，UTC；亦为升级余时折抵与旧收益截点',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_subscription_quote_key` (`quote_key`) USING BTREE,
  KEY `idx_subscription_quote_member` (`member_id`,`created_at`) USING BTREE,
  KEY `idx_subscription_quote_expiry` (`expires_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='产品购买与订阅升级的限时价格报价，不包含资金变更';

CREATE TABLE `compute_provider` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '供应方主键',
  `code` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '供应方唯一编码，人工填写的稳定标识',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '供应方显示名称',
  `image_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '供应方图片素材地址',
  `enabled` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否启用，停用后不可配置新产品',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间，UTC',
  `updated_at` datetime(6) DEFAULT NULL COMMENT '最后修改时间，UTC',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_provider_code` (`code`) USING BTREE,
  UNIQUE KEY `uk_provider_name` (`name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='AI供应方目录，一个供应方对应多个模型';

CREATE TABLE `compute_recovery_request` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `member_id` bigint(20) NOT NULL COMMENT '用户端账号编号',
  `method` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '账号找回所用身份方式',
  `account` varchar(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '账号找回所用身份值',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '业务处理状态',
  `token_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '随机凭据摘要，不保存明文令牌',
  `expires_at` datetime(6) DEFAULT NULL COMMENT '凭据有效期截止时间，UTC',
  `reviewer_id` int(11) DEFAULT NULL COMMENT '账号找回审核后台账号编号',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  `used_at` datetime(6) DEFAULT NULL COMMENT '找回凭据使用时间，UTC',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_compute_recovery_token` (`token_hash`) USING BTREE,
  KEY `idx_compute_recovery_member` (`member_id`,`status`) USING BTREE,
  CONSTRAINT `compute_recovery_request_ibfk_1` FOREIGN KEY (`member_id`) REFERENCES `compute_member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='用户账号人工找回申请';

CREATE TABLE `compute_relation_state` (
  `singleton_id` int(11) NOT NULL COMMENT '关系锁单例编号，固定为1',
  `version` bigint(20) NOT NULL DEFAULT '1' COMMENT '并发控制版本号',
  PRIMARY KEY (`singleton_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='真实邀请关系并发锁与版本';

CREATE TABLE `compute_request` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `member_id` bigint(20) NOT NULL COMMENT '用户端账号编号',
  `request_id` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '调用方幂等请求编号',
  `request_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '用户幂等请求业务类型',
  `payload_hash` char(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '请求内容摘要，用于验证重复请求一致性',
  `result_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '已完成请求的结果JSON快照',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_member_request` (`member_id`,`request_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='用户端资金请求幂等结果';

CREATE TABLE `compute_reward_award` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '奖励分项记录内部主键，不在业务界面展示',
  `order_no` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '独立且唯一的短奖励业务编号',
  `event_key` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '触发奖励的真实充值或收益事件键',
  `member_id` bigint(20) NOT NULL COMMENT '奖励接收用户主键，关联compute_member.id',
  `source_member_id` bigint(20) NOT NULL COMMENT '原始充值或收益用户主键，按实际邀请链计算',
  `source_kind` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '奖励来源：DEPOSIT_SELF本人充值、DEPOSIT_DIRECT直属充值、EFFECTIVE有效客户、VIP_FIRST_DEPOSIT首充、VIP_DEPOSIT累计逐笔充值、VIP_INCOME算力收益、PRODUCT_INCOME项目收益',
  `source_id` bigint(20) NOT NULL COMMENT '来源充值记录主键或真实收益账本主键，由source_kind确定',
  `rule_key` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '该事件奖励分项标识；EFFECTIVE币种人数为永久一次的里程碑标识',
  `depth` int(11) NOT NULL COMMENT '接收者与来源用户的实际邀请距离；本人充值奖励为0',
  `currency` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '奖励原币资产代码，不跨币汇总或兑换',
  `amount` decimal(28,8) NOT NULL COMMENT '实际发入接收者收益钱包的奖励金额，原币单位',
  `source_amount` decimal(28,8) NOT NULL COMMENT '计算奖励的原币充值或收益金额；有效客户奖励为达标人数',
  `is_virtual` tinyint(1) NOT NULL DEFAULT '0' COMMENT '接收者为测试用户时标记1，保留测试账本并排除正式业务统计',
  `rule_snapshot` json NOT NULL COMMENT '发奖时使用的门槛、费率、固定金额或人数阶梯快照',
  `created_at` datetime(6) NOT NULL COMMENT '实际奖励入账时间，UTC',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_reward_award_number` (`order_no`) USING BTREE,
  UNIQUE KEY `uk_reward_award_event` (`event_key`,`member_id`,`rule_key`) USING BTREE,
  KEY `ix_reward_award_member_date` (`member_id`,`created_at`) USING BTREE,
  KEY `ix_reward_award_milestone` (`member_id`,`rule_key`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='第九版邀请奖励分项记录，和钱包账本在同一事务提交；历史奖励表保留';

CREATE TABLE `compute_reward_event` (
  `event_key` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '真实发奖来源事件唯一键，DEPOSIT充值主键或INCOME收益账本主键',
  `created_at` datetime(6) NOT NULL COMMENT '来源事件处理完成时间，UTC',
  PRIMARY KEY (`event_key`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='新奖励引擎已处理事件；停用规则或零奖励事件同样去重';

CREATE TABLE `compute_system_message_delivery` (
  `event_code` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '业务事件编码',
  `source_id` bigint(20) NOT NULL COMMENT '来源记录主键：注册事件为用户主键，充值事件为充值记录主键',
  `member_id` bigint(20) NOT NULL COMMENT '唯一接收用户编号',
  `content_id` bigint(20) DEFAULT NULL COMMENT '已投递的用户消息内容编号',
  `created_at` datetime(6) NOT NULL COMMENT '事件发生时间UTC',
  PRIMARY KEY (`event_code`,`source_id`) USING BTREE,
  KEY `idx_compute_system_message_member` (`member_id`,`content_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='系统用户消息幂等投递凭据';

CREATE TABLE `compute_system_message_template` (
  `event_code` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '固定业务事件编码',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用后续事件通知',
  `updated_at` datetime(6) NOT NULL COMMENT '最后修改时间UTC',
  PRIMARY KEY (`event_code`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='系统用户站内消息模板开关';

CREATE TABLE `compute_transfer` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `member_id` bigint(20) NOT NULL COMMENT '用户端账号编号',
  `from_wallet` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '划转来源钱包类型',
  `to_wallet` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '划转目标钱包类型',
  `currency` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '货币资产代码',
  `amount` decimal(28,8) NOT NULL COMMENT '金额，币种以本行货币字段为准',
  `fee` decimal(28,8) NOT NULL COMMENT '本次手续费',
  `net_amount` decimal(28,8) NOT NULL COMMENT '扣除手续费后的净额',
  `rule_id` bigint(20) NOT NULL COMMENT '匹配规则编号',
  `rule_snapshot` json NOT NULL COMMENT '操作时规则与通道配置JSON快照',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  `order_no` varchar(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '独立唯一业务编号，与记录主键无关',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_v6_order_no` (`order_no`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='用户钱包间划转记录';

CREATE TABLE `compute_transfer_rule` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `from_wallet` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '划转来源钱包类型',
  `to_wallet` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '划转目标钱包类型',
  `currency` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '*' COMMENT '货币资产代码',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `min_amount` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '规则或产品金额下限',
  `max_amount` decimal(28,8) NOT NULL COMMENT '规则或产品金额上限',
  `rate` decimal(12,6) NOT NULL DEFAULT '0.000000' COMMENT '手续费百分比，1表示百分之一',
  `fixed_fee` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '固定手续费',
  `minimum_fee` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '手续费最低收取金额',
  `updated_at` datetime(6) NOT NULL COMMENT '更新时间，UTC',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_transfer_direction` (`from_wallet`,`to_wallet`,`currency`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='用户钱包划转方向及费用规则';

CREATE TABLE `compute_translation_form` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '表单快照编号，与业务内容主键分离',
  `resource_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '表单业务类型：CONTENT内容、PRODUCT产品、LEVEL会员等级',
  `content_kind` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '内容表单原始分类，用于轮询时复核权限；历史未知或非内容表单为空',
  `source_business_id` bigint(20) DEFAULT NULL COMMENT '已保存表单对应业务编号，轮询重新校验当前范围；新表单为空',
  `requested_by` int(11) NOT NULL COMMENT '提交后台账号，查询和去重按账号隔离',
  `request_hash` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标题、正文和已有译文的SHA256快照摘要',
  `title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '提交时的源标题，不因翻译结果修改',
  `body_html` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '提交时的安全HTML源正文',
  `source_language` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '源文启用语言标签',
  `translations_json` json NOT NULL COMMENT '快照已有译文和成功补齐结果，仅供表单回填',
  `content_version` bigint(20) NOT NULL DEFAULT '1' COMMENT '隔离快照版本，复用翻译任务版本及租约保护',
  `created_at` datetime(6) NOT NULL COMMENT 'UTC快照创建时间',
  `updated_at` datetime(6) NOT NULL COMMENT 'UTC快照更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_translation_form_request` (`requested_by`,`resource_type`,`request_hash`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='未保存表单的翻译结果快照，不是业务自动保存';

CREATE TABLE `compute_wallet` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `member_id` bigint(20) NOT NULL COMMENT '用户端账号编号',
  `wallet_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '钱包类型：算力、项目或收益',
  `currency` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '货币资产代码',
  `available` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '当前可用余额',
  `frozen` decimal(28,8) NOT NULL DEFAULT '0.00000000' COMMENT '当前冻结余额',
  `updated_at` datetime(6) NOT NULL COMMENT '更新时间，UTC',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_member_wallet` (`member_id`,`wallet_type`,`currency`) USING BTREE,
  KEY `ix_compute_wallet_currency_member` (`currency`,`member_id`,`wallet_type`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='用户按币种分开的三类钱包余额';

CREATE TABLE `compute_wheel_adjustment` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '调整记录编号',
  `activity_id` bigint(20) NOT NULL COMMENT '活动编号',
  `member_id` bigint(20) NOT NULL COMMENT '会员编号',
  `round_no` int(11) NOT NULL COMMENT '轮次',
  `request_id` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '幂等请求标识',
  `payload_hash` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '完整规范化调整请求摘要',
  `added_count` bigint(20) NOT NULL DEFAULT '0' COMMENT '增加次数',
  `subtracted_count` bigint(20) NOT NULL DEFAULT '0' COMMENT '减少次数',
  `before_count` bigint(20) NOT NULL COMMENT '调整前次数',
  `after_count` bigint(20) NOT NULL COMMENT '调整后次数',
  `operator_id` int(11) NOT NULL COMMENT '操作后台账号',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '调整说明',
  `created_at` datetime NOT NULL COMMENT 'UTC调整时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_wheel_adjustment` (`activity_id`,`member_id`,`request_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='转盘次数调整审计，轮次重置不删除';

CREATE TABLE `compute_wheel_member` (
  `activity_id` bigint(20) NOT NULL COMMENT '转盘活动编号',
  `member_id` bigint(20) NOT NULL COMMENT '会员编号',
  `round_no` int(11) NOT NULL COMMENT '活动轮次',
  `control_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'UNIFIED' COMMENT 'UNIFIED统一或INDEPENDENT独立',
  `available_count` bigint(20) NOT NULL DEFAULT '0' COMMENT '实际可抽次数',
  `issued_count` bigint(20) NOT NULL DEFAULT '0' COMMENT '累计已发次数',
  `used_count` bigint(20) NOT NULL DEFAULT '0' COMMENT '已使用次数',
  `pending_count` bigint(20) NOT NULL DEFAULT '0' COMMENT '达标但未发放次数',
  `earned_count` bigint(20) NOT NULL DEFAULT '0' COMMENT '已记录资格次数，防重复累计',
  `target_draw` bigint(20) NOT NULL DEFAULT '0' COMMENT '独立指定第几次抽中',
  `target_prize_id` bigint(20) DEFAULT NULL COMMENT '独立指定奖品编号',
  `created_at` datetime NOT NULL COMMENT 'UTC创建时间',
  `updated_at` datetime NOT NULL COMMENT 'UTC更新时间',
  `target_range_id` varchar(80) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '宝箱独立指定的稳定奖励区间标识，不使用数组位置',
  `chest_earned_offset` bigint(20) DEFAULT NULL COMMENT '宝箱新资格计算与旧已记录次数的固定兼容差额，可正可负；空值待首次锁内核验',
  PRIMARY KEY (`activity_id`,`member_id`,`round_no`) USING BTREE,
  KEY `idx_wheel_member` (`member_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='转盘会员次数与独立规则';

CREATE TABLE `compute_withdrawal` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `member_id` bigint(20) NOT NULL COMMENT '用户端账号编号',
  `currency` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '货币资产代码',
  `network` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '网络或银行通道代码',
  `amount` decimal(28,8) NOT NULL COMMENT '金额，币种以本行货币字段为准',
  `fee` decimal(28,8) NOT NULL COMMENT '本次手续费',
  `net_amount` decimal(28,8) NOT NULL COMMENT '扣除手续费后的净额',
  `address` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '收款地址或银行账号',
  `rule_id` bigint(20) DEFAULT NULL COMMENT '匹配规则编号',
  `rule_snapshot` json NOT NULL COMMENT '操作时规则与通道配置JSON快照',
  `status` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'PENDING' COMMENT '提款状态：PENDING待审核、PROCESSING真实代付处理中、APPROVED已完成、REJECTED已拒绝或安全退回',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '操作或配置备注',
  `reviewed_by` int(11) DEFAULT NULL COMMENT '审核后台账号编号',
  `reviewed_at` datetime(6) DEFAULT NULL COMMENT '审核时间，UTC',
  `created_at` datetime(6) NOT NULL COMMENT '创建时间，UTC',
  `address_snapshot` json DEFAULT NULL COMMENT '提款确认时收款资料JSON快照',
  `record_source` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'USER' COMMENT '记录来源：USER用户申请、ADMIN_MANUAL后台手工添加',
  `created_by` int(11) DEFAULT NULL COMMENT '手工创建记录的后台账号UID；用户申请为空',
  `creation_remark` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '手工添加说明，与后续审核备注分开保留',
  `completion_mode` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '提款完成方式：MANUAL_CONFIRMED人工核验、TEST_COMPLETED测试完成、MCAG_PENDING代付中、MCAG_COMPLETED已付款、MCAG_RELEASED安全退回；历史未记录为空',
  `order_no` varchar(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '独立唯一业务编号，与记录主键无关',
  `sender_address` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '提款链上发送地址；人工或测试提款未发生真实代付时为空',
  `receiver_address` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '提款链上接收地址快照；为空时展示既有 address 收款快照',
  `tx_hash` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '提款链上交易哈希；只保存实际代付凭据，人工及测试未上链时为空',
  `chain_status` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'NOT_APPLICABLE' COMMENT '提款链上状态：NOT_APPLICABLE无链上操作，WAITING等待，BROADCAST已广播，CONFIRMED已确认，FAILED失败；审核通过不代表上链',
  `chain_confirmations` int(11) DEFAULT NULL COMMENT '提款链上已确认区块数量；未接入或未查询时为空',
  `chain_updated_at` datetime(6) DEFAULT NULL COMMENT '提款链上状态最近核验时间，按 UTC 保存',
  `external_order_no` varchar(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '支付供应商真实代付业务编号，创建确认前为空',
  `payment_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'MANUAL_PENDING' COMMENT '支付处理状态；MCAG排队、成功、失败与核查不代替业务审核状态',
  `gateway_snapshot` json DEFAULT NULL COMMENT '创建提款时的支付方式、商户及币网映射快照，不随后续配置降级',
  `accounting_version` int(11) NOT NULL DEFAULT '1' COMMENT '计价版本：1保留历史原币义务，2为新业务USD统一计价；新业务显式写入2',
  `accounting_currency` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '业务记账币种，版本2固定为USD；原币币种仍保留在currency字段',
  `accounting_amount` decimal(28,8) DEFAULT NULL COMMENT '按固定汇率折合的USD总额：充值入账额或提款冻结扣款总额；是否已入账由计价状态决定',
  `accounting_rate` decimal(36,18) DEFAULT NULL COMMENT '固定计价汇率，1单位原币等于此数额USD；历史原币记录不补写现价',
  `accounting_quote_json` json DEFAULT NULL COMMENT '本笔USD计价报价快照，保留汇率来源和报价时间；不覆盖旧记录或重算历史金额',
  `accounting_status` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'LEGACY' COMMENT '提款计价状态：LEGACY历史原币、FROZEN已冻结USD、POSTED已扣款、RELEASED已释放冻结',
  `accounting_fee` decimal(28,8) DEFAULT NULL COMMENT '本笔提款手续费折合USD快照；原币手续费保留在fee字段',
  `accounting_net_amount` decimal(28,8) DEFAULT NULL COMMENT '本笔提款到账净额折合USD快照；实际链上净额保留在net_amount字段',
  `attention_ignored` tinyint(4) NOT NULL DEFAULT '0' COMMENT '仅忽略后台提款处理计数及声音提醒，0提醒1忽略；不改变资金状态',
  `attention_updated_by` int(11) DEFAULT NULL COMMENT '最近调整提款提醒的管理员编号',
  `attention_updated_at` datetime(6) DEFAULT NULL COMMENT '最近调整提款提醒的UTC时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_v6_order_no` (`order_no`) USING BTREE,
  KEY `ix_withdrawal_member` (`member_id`,`created_at`) USING BTREE,
  KEY `ix_withdrawal_review` (`status`,`created_at`) USING BTREE,
  KEY `ix_compute_withdrawal_currency_member` (`currency`,`member_id`,`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='用户提款申请冻结与审核记录';

CREATE TABLE `ip_whitelist` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `ip_pattern` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '精确 IPv4/IPv6 或 CIDR',
  `description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '说明内容',
  `enabled` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `created_by` int(11) DEFAULT NULL COMMENT '创建记录的后台账号编号',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，UTC',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间，UTC',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_ip_whitelist_pattern` (`ip_pattern`) USING BTREE,
  KEY `idx_ip_whitelist_enabled` (`enabled`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='后台访问 IP 白名单';

CREATE TABLE `material_asset` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `name` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '名称',
  `media_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '素材媒体类型：IMAGE 图片，VIDEO 视频',
  `mime_type` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '文件媒体类型',
  `file_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '存储文件名',
  `file_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '素材访问地址',
  `file_size` bigint(20) NOT NULL DEFAULT '0' COMMENT '文件字节数',
  `width` int(11) DEFAULT NULL COMMENT '图像或视频像素宽度',
  `height` int(11) DEFAULT NULL COMMENT '图像或视频像素高度',
  `duration_seconds` int(11) DEFAULT NULL COMMENT '视频时长秒数',
  `category` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '素材分类',
  `tags` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '素材标签',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '操作或配置备注',
  `created_by` int(11) DEFAULT NULL COMMENT '创建记录的后台账号编号',
  `is_del` tinyint(4) NOT NULL DEFAULT '0' COMMENT '软删除：0正常，1删除',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，UTC',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间，UTC',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_material_file_name` (`file_name`) USING BTREE,
  KEY `idx_material_type_deleted` (`media_type`,`is_del`,`created_at`) USING BTREE,
  KEY `idx_material_category` (`category`,`is_del`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='图片视频素材库';

CREATE TABLE `memu` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `isopen` int(11) DEFAULT NULL COMMENT '菜单权限是否启用',
  `level` int(11) DEFAULT NULL COMMENT '菜单权限树层级',
  `icon` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '菜单图标',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '菜单或权限的多语言名称键',
  `pid` int(11) DEFAULT NULL COMMENT '父菜单权限编号',
  `sort` int(11) DEFAULT NULL COMMENT '显示排序值',
  `url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '菜单路径或兼容地址',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='兼容命名的后台菜单及数字权限目录';

CREATE TABLE `notification_automation_state` (
  `event_code` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '自动通知规则编码',
  `source_key` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '当前连续低余额事件标识',
  `updated_at` datetime(6) NOT NULL COMMENT '最后更新UTC',
  PRIMARY KEY (`event_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='连续低余额通知事件状态';

CREATE TABLE `notification_channel_config` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '名称',
  `channel_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '通知渠道类型：EMAIL 邮件，TELEGRAM 机器人，DINGTALK 钉钉',
  `enabled` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `encrypted_config` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'AES-256-GCM 加密 JSON',
  `default_recipients` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '邮箱、Telegram chat ID 等默认接收目标',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，UTC',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间，UTC',
  `last_failure_alert_at` datetime DEFAULT NULL COMMENT '最近一次 Telegram 失败告警时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_notification_channel_type_enabled` (`channel_type`,`enabled`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='外部通知渠道配置';

CREATE TABLE `notification_event_delivery` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '通知投递编号',
  `event_code` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '通知规则编码',
  `source_key` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '事件来源与启用轮次唯一键',
  `target_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '接收对象或事件处理标记',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'CLAIMED已领取 SENT成功 FAILED失败 SKIPPED跳过',
  `created_at` datetime(6) NOT NULL COMMENT '领取时间UTC',
  `updated_at` datetime(6) NOT NULL COMMENT '最后处理时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notification_delivery` (`event_code`,`source_key`,`target_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='自动通知持久去重与投递状态';

CREATE TABLE `notification_finance_event` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '已提交订单采集事件序号',
  `resource` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '订单种类DEPOSIT或WITHDRAWAL',
  `source_id` bigint(20) NOT NULL COMMENT '充值或提款业务记录编号',
  `created_at` datetime(6) NOT NULL COMMENT '事件采集时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notification_finance_source` (`resource`,`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='新充提声音事件索引';

CREATE TABLE `notification_telegram_binding` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'Telegram绑定编号',
  `channel_id` bigint(20) NOT NULL COMMENT '所属通知渠道',
  `chat_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '已确认接收对象数字编号',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户或群组显示名称',
  `chat_type` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PRIVATE个人 GROUP群组 CHANNEL频道',
  `enabled` int(11) NOT NULL DEFAULT '1' COMMENT '是否允许作为通知接收对象',
  `created_at` datetime(6) NOT NULL COMMENT '绑定创建时间UTC',
  `updated_at` datetime(6) NOT NULL COMMENT '最后修改时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notification_telegram_chat` (`channel_id`,`chat_id`),
  KEY `ix_notification_telegram_enabled` (`channel_id`,`enabled`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Telegram渠道已绑定接收对象';

CREATE TABLE `notification_template` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `code` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '通知模板自动生成代码',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '名称',
  `channel_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '为空表示通用模板',
  `title_template` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '通知标题模板',
  `content_template` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '通知正文模板',
  `enabled` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，UTC',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间，UTC',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_notification_template_code` (`code`) USING BTREE,
  KEY `idx_notification_template_enabled` (`enabled`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='通知内容模板';

CREATE TABLE `system_notification` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `recipient_uid` int(11) NOT NULL COMMENT '接收账号 UID',
  `sender_uid` int(11) DEFAULT NULL COMMENT '发送账号 UID，系统任务可为空',
  `title` varchar(300) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '标题',
  `content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '通知正文',
  `read_status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '0 未读；1 已读',
  `read_time` datetime DEFAULT NULL COMMENT '通知阅读时间，UTC',
  `hidden_status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '当前接收用户是否隐藏',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，UTC',
  `severity` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'INFO' COMMENT '通知样式INFO/SUCCESS/WARNING/ERROR',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_notification_created_at` (`created_at`) USING BTREE,
  KEY `idx_notification_recipient_hidden_read` (`recipient_uid`,`hidden_status`,`read_status`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='管理员站内收件箱，实时弹窗不持久化';

CREATE TABLE `system_setting` (
  `setting_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '稳定配置键',
  `setting_value` longtext COLLATE utf8mb4_unicode_ci COMMENT '配置原始值；领域配置使用包含业务生效时间的JSON文档',
  `value_type` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'string' COMMENT '配置值类型',
  `group_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'system' COMMENT '配置分组名称',
  `description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '说明内容',
  `is_public` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否允许未登录读取',
  `is_editable` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否允许后台编辑',
  `updated_by` int(11) DEFAULT NULL COMMENT '最后修改的后台账号编号',
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '配置修改时间，UTC，不代替业务启用边界',
  `revision` bigint(20) NOT NULL DEFAULT '0' COMMENT '配置项并发版本，每次成功修改递增',
  PRIMARY KEY (`setting_key`) USING BTREE,
  KEY `idx_system_setting_group` (`group_name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='系统基础设置';

CREATE TABLE `translate_language` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `lang` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '完整语言标签',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '语言英文名称',
  `chinese_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '中文名称',
  `native_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '语言本地名称',
  `sort_no` int(11) NOT NULL DEFAULT '0' COMMENT '排序',
  `status` tinyint(4) NOT NULL DEFAULT '1' COMMENT '状态：0停用，1启用',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_translate_language_lang` (`lang`) USING BTREE,
  KEY `idx_translate_language_status_sort` (`status`,`sort_no`,`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='系统支持的多语言配置';

CREATE TABLE `translates` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `tkey` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '多语言消息键',
  `tvalue` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '翻译正文',
  `lang` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '完整语言标签或内置模板标识',
  `type` int(11) NOT NULL COMMENT '语言模板类型：1服务端、5后台、7用户端及内置模板',
  `prop` int(11) DEFAULT NULL COMMENT '文本类型 1 html  0 text',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '标题',
  `is_del` int(11) DEFAULT NULL COMMENT '0不可删除',
  `action_time` datetime DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '最后操作时间，UTC',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `key_lang` (`tkey`,`lang`,`type`) USING BTREE,
  KEY `idx_translates_type_lang_tkey` (`type`,`lang`,`tkey`) COMMENT '按模板类型和语言读取管理端及用户端词包'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='动态多语言翻译键值及模板';

CREATE TABLE `translation_completion_task` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `template_type` int(11) NOT NULL COMMENT 'translates 模板 ID',
  `template_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '翻译模板名称',
  `requested_by` int(11) NOT NULL COMMENT '发起账号 UID',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '翻译补齐任务状态：PENDING 待执行，RUNNING 执行中，SUCCEEDED 成功，FAILED 失败',
  `progress_percent` int(11) NOT NULL DEFAULT '0' COMMENT '任务完成百分比',
  `processed_keys` int(11) NOT NULL DEFAULT '0' COMMENT '已处理语言键数',
  `total_keys` int(11) NOT NULL DEFAULT '0' COMMENT '待处理语言键总数',
  `inserted` int(11) NOT NULL DEFAULT '0' COMMENT '已新增翻译行数',
  `translated_keys` int(11) NOT NULL DEFAULT '0' COMMENT '已翻译键数',
  `missing_before` bigint(20) NOT NULL DEFAULT '0' COMMENT '执行前缺失翻译数',
  `missing_after` bigint(20) NOT NULL DEFAULT '0' COMMENT '执行后缺失翻译数',
  `error_message` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '任务错误说明',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，UTC',
  `started_at` datetime DEFAULT NULL COMMENT '执行开始时间，UTC',
  `finished_at` datetime DEFAULT NULL COMMENT '执行结束时间，UTC',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间，UTC',
  `active_template_type` int(11) GENERATED ALWAYS AS ((case when (`status` in (_utf8mb4'PENDING',_utf8mb4'RUNNING')) then `template_type` else NULL end)) STORED COMMENT '活动任务锁定的模板类型',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_translation_task_active_template` (`active_template_type`) USING BTREE,
  KEY `idx_translation_task_requester_created` (`requested_by`,`created_at`) USING BTREE,
  KEY `idx_translation_task_status_updated` (`status`,`updated_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC COMMENT='翻译模板补齐持久任务';

CREATE TABLE `user_role` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '记录编号',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '名称',
  `remarks` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '角色说明',
  `actionTime` datetime DEFAULT NULL COMMENT '最后操作时间，UTC',
  `isSelf` varchar(4) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '是否系统超级角色',
  `role` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '逗号分隔的数字权限码集合',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='后台角色及数字权限集合';

SET FOREIGN_KEY_CHECKS=1;
