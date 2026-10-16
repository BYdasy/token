package org.token.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.token.tools.time.RequestTimeZoneContext;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

/**
 * JSON 时间格式配置。
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * HTTP 时间必须携带明确偏移，浏览器只转换一次；内部缓存 JSON 保持 UTC 原值。
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonDateTimeCustomizer() {
        return builder -> {
            builder.timeZone(TimeZone.getTimeZone(ZoneOffset.UTC));
            builder.simpleDateFormat("yyyy-MM-dd HH:mm:ss");
            builder.serializerByType(LocalDateTime.class, new JsonSerializer<LocalDateTime>() {
                @Override
                public void serialize(LocalDateTime value, JsonGenerator generator, SerializerProvider serializers)
                        throws IOException {
                    if (RequestTimeZoneContext.isResponseBody() && RequestTimeZoneContext.isUserApi()) {
                        generator.writeString(value.toInstant(ZoneOffset.UTC).toString());
                        return;
                    }
                    if (RequestTimeZoneContext.isResponseBody()) {
                        generator.writeString(value.atZone(ZoneOffset.UTC).withZoneSameInstant(RequestTimeZoneContext.responseZone())
                                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                    } else generator.writeString(value.format(DATE_TIME_FORMATTER));
                }
            });
            builder.deserializerByType(LocalDateTime.class, new JsonDeserializer<>() {
                @Override
                public LocalDateTime deserialize(JsonParser parser, DeserializationContext context)
                        throws IOException {
                    String value = parser.getValueAsString();
                    if (!StringUtils.hasText(value)) {
                        return null;
                    }
                    if (value.matches(".*(?:Z|[+-]\\d{2}:\\d{2})$")) {
                        return java.time.OffsetDateTime.parse(value.trim().replace(' ', 'T')).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
                    }
                    LocalDateTime parsed = LocalDateTime.parse(
                            value.trim().replace('T', ' '),
                            DATE_TIME_FORMATTER
                    );
                    return RequestTimeZoneContext.isRequestBody()
                            ? parsed.atZone(RequestTimeZoneContext.zone())
                            .withZoneSameInstant(ZoneOffset.UTC)
                            .toLocalDateTime()
                            : parsed;
                }
            });
        };
    }
}
