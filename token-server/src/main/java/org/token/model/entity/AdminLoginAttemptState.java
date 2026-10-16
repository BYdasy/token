package org.token.model.entity;

import java.time.LocalDateTime;

/** 管理员登录失败状态的数据库投影，日期字段保持 UTC 原值。 */
public record AdminLoginAttemptState(int count, LocalDateTime lockedUntil) { }
