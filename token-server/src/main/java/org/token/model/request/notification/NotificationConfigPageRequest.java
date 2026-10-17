package org.token.model.request.notification;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 通知渠道、模板和接收绑定列表的分页条件；不接受客户端自定义排序或 SQL。 */
public record NotificationConfigPageRequest(
        @Min(1) @Max(1000000) Integer current,
        @Min(1) @Max(100) Integer size,
        @Size(max = 200) String keyword,
        @Size(max = 20) String channelType,
        @Positive Long channelId
) {
    public NotificationConfigPageRequest {
        current = current == null ? 1 : current;
        size = size == null ? 20 : size;
        keyword = keyword == null ? "" : keyword.trim();
        channelType = channelType == null ? "" : channelType.trim();
    }
}
