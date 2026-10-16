package org.token.custom.web;

import org.token.tools.time.RequestTimeZoneContext;

import jakarta.servlet.ServletException;
import org.token.service.SystemSettingService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.time.ZoneId;
import java.time.ZoneOffset;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 时区只取系统设置，异常结束请求也不能把上下文带入下一次请求。 */
class RequestTimeZoneFilterTest {
    @Test void appliesSystemZoneAndClearsItOnFailure() {
        SystemSettingService settings=mock(SystemSettingService.class);
        when(settings.resolveSystemTimeZone()).thenReturn("Pacific/Auckland");
        when(settings.resolveDisplayTimeZoneUsingSystem("Pacific/Auckland")).thenReturn("Europe/Paris");
        RequestTimeZoneFilter filter=new RequestTimeZoneFilter(settings);
        assertThrows(ServletException.class,()->filter.doFilter(new MockHttpServletRequest("GET","/api/compute/admin/orders"),
                new MockHttpServletResponse(),(request,response)->{
                    assertEquals(ZoneId.of("Pacific/Auckland"),RequestTimeZoneContext.zone());
                    assertEquals(ZoneId.of("Europe/Paris"),RequestTimeZoneContext.responseZone());
                    RequestTimeZoneContext.beginRequestBody();
                    RequestTimeZoneContext.beginResponseBody();
                    throw new ServletException("测试异常");
                }));
        assertEquals(ZoneOffset.UTC,RequestTimeZoneContext.zone());
        assertFalse(RequestTimeZoneContext.isRequestBody());
        assertFalse(RequestTimeZoneContext.isResponseBody());
        verify(settings).resolveSystemTimeZone();
        verify(settings).resolveDisplayTimeZoneUsingSystem("Pacific/Auckland");
    }

    @Test void userApiUsesUtcOutputWhileBusinessInputStillUsesSystemZone() throws Exception {
        var settings=mock(SystemSettingService.class);
        when(settings.resolveSystemTimeZone()).thenReturn("Asia/Shanghai");
        var filter=new RequestTimeZoneFilter(settings);
        filter.doFilter(new MockHttpServletRequest("GET","/api/compute/user/orders"),new MockHttpServletResponse(),(request,response)->{
            assertTrue(RequestTimeZoneContext.isUserApi());
            assertEquals(ZoneOffset.UTC,RequestTimeZoneContext.responseZone());
            assertEquals(ZoneId.of("Asia/Shanghai"),RequestTimeZoneContext.zone());
            RequestTimeZoneContext.beginResponseBody();
        });
        assertFalse(RequestTimeZoneContext.isResponseBody());
        assertFalse(RequestTimeZoneContext.isUserApi());
        verify(settings,never()).resolveDisplayTimeZone(any());
        verify(settings,never()).resolveDisplayTimeZoneUsingSystem(any());
    }
}
