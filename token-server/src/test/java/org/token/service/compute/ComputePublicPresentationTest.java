package org.token.service.compute;

import org.token.config.EnabledLocaleResolver;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.SystemSettingService;
import org.token.service.TranslationService;
import org.token.controller.publicapi.ComputePublicController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 注册协议仅复用已发布的指定用途内容，保持游客首页以外的业务认证边界。 */
class ComputePublicPresentationTest {
    @Test void bootstrapUsesConfiguredDefaultAndOnlyIncludesRequiredLegalDocuments() {
        var jdbc = mock(JdbcTemplate.class); var accounts = mock(ComputeAccountService.class);
        var locales = mock(EnabledLocaleResolver.class); var settings = mock(SystemSettingService.class);
        var translations = mock(TranslationService.class); var results = mock(ResultFactory.class);
        var currencies = mock(ComputeCurrencyService.class); var presentation = mock(ComputePresentationService.class); var content = mock(ComputeContentService.class);
        when(accounts.settings()).thenReturn(Map.of()); when(locales.resolve("en")).thenReturn("en");
        when(presentation.publicSettings()).thenReturn(new LinkedHashMap<>(Map.of("defaultLanguage", "en")));
        when(content.userList(isNull(), anyMap(), eq("en"))).thenAnswer(input -> {
            Map<String, String> query = input.getArgument(1);
            assertEquals("ARTICLE", query.get("kind")); assertEquals("1", query.get("size"));
            return Map.of("records", "TERMS".equals(query.get("contentKey")) ? List.of(Map.of("title", "Terms", "bodyHtml", "<p>English terms</p>")) : List.of());
        });
        when(results.success(any())).thenAnswer(input -> new Result(0, input.getArgument(0), "成功"));
        var controller = new ComputePublicController(new org.token.service.compute.ComputeBootstrapService(accounts, locales, settings, translations, currencies, presentation, content, mock(ComputeUserFeaturesService.class), org.mockito.Mockito.mock(org.token.repository.compute.ComputePublicQueryRepository.class)), mock(org.token.custom.security.ClientIpResolver.class), results);
        Result result = controller.bootstrap(null, new org.springframework.mock.web.MockHttpServletRequest());
        Map<?, ?> response = (Map<?, ?>) result.getData();
        Map<?, ?> legal = (Map<?, ?>) ((Map<?, ?>) response.get("presentation")).get("legal");
        assertEquals(Set.of("TERMS"), legal.keySet());
        verify(content, times(2)).userList(isNull(), anyMap(), eq("en"));
        verify(content, never()).userDetail(anyLong(), any(), anyString());
        assertEquals("en", response.get("language"));
        assertEquals(List.of("phone", "email", "username"), response.get("loginMethodOrder"));
        assertEquals(true, response.get("usernameCharacterLimitEnabled"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"email,username,phone", "username,phone,email", "phone,phone,username"})
    void bootstrapPublishesOrderSeparatelyFromAuthenticationSwitches(String order) {
        var jdbc = mock(JdbcTemplate.class); var accounts = mock(ComputeAccountService.class);
        var locales = mock(EnabledLocaleResolver.class); var results = mock(ResultFactory.class);
        var presentation = mock(ComputePresentationService.class); var content = mock(ComputeContentService.class);
        var current = new HashMap<String, String>();
        current.put("phoneEnabled", "false"); current.put("usernameRequired", "true");
        current.put("usernameCharacterLimitEnabled", "false");
        current.put("memberUidStartBase", "200"); current.put("memberUidRandomStep", "3"); current.put("memberUidLastAssigned", "205");
        if (order != null) current.put("loginMethodOrder", order);
        when(accounts.settings()).thenReturn(current);
        when(locales.resolve("en")).thenReturn("en");
        when(presentation.publicSettings()).thenReturn(new LinkedHashMap<>(Map.of("defaultLanguage", "en")));
        when(content.userList(isNull(), anyMap(), eq("en"))).thenReturn(Map.of("records", List.of()));
        when(results.success(any())).thenAnswer(input -> new Result(0, input.getArgument(0), "成功"));
        var controller = new ComputePublicController(new org.token.service.compute.ComputeBootstrapService(accounts, locales, mock(SystemSettingService.class), mock(TranslationService.class), mock(ComputeCurrencyService.class), presentation, content, mock(ComputeUserFeaturesService.class), org.mockito.Mockito.mock(org.token.repository.compute.ComputePublicQueryRepository.class)), mock(org.token.custom.security.ClientIpResolver.class), results);

        Map<?, ?> response = (Map<?, ?>) controller.bootstrap("en", new org.springframework.mock.web.MockHttpServletRequest()).getData();

        assertEquals(order != null && List.of("email,username,phone", "username,phone,email").contains(order)
                ? List.of(order.split(",")) : List.of("phone", "email", "username"), response.get("loginMethodOrder"));
        Map<?, ?> fields = (Map<?, ?>) response.get("loginFields");
        assertEquals(Map.of("enabled", false, "required", false), fields.get("phone"));
        assertEquals(Map.of("enabled", true, "required", true), fields.get("username"));
        assertEquals(false, response.get("usernameCharacterLimitEnabled"));
        for (String key : List.of("memberUidStartBase", "memberUidRandomStep", "memberUidLastAssigned", "memberUidCurrentMax", "memberUidHighWatermark"))
            assertFalse(response.containsKey(key));
    }
}
