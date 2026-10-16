package org.token.config;

import org.junit.jupiter.api.Tag;

import org.token.controller.publicapi.I18nController;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.response.AdminI18nBundle;
import org.token.custom.security.JwtAccessDeniedHandler;
import org.token.custom.security.JwtAuthenticationEntryPoint;
import org.token.custom.security.JwtAuthenticationFilter;
import org.token.custom.security.IpWhitelistFilter;
import org.token.custom.security.TotpEnforcementFilter;
import org.token.custom.web.RequestTimeZoneFilter;
import org.token.service.AccountService;
import org.token.service.RoleService;
import org.token.service.TranslationService;
import org.token.service.IpWhitelistService;
import org.token.service.SystemSettingService;
import org.token.custom.security.ClientIpResolver;
import static org.mockito.Mockito.doAnswer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(I18nController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@Tag("regression")
class I18nEndpointSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TranslationService translationService;

    @MockitoBean
    private ResultFactory resultFactory;

    @MockitoBean
    private EnabledLocaleResolver localeResolver;

    @MockitoBean
    private AccountService accountService;
    @MockitoBean
    private org.token.service.auth.AdminLoginSessionService loginSessions;

    @MockitoBean
    private RoleService roleService;

    @MockitoBean
    private JwtAuthenticationEntryPoint authenticationEntryPoint;

    @MockitoBean
    private JwtAccessDeniedHandler accessDeniedHandler;

    @MockitoBean
    private IpWhitelistService ipWhitelistService;

    @MockitoBean
    private ClientIpResolver clientIpResolver;

    @MockitoBean
    private IpWhitelistFilter ipWhitelistFilter;

    @MockitoBean
    private TotpEnforcementFilter totpEnforcementFilter;

    @MockitoBean
    private RequestTimeZoneFilter requestTimeZoneFilter;

    @Test
    void adminLanguageBundleCanBeLoadedWithoutAuthentication() throws Exception {
        doAnswer(invocation -> {
            jakarta.servlet.FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(ipWhitelistFilter).doFilter(any(), any(), any());
        doAnswer(invocation -> {
            jakarta.servlet.FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(totpEnforcementFilter).doFilter(any(), any(), any());
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.resolveSystemTimeZone()).thenReturn("UTC");
        when(settings.resolveDisplayTimeZone(null)).thenReturn("UTC");
        RequestTimeZoneFilter realTimeZoneFilter = new RequestTimeZoneFilter(settings);
        doAnswer(invocation -> {
            realTimeZoneFilter.doFilter(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(requestTimeZoneFilter).doFilter(any(), any(), any());
        AdminI18nBundle bundle = new AdminI18nBundle("zh", List.of(), Map.of("admin.title", "管理端"));
        when(translationService.getAdminI18nBundle("zh")).thenReturn(bundle);
        when(resultFactory.success(bundle)).thenReturn(new Result(0, bundle, "操作成功"));

        mockMvc.perform(get("/api/i18n/admin").param("lang", "zh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.locale").value("zh"))
                .andExpect(jsonPath("$.data.messages['admin.title']").value("管理端"));
    }
}
