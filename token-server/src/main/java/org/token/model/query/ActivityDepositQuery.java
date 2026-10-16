package org.token.model.query;

import java.time.LocalDateTime;

/** 服务根据活动轮次及固定切换点确认的充值候选窗口。 */
public record ActivityDepositQuery(long memberId,String currency,LocalDateTime start,LocalDateTime end,LocalDateTime testParticipationSince) { }
