package org.token.model.request.system;

public record LoginSessionSettingsRequest(Integer adminIdleHours, Integer userIdleHours) {
}
