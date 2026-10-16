package org.token.custom.security;

import org.token.model.security.ComputeMemberPrincipal;

import org.junit.jupiter.api.Tag;

import jakarta.servlet.FilterChain;
import org.token.config.EnabledLocaleResolver;
import org.token.config.SecurityConfig;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.custom.security.*;
import org.token.service.SystemSettingService;
import org.token.custom.web.RequestTimeZoneFilter;
import org.token.config.compute.ComputeSecurityConfig;
import org.token.controller.user.ComputeAccountController;
import org.token.controller.publicapi.ComputeHomeController;
import org.token.controller.user.ComputeContentUserController;
import org.token.controller.user.ComputeUserController;
import org.token.controller.user.ComputeTeamController;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 用实际安全链重现开发代理 Origin/Host 不一致，并验证游客只能取得首页摘要。 */
@WebMvcTest({ComputeAccountController.class, ComputeHomeController.class, ComputeUserController.class, ComputeTeamController.class, ComputeContentUserController.class, org.token.controller.publicapi.ComputeContentPublicController.class, org.token.controller.publicapi.ComputeCatalogPublicController.class})
@Import({ComputeSecurityConfig.class, SecurityConfig.class, ComputeAccountApplicationService.class, ComputeHomeQueryService.class, ComputeUserApplicationService.class, ComputePublicContentService.class})
@Tag("regression")
class ComputeGuestAccessTest {
    @Autowired MockMvc mvc;
    @MockitoBean ComputeAccountService accounts;
    @MockitoBean ComputeBusinessService business;
    @MockitoBean ComputeWithdrawalCoordinator withdrawals;
    @MockitoBean ComputeTeamOverviewService team;
    @MockitoBean ComputeCatalogService catalog;
    @MockitoBean ComputeContentService content;
    @MockitoBean ComputeData data;
    @MockitoBean org.token.repository.compute.ComputeControllerQueryRepository queries;
    @MockitoBean EnabledLocaleResolver locales;
    @MockitoBean ResultFactory results;
    @MockitoBean org.token.custom.LoggingAspect logging;
    @MockitoBean ClientIpResolver ips;
    @MockitoBean JwtAuthenticationFilter adminJwt;
    @MockitoBean IpWhitelistFilter adminIp;
    @MockitoBean TotpEnforcementFilter adminTotp;
    @MockitoBean RequestTimeZoneFilter timezone;
    @MockitoBean JwtAuthenticationEntryPoint entryPoint;
    @MockitoBean JwtAccessDeniedHandler denied;

    @BeforeEach void setup() throws Exception {
        when(ips.resolve(any())).thenReturn("127.0.0.1");
        when(locales.resolve(any(jakarta.servlet.http.HttpServletRequest.class))).thenReturn("en");
        when(results.success(any())).thenAnswer(call -> new Result(0, call.getArgument(0), "成功"));
        when(results.create(anyInt(), any(), anyString())).thenAnswer(call -> new Result(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        when(results.failure(anyInt(), anyString(), any())).thenAnswer(call -> new Result(call.getArgument(0), null, call.getArgument(1)));
        when(results.failure(anyInt(), anyString())).thenAnswer(call -> new Result(call.getArgument(0), null, call.getArgument(1)));
        for (var filter : List.of(adminJwt, adminIp, adminTotp)) {
            doAnswer(call -> { ((FilterChain) call.getArgument(2)).doFilter(call.getArgument(0), call.getArgument(1)); return null; })
                    .when(filter).doFilter(any(), any(), any());
        }
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.resolveSystemTimeZone()).thenReturn("UTC");
        when(settings.resolveDisplayTimeZoneUsingSystem("UTC")).thenReturn("UTC");
        RequestTimeZoneFilter realTimeZoneFilter = new RequestTimeZoneFilter(settings);
        doAnswer(call -> {
            realTimeZoneFilter.doFilter(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            return null;
        }).when(timezone).doFilter(any(), any(), any());
        when(accounts.login(anyString(), any())).thenReturn(Map.of("token", "new-session"));
        when(accounts.register(any())).thenReturn(Map.of("token", "new-session"));
    }

    @Test void registrationAndAllLoginMethodsIgnoreStaleToken() throws Exception {
        for (String method : List.of("username", "email", "phone")) {
            mvc.perform(post("/api/compute/auth/login/" + method)
                    .with(request -> { request.setServerName("127.0.0.1"); request.setServerPort(5578); return request; })
                    .header("Origin", "http://127.0.0.1:5578")
                    .header("Authorization", "Bearer expired").contentType("application/json")
                    .content("{\"account\":\"member\",\"password\":\"1234567\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.token").value("new-session"));
        }
        mvc.perform(post("/api/compute/auth/register").header("Authorization", "Bearer expired").contentType("application/json")
                .content("{\"username\":\"member\",\"password\":\"1234567\"}"))
                .andExpect(status().isOk());
        verify(accounts, never()).authenticate(anyString());
        verifyNoInteractions(adminIp, adminJwt);
    }

    @Test void unchangedHostFixesCorsAfterViteChoosesAnotherPort() throws Exception {
        String body = "{\"account\":\"member\",\"password\":\"1234567\"}";
        // 旧代理将 Host 改为 8081，但 Origin 保留 5579，因此确实会被拦截。
        mvc.perform(post("/api/compute/auth/login/username").with(request -> { request.setServerName("localhost"); request.setServerPort(8081); return request; })
                .header("Origin", "http://127.0.0.1:5579").contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/compute/auth/login/username").with(request -> { request.setServerName("127.0.0.1"); request.setServerPort(5579); return request; })
                .header("Origin", "http://127.0.0.1:5579").contentType("application/json").content(body)).andExpect(status().isOk());
        verify(accounts, times(1)).login(eq("username"), any());
    }

    @Test void everyNonHomeBusinessReadRequiresMemberSession() throws Exception {
        for (String path : List.of("/public/catalog", "/public/currencies", "/public/content", "/public/content/1", "/public/legal/about", "/public/legal/terms/1", "/public/legal/termsExtra", "/public/activities", "/public/activities/1", "/user/me", "/user/wallets", "/user/team", "/user/team/overview")) {
            mvc.perform(get("/api/compute" + path)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(catalog, content, business, team);
    }

    @Test void registrationDocumentsIgnoreStaleSessionsAndCannotSelectOtherArticles() throws Exception {
        for (String key : List.of("TERMS", "PRIVACY")) {
            var fixedQuery = Map.of("kind", "ARTICLE", "contentKey", key, "page", "1", "size", "1");
            when(content.userList(isNull(), eq(fixedQuery), eq("en")))
                    .thenReturn(Map.of("records", List.of(Map.of("title", key, "bodyHtml", "<p>已发布正文</p>")), "total", 1));
            mvc.perform(get("/api/compute/public/legal/" + key.toLowerCase(java.util.Locale.ROOT))
                            .header("Authorization", "Bearer expired").param("contentKey", "GENERAL")
                            .param("kind", "MESSAGE").param("page", "2").param("size", "100"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].title").value(key))
                    .andExpect(jsonPath("$.data.records[0].bodyHtml").value("<p>已发布正文</p>"));
            verify(content).userList(null, fixedQuery, "en");
        }
        mvc.perform(post("/api/compute/public/legal/terms")).andExpect(status().isUnauthorized());
        verify(accounts, never()).authenticate(anyString());
        verifyNoInteractions(adminIp, adminJwt);
    }

    @Test void teamOverviewAlwaysUsesAuthenticatedUserEvenWhenQueryNamesAnotherRoot() throws Exception {
        when(accounts.authenticate("team-session")).thenReturn(new ComputeMemberPrincipal(7, "member"));
        when(team.overview(eq(7L), anyMap())).thenReturn(Map.of("summary", Map.of("memberCount", 2)));
        mvc.perform(get("/api/compute/user/team/overview").header("Authorization", "Bearer team-session")
                        .param("memberId", "999").param("username", "other-user"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.memberCount").value(2));
        verify(team).overview(eq(7L), anyMap());
        verify(team, never()).overview(eq(999L), anyMap());
    }

    @Test void guestHomeIsBoundedAndDoesNotReturnContentBodies() throws Exception {
        var row = Map.<String, Object>of("id", 1, "name", "demo");
        when(catalog.homeCatalog("en")).thenReturn(Map.of("products", java.util.Collections.nCopies(8, row), "models", List.of(), "levels", List.of(row), "summaryCurrency", "USDT"));
        when(content.userList(isNull(), anyMap(), eq("en"))).thenReturn(Map.of("records", List.of(Map.of("id", 1, "title", "Notice", "bodyHtml", "private full body"))));
        mvc.perform(get("/api/compute/public/home").header("Authorization", "Bearer expired"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.products.length()").value(4))
                .andExpect(jsonPath("$.data.levels").doesNotExist()).andExpect(jsonPath("$.data.announcements[0].bodyHtml").doesNotExist());
        verify(accounts, never()).authenticate(anyString());
    }

    @Test void sixCharacterNumericPasswordPassesAndFiveCharactersFail() throws Exception {
        mvc.perform(post("/api/compute/auth/register").contentType("application/json").content("{\"username\":\"member\",\"password\":\"1234567\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/compute/auth/register").contentType("application/json").content("{\"username\":\"member\",\"password\":\"123456\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/compute/auth/register").contentType("application/json").content("{\"username\":\"member\",\"password\":\"12345\"}"))
                .andExpect(jsonPath("$.code").value(400)).andExpect(jsonPath("$.message").value("server.compute.v8.passwordRule"));
        verify(accounts, times(2)).register(any());
    }
}
