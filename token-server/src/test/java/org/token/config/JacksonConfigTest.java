package org.token.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.tools.time.RequestTimeZoneContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JacksonConfigTest {

    @Test void userUtcAndAdminDisplayNeverConvertTheSameInstantTwice() throws Exception {
        var builder=new Jackson2ObjectMapperBuilder();
        new JacksonConfig().jacksonDateTimeCustomizer().customize(builder);
        ObjectMapper mapper=builder.build();
        LocalDateTime utc=LocalDateTime.parse("2026-09-12T00:00:00");
        RequestTimeZoneContext.setZone(ZoneId.of("Asia/Shanghai"));
        RequestTimeZoneContext.setResponseZone(ZoneId.of("America/New_York"));
        RequestTimeZoneContext.beginRequestBody();
        assertEquals(utc,mapper.readValue("\"2026-09-12 08:00:00\"",LocalDateTime.class));
        assertEquals(utc,mapper.readValue("\"2026-09-12T00:00:00Z\"",LocalDateTime.class));
        RequestTimeZoneContext.endRequestBody();
        RequestTimeZoneContext.beginResponseBody();
        assertEquals("\"2026-09-11T20:00:00-04:00\"",mapper.writeValueAsString(utc));
        RequestTimeZoneContext.setUserApi(true);
        assertEquals("\"2026-09-12T00:00:00Z\"",mapper.writeValueAsString(utc));
    }

    @BeforeEach
    @AfterEach
    void clearContext() {
        RequestTimeZoneContext.clear();
    }

    @Test
    void convertsUtcOnlyForHttpRequestAndResponseBodies() throws Exception {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().jacksonDateTimeCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();
        LocalDateTime utc = LocalDateTime.of(2026, 1, 15, 0, 0);

        assertEquals("\"2026-01-15 00:00:00\"", mapper.writeValueAsString(utc),
                "内部缓存 JSON 必须保持 UTC");

        RequestTimeZoneContext.setZone(ZoneId.of("Asia/Shanghai"));
        assertEquals("\"2026-01-15 00:00:00\"", mapper.writeValueAsString(utc),
                "请求业务阶段的内部缓存不能套用展示时区");
        RequestTimeZoneContext.beginResponseBody();
        assertEquals("\"2026-01-15T08:00:00+08:00\"", mapper.writeValueAsString(utc));

        RequestTimeZoneContext.clear();
        RequestTimeZoneContext.setZone(ZoneId.of("Asia/Shanghai"));
        RequestTimeZoneContext.beginRequestBody();
        assertEquals(utc, mapper.readValue("\"2026-01-15 08:00:00\"", LocalDateTime.class));
    }
}
