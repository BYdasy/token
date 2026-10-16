package org.token.model.response;

import java.time.LocalDateTime;
import java.util.List;

/** 通知模板及其启用语言内容。 */
public record NotificationTemplateView(
        Long id,
        String code,
        String name,
        String channelType,
        String titleTemplate,
        String contentTemplate,
        Integer enabled,
        Integer translationType,
        List<NotificationTemplateLanguageView> enabledLanguages,
        List<NotificationTemplateLocalizationView> translations,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /** 短码只作界面和调用别名，不改写历史模板编码及翻译键。 */
    @com.fasterxml.jackson.annotation.JsonProperty("shortCode")
    public String shortCode() { return id == null ? null : "T" + id; }
}
