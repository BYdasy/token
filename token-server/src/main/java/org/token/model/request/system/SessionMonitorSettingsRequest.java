package org.token.model.request.system;

/** 在线窗口为空表示仅更新原有会话期限，保留受限管理员原有能力。 */
public record SessionMonitorSettingsRequest(Integer adminIdleHours, Integer userIdleHours,
                                           Integer onlineWindowMinutes) {
}
