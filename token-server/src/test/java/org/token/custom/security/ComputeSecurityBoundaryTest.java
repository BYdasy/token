package org.token.custom.security;

import org.token.model.security.JwtUserPrincipal;

import org.token.model.security.ComputeMemberPrincipal;
import org.token.service.compute.ComputeAgentScope;

import org.junit.jupiter.api.Tag;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.token.controller.user.ComputeAccountController;
import org.token.controller.admin.ComputeMemberAccessController;
import org.token.service.compute.ComputeMemberAccessService;
import org.token.controller.admin.ComputeAdminController;
import org.token.controller.admin.ComputeAnalyticsController;
import org.token.service.compute.ComputeAnalyticsService;
import org.token.controller.admin.ComputeCurrencyController;
import org.token.controller.callback.ComputeGatewayController;
import org.token.service.compute.ComputePaymentGateway;
import org.token.service.compute.ComputeCatalogService;
import org.token.service.compute.ComputeCurrencyService;
import org.token.service.compute.ComputeJobService;
import org.token.service.compute.ComputeAccountService;
import org.token.controller.admin.ComputeRecoveryAdminController;
import org.token.config.compute.ComputeSecurityConfig;
import org.token.controller.admin.ComputeActivityController;
import org.token.service.compute.ComputeActivityService;
import org.token.service.compute.ComputeWheelService;
import org.token.controller.admin.ComputeContentAdminController;
import org.token.service.compute.ComputeContentService;
import org.token.service.compute.ComputeContentTranslationService;
import org.token.repository.compute.ComputeData;
import org.token.controller.admin.ComputeManagementController;
import org.token.controller.admin.ComputeFinanceManualController;
import org.token.controller.admin.ComputePresentationController;
import org.token.service.compute.ComputePresentationService;
import org.token.service.compute.ComputeManagementService;
import org.token.service.compute.ComputeWithdrawalCoordinator;
import org.token.service.compute.ComputeMerchantService;
import org.token.config.EnabledLocaleResolver;
import org.token.config.SecurityConfig;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.exception.BusinessException;
import org.token.custom.security.*;
import org.token.service.SystemSettingService;
import org.token.tools.time.RequestTimeZoneContext;
import org.token.custom.web.RequestTimeZoneFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证用户令牌与管理员入口分离、公共读取边界及 Servlet 过滤器注册。 */
@WebMvcTest(controllers = {ComputeAccountController.class, ComputeMemberAccessController.class, ComputeRecoveryAdminController.class, ComputeActivityController.class,
        ComputeContentAdminController.class, ComputeManagementController.class, ComputeFinanceManualController.class,ComputeAdminController.class, ComputeCurrencyController.class,ComputeGatewayController.class,ComputePresentationController.class,ComputeAnalyticsController.class, org.token.controller.user.ComputeImpersonationController.class,
        org.token.controller.publicapi.ComputeActivityPublicController.class, org.token.controller.user.ComputeActivityUserController.class,
        org.token.controller.publicapi.ComputeCurrencyPublicController.class, org.token.controller.admin.ComputeGatewayAdminController.class,
        org.token.controller.user.ComputeDepositGatewayController.class, org.token.controller.publicapi.ComputeRuntimeController.class, org.token.controller.user.ComputePresentationUserController.class,
        org.token.controller.admin.ComputeMcagController.class}, properties = "token.mcag.enabled=true")
@Import({ComputeSecurityConfig.class, SecurityConfig.class, org.token.service.compute.ComputeAccountApplicationService.class,
        org.token.service.compute.ComputeActivityApplicationService.class, org.token.service.compute.ComputeContentManagementService.class,
        org.token.service.compute.ComputeCurrencyManagementService.class, org.token.service.compute.ComputeGatewayAdminService.class, org.token.service.compute.ComputeDepositGatewayService.class})
@Tag("regression")
class ComputeSecurityBoundaryTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private ComputeAccountService accounts;
    @MockitoBean private ComputeMemberAccessService memberAccess;
    @MockitoBean private ComputeActivityService activities;
    @MockitoBean private ComputeWheelService wheel;
    @MockitoBean private ComputeContentService content;
    @MockitoBean private ComputeContentTranslationService contentTranslations;
    @MockitoBean private ComputeManagementService management;
    @MockitoBean private ComputeAnalyticsService analytics;
    @MockitoBean private org.token.service.compute.ComputeInviteCodeService inviteCodes;
    @MockitoBean private ComputeWithdrawalCoordinator withdrawals;
    @MockitoBean private org.token.service.compute.ComputeHomeFinanceService homeFinance;
    @MockitoBean private ComputeData data;
    @MockitoBean private org.token.repository.compute.ComputeControllerQueryRepository queries;
    @MockitoBean private ComputeCatalogService catalog;
    @MockitoBean private ComputePresentationService presentation;
    @MockitoBean private ComputeCurrencyService currencies;
    @MockitoBean private ComputeJobService jobs;
    @MockitoBean private ComputeAgentScope scope;
    @MockitoBean private ComputePaymentGateway gateway;
    @MockitoBean private ComputeMerchantService merchant;
    @MockitoBean private org.token.service.compute.ComputeMcagAdminService mcagAdmin;
    @MockitoBean private ResultFactory results;
    @MockitoBean private org.token.custom.LoggingAspect logging;
    @MockitoBean private EnabledLocaleResolver locales;
    @MockitoBean private ClientIpResolver ipResolver;
    @MockitoBean private JwtAuthenticationFilter adminJwt;
    @MockitoBean private IpWhitelistFilter adminIp;
    @MockitoBean private TotpEnforcementFilter adminTotp;
    @MockitoBean private RequestTimeZoneFilter timezone;
    @MockitoBean private JwtAuthenticationEntryPoint entryPoint;
    @MockitoBean private JwtAccessDeniedHandler denied;

    @BeforeEach
    void configureBoundary() throws Exception {
        when(ipResolver.resolve(any())).thenReturn("127.0.0.1");
        when(accounts.authenticate("member-token")).thenReturn(new ComputeMemberPrincipal(7, "member"));
        when(accounts.member(7)).thenReturn(Map.of("id", 7, "username", "member"));
        when(results.success(any())).thenAnswer(invocation -> new Result(0, invocation.getArgument(0), "操作成功"));
        when(results.create(anyInt(), any(), anyString())).thenAnswer(invocation -> new Result(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        when(results.failure(anyInt(), anyString(), any())).thenAnswer(invocation -> new Result(invocation.getArgument(0), null, invocation.getArgument(1)));
        for (var filter : new org.springframework.web.filter.OncePerRequestFilter[]{adminJwt, adminIp, adminTotp}) {
            doAnswer(invocation -> { ((FilterChain) invocation.getArgument(2)).doFilter(invocation.getArgument(0), invocation.getArgument(1)); return null; })
                    .when(filter).doFilter(any(), any(), any());
        }
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.resolveSystemTimeZone()).thenReturn("UTC");
        when(settings.resolveDisplayTimeZoneUsingSystem("UTC")).thenReturn("UTC");
        RequestTimeZoneFilter realTimeZoneFilter = new RequestTimeZoneFilter(settings);
        doAnswer(invocation -> {
            realTimeZoneFilter.doFilter(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(timezone).doFilter(any(), any(), any());
        doAnswer(invocation -> { ((HttpServletResponse) invocation.getArgument(1)).setStatus(401); return null; })
                .when(entryPoint).commence(any(), any(), any());
        doAnswer(invocation -> { ((HttpServletResponse) invocation.getArgument(1)).setStatus(403); return null; })
                .when(denied).handle(any(), any(), any());
    }

    @Test
    void globalReportRequiresItsOwnLeafAndDoesNotGrantHomeOrPersonalReports() throws Exception {
        for(String authority:List.of("4","403","40100","40200","10100","610","61012")) {
            authenticateAdminWith(authority);
            mvc.perform(get("/api/compute/admin/reports/global")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(analytics);
        authenticateAdminWith("40300");
        when(analytics.globalReport(anyMap())).thenReturn(Map.of("current",Map.of("newMembers",1)));
        mvc.perform(get("/api/compute/admin/reports/global").param("startTime","2026-09-27 10:15:30").param("endTime","2026-09-27 11:20:45"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.current.newMembers").value(1));
        mvc.perform(get("/api/compute/admin/analytics")).andExpect(status().isForbidden());
        mvc.perform(get("/api/compute/admin/reports/personal")).andExpect(status().isForbidden());
        verify(analytics).globalReport(Map.of("startTime","2026-09-27 10:15:30","endTime","2026-09-27 11:20:45"));
    }

    @Test
    void overviewUsesTodayWithoutReusingSearchParameters() throws Exception {
        authenticateAdminWith("10100");
        when(analytics.dashboardReport()).thenReturn(Map.of("startDate","2026-09-28","endDate","2026-09-28"));
        mvc.perform(get("/api/compute/admin/analytics").param("startDate","2000-01-01").param("groupId","42"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.startDate").value("2026-09-28"));
        verify(analytics).dashboardReport();
        verify(analytics,never()).dailyReport(anyMap());
    }

    @Test
    void newSameOriginDomainStillRequiresCredentialsAndDoesNotCrossAdminBoundary() throws Exception {
        when(ipResolver.isTrustedProxyRequest(any())).thenReturn(true);
        mvc.perform(get("/api/compute/user/me").header("Host", "new-site.example")
                .header("Origin", "https://new-site.example").header("X-Forwarded-Proto", "https"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/compute/user/me").header("Host", "new-site.example")
                .header("Origin", "https://new-site.example").header("X-Forwarded-Proto", "https")
                .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(7));
        mvc.perform(get("/api/compute/admin/recoveries").header("Host", "new-site.example")
                .header("Origin", "https://new-site.example").header("X-Forwarded-Proto", "https")
                .header("Authorization", "Bearer member-token"))
                .andExpect(status().isUnauthorized());
        verify(accounts, never()).recoveries(anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void memberTokenReadsOnlyItsOwnAccountAndNeverAuthenticatesAsAdmin() throws Exception {
        mvc.perform(get("/api/compute/user/me").header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(7));
        verify(timezone).doFilter(any(), any(), any());
        assertFalse(RequestTimeZoneContext.isResponseBody(), "用户响应结束后必须清理线程上下文");
        assertFalse(RequestTimeZoneContext.isUserApi(), "用户身份的时间展示标记不能残留");
        mvc.perform(get("/api/compute/admin/recoveries").header("Authorization", "Bearer member-token"))
                .andExpect(status().isUnauthorized());
        verify(accounts, never()).recoveries(anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void missingOrInvalidMemberCredentialsCannotReadPrivateData() throws Exception {
        mvc.perform(get("/api/compute/user/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/compute/user/me").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        verify(accounts, never()).member(anyLong());
    }

    @Test
    void onlyDepositAndPayoutPostsReachIndependentCallbackVerification() throws Exception {
        var failure = new BusinessException(502, "server.compute.payment.invalidResponse");
        doThrow(failure).when(gateway).acceptDepositCallback(any(), anyMap(), anyString());
        doThrow(failure).when(gateway).acceptPayoutCallback(any(), anyMap(), anyString());
        when(results.failure(anyInt(), anyString())).thenAnswer(invocation ->
                new Result(invocation.getArgument(0), null, invocation.getArgument(1)));
        for (String kind : List.of("deposit", "payout")) {
            String path = "/api/compute/callbacks/" + kind;
            mvc.perform(post(path).contentType("application/jose").content("invalid-envelope"))
                    .andExpect(status().isBadGateway());
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        verify(gateway).acceptDepositCallback(any(), anyMap(), eq("/api/compute/callbacks/deposit"));
        verify(gateway).acceptPayoutCallback(any(), anyMap(), eq("/api/compute/callbacks/payout"));
        verifyNoInteractions(adminJwt, adminIp, adminTotp, accounts);
        verify(results, never()).success();
    }

    @Test
    void retiredAndGenericCallbackPathsAreDeniedEvenWithMemberCredentials() throws Exception {
        for (String kind : List.of("deposit-exception", "payout-exception", "event", "generic", "deposit/extra")) {
            String path = "/api/compute/callbacks/" + kind;
            mvc.perform(post(path).contentType("application/jose").content("invalid-envelope"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(path).header("Authorization", "Bearer member-token")
                            .contentType("application/jose").content("invalid-envelope"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(gateway);
        verify(results, never()).success();
    }

    @Test
    void sessionAdministrationRequiresDedicatedPermissionsAndExchangeStaysUserOnly() throws Exception {
        when(memberAccess.issue(7)).thenReturn(Map.of("ticket","single-use"));
        when(memberAccess.exchange("single-use")).thenReturn(Map.of("token","user-only"));
        mvc.perform(post("/api/compute/admin/members/7/impersonation").header("Authorization","Bearer member-token")).andExpect(status().isUnauthorized());
        authenticateAdminWith("20102");
        mvc.perform(post("/api/compute/admin/members/7/impersonation")).andExpect(status().isForbidden());
        mvc.perform(post("/api/compute/admin/members/7/force-logout")).andExpect(status().isForbidden());
        authenticateAdminWith("20105");
        mvc.perform(post("/api/compute/admin/members/7/impersonation")).andExpect(status().isOk());
        mvc.perform(post("/api/compute/admin/members/7/force-logout")).andExpect(status().isForbidden());
        authenticateAdminWith("20106");
        mvc.perform(post("/api/compute/admin/members/7/force-logout")).andExpect(status().isOk());
        mvc.perform(post("/api/compute/auth/impersonation").header("Authorization","Bearer old-user-token").contentType("application/json").content("{\"ticket\":\"single-use\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.token").value("user-only"));
        verify(memberAccess,times(1)).issue(7);verify(memberAccess,times(1)).forceLogout(7);
    }

    @Test
    void presentationAndUnreadRemainPrivateAndAdminDisplaySettingsUseExistingLeaves() throws Exception {
        when(presentation.member(7)).thenReturn(Map.of("username", "member"));
        when(presentation.unread(7)).thenReturn(Map.of("total", 2));
        for (String path : List.of("/user/presentation", "/user/unread-count"))
            mvc.perform(get("/api/compute" + path)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/compute/user/presentation").header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("member"));
        mvc.perform(get("/api/compute/user/unread-count").header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
        mvc.perform(get("/api/compute/admin/presentation-settings").header("Authorization", "Bearer member-token"))
                .andExpect(status().isUnauthorized());
        authenticateAdminWith("70500");
        mvc.perform(get("/api/compute/admin/presentation-settings")).andExpect(status().isForbidden());
        authenticateAdminWith("61200");
        mvc.perform(get("/api/compute/admin/presentation-settings")).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/compute/admin/presentation-settings").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        authenticateAdminWith("61201");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/compute/admin/presentation-settings").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        verify(presentation).member(7); verify(presentation).unread(7); verify(presentation).save(anyMap());
    }

    @Test
    void supportPermissionsCannotAccessComputeRecoveryWhileComputeAndSuperCan() throws Exception {
        when(accounts.recoveries(anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(Map.of("records", List.of(), "total", 0));

        // 已移除模块的 609 权限不能访问算力接口。
        for (String authority : List.of("609", "60904")) {
            authenticateAdminWith(authority);
            mvc.perform(get("/api/compute/admin/recoveries")).andExpect(status().isForbidden());
        }
        verify(accounts, never()).recoveries(anyInt(), anyInt(), any(), any(), any());

        for (String authority : List.of("20500", "20501", "super")) {
            authenticateAdminWith(authority);
            mvc.perform(get("/api/compute/admin/recoveries"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        }
        verify(accounts, times(3)).recoveries(anyInt(), anyInt(), any(), any(), any());
    }

    private void authenticateAdminWith(String authority) throws Exception {
        doAnswer(invocation -> {
            var authentication = new UsernamePasswordAuthenticationToken(JwtUserPrincipal.builder().uid(2).username("operator").build(), null,
                    AuthorityUtils.createAuthorityList(authority));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            try {
                ((FilterChain) invocation.getArgument(2)).doFilter(invocation.getArgument(0), invocation.getArgument(1));
            } finally {
                SecurityContextHolder.clearContext();
            }
            return null;
        }).when(adminJwt).doFilter(any(), any(), any());
    }

    @Test
    void manualFinanceRequiresMatchingReviewLeafAndNeverAcceptsMemberToken() throws Exception {
        when(management.manualDeposit(anyMap())).thenReturn(Map.of("id",1,"memberId",1));
        when(withdrawals.manualWithdrawal(anyMap())).thenReturn(Map.of("id",2,"memberId",1));
        when(management.manualWithdrawalQuote(anyMap())).thenReturn(Map.of("fee","0","netAmount","1"));
        mvc.perform(post("/api/compute/admin/deposits/manual").header("Authorization","Bearer member-token").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/compute/admin/withdrawals/manual").header("Authorization","Bearer member-token").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(withdrawals);
        authenticateAdminWith("30100");
        mvc.perform(post("/api/compute/admin/deposits/manual").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        authenticateAdminWith("30101");
        mvc.perform(post("/api/compute/admin/deposits/manual").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/compute/admin/withdrawals/manual").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        verify(withdrawals,never()).manualWithdrawal(anyMap());
        authenticateAdminWith("30201");
        mvc.perform(post("/api/compute/admin/withdrawals/manual-quote").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/compute/admin/withdrawals/manual").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        verify(management,times(1)).manualDeposit(anyMap());verify(withdrawals,times(1)).manualWithdrawal(anyMap());
    }

    @Test
    void readLeavesCannotPublishOrManageOtherModules() throws Exception {
        when(content.adminList(anyMap())).thenReturn(Map.of("records",List.of(),"total",0));
        when(activities.adminPage(anyMap())).thenReturn(Map.of("records",List.of(),"total",0));
        authenticateAdminWith("70500");
        mvc.perform(get("/api/compute/admin/content").param("kind","MESSAGE")).andExpect(status().isOk());
        mvc.perform(post("/api/compute/admin/content/1/publish")).andExpect(status().isForbidden());
        mvc.perform(get("/api/compute/admin/activities")).andExpect(status().isForbidden());
        mvc.perform(get("/api/compute/admin/agent-bindings")).andExpect(status().isForbidden());
        authenticateAdminWith("50200");
        // 活动读取必须声明分类，各分类使用自己的叶子权限。
        mvc.perform(get("/api/compute/admin/activities")).andExpect(status().isForbidden());
        mvc.perform(get("/api/compute/admin/activities").param("type","CHEST")).andExpect(status().isForbidden());
        mvc.perform(get("/api/compute/admin/activities").param("type","RED_PACKET")).andExpect(status().isForbidden());
        mvc.perform(get("/api/compute/admin/activities").param("type","WHEEL")).andExpect(status().isOk());
        mvc.perform(post("/api/compute/admin/activities").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        verify(content,never()).publish(anyLong());verify(activities,never()).save(anyMap());
        verify(management,never()).bindings(anyMap());
    }

    @Test
    void writesAcceptTheirOwnLeafAndSuper() throws Exception {
        when(content.publish(1)).thenReturn(Map.of("id",1,"status","PUBLISHING"));
        when(content.adminDetail(1)).thenReturn(Map.of("id",1L,"kind","MESSAGE"));
        for(String authority:List.of("70501","super")) {
            authenticateAdminWith(authority);
            mvc.perform(post("/api/compute/admin/content/1/publish")).andExpect(status().isOk());
        }
        verify(content,times(2)).publish(1);
    }

    @Test
    void dangerousOperationsNeedTheirOwnLeaves() throws Exception {
        authenticateAdminWith("20102");
        for(String path:List.of("/members/1/adjustments","/members/1/inviter-preview","/members/1/inviter-transfer"))
            mvc.perform(post("/api/compute/admin"+path).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        verify(management,never()).adjustMoney(anyLong(),anyMap());verify(management,never()).transferInviter(anyLong(),anyMap());
        authenticateAdminWith("20103");when(management.adjustMoney(anyLong(),anyMap())).thenReturn(Map.of("operationId",1));
        mvc.perform(post("/api/compute/admin/members/1/adjustments").contentType("application/json").content("{}")).andExpect(status().isOk());
        mvc.perform(post("/api/compute/admin/members/1/inviter-transfer").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        authenticateAdminWith("20104");when(management.transferInviter(anyLong(),anyMap())).thenReturn(Map.of("memberId",1));
        mvc.perform(post("/api/compute/admin/members/1/inviter-transfer").contentType("application/json").content("{}")).andExpect(status().isOk());
    }

    @Test
    void memberBearerNeverCrossesNewManagementEndpoints() throws Exception {
        for(String path:List.of("/members/1/adjustments","/members/1/inviter-preview","/members/1/inviter-transfer"))
            mvc.perform(post("/api/compute/admin"+path).header("Authorization","Bearer member-token").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(management);
    }

    @Test
    void inviteCodeRotationRequiresMemberEditInsteadOfForceLogoutPermission() throws Exception {
        String path = "/api/compute/admin/members/1/invite-code/rotate";
        String body = "{\"expectedInviteCode\":\"original\",\"requestNo\":\"rotation-check\"}";
        mvc.perform(post(path).header("Authorization", "Bearer member-token").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        authenticateAdminWith("20106");
        mvc.perform(post(path).contentType("application/json").content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(inviteCodes);
        authenticateAdminWith("20102");
        when(inviteCodes.rotate(eq(1L), anyMap())).thenReturn(Map.of("memberId", 1, "inviteCode", "NEW123"));
        mvc.perform(post(path).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.inviteCode").value("NEW123"));
        verify(inviteCodes).rotate(eq(1L), anyMap());
    }

    @Test
    void financeAndCurrencyLeavesDoNotImplicitlyAuthorizeOtherActions() throws Exception {
        when(management.finances(anyString(),anyMap())).thenReturn(Map.of("records",List.of(),"total",0));
        authenticateAdminWith("30100");
        mvc.perform(get("/api/compute/admin/deposits")).andExpect(status().isOk());
        mvc.perform(get("/api/compute/admin/withdrawals")).andExpect(status().isForbidden());
        mvc.perform(post("/api/compute/admin/deposits/1/review").contentType("application/json").content("{\"approved\":true}")).andExpect(status().isForbidden());
        authenticateAdminWith("30101");when(management.reviewDeposit(anyLong(),anyBoolean(),anyString(),anyInt())).thenReturn(Map.of("id",1));
        mvc.perform(post("/api/compute/admin/deposits/1/review").contentType("application/json").content("{\"approved\":true}")).andExpect(status().isOk());
        mvc.perform(post("/api/compute/admin/withdrawals/1/review").contentType("application/json").content("{\"approved\":true}")).andExpect(status().isForbidden());
        when(currencies.page(anyString(),anyMap())).thenReturn(Map.of("records",List.of(),"total",0));
        authenticateAdminWith("70400");
        mvc.perform(get("/api/compute/admin/currencies")).andExpect(status().isOk());
        mvc.perform(post("/api/compute/admin/currencies").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        authenticateAdminWith("70402");when(currencies.save(anyString(),isNull(),anyMap())).thenReturn(Map.of("id",1));
        mvc.perform(post("/api/compute/admin/currencies").contentType("application/json").content("{}")).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/compute/admin/currencies/1").contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }

    @Test
    void administratorFiltersAreOnlyRegisteredInTheirSecurityChain() {
        ComputeSecurityConfig config = new ComputeSecurityConfig();
        assertFalse(config.computeJwtServletRegistration(adminJwt).isEnabled());
        assertFalse(config.computeIpServletRegistration(adminIp).isEnabled());
        assertFalse(config.computeTotpServletRegistration(adminTotp).isEnabled());
        assertFalse(config.computeTimezoneServletRegistration(timezone).isEnabled());
    }

    @Test
    void boundAdministratorCannotQueryGlobalGatewayAccounts() throws Exception {
        authenticateAdminWith("30200");doThrow(ComputeAgentScope.denied()).when(scope).requireGlobalScope();
        mvc.perform(get("/api/compute/admin/gateway/payouts/reference-one")).andExpect(status().isForbidden());
        verifyNoInteractions(gateway);
    }

    @Test
    void depositInvestigationAllowsItsReadLeafAndSuperWithPagination() throws Exception {
        when(mcagAdmin.status(anyInt(), anyInt())).thenAnswer(invocation ->
                org.token.model.response.OperationOutcome.success(Map.of("review", List.of(), "total", 125L,
                        "page", invocation.getArgument(0), "pageSize", invocation.getArgument(1))));
        for (String authority : List.of("30100", "super")) {
            authenticateAdminWith(authority);
            mvc.perform(get("/api/compute/admin/gateway/deposits/status").param("page", "6").param("pageSize", "20"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(6))
                    .andExpect(jsonPath("$.data.pageSize").value(20)).andExpect(jsonPath("$.data.total").value(125));
        }
        verify(mcagAdmin, times(2)).status(6, 20);
        mvc.perform(get("/api/compute/admin/gateway/deposits/status"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(20));
        verify(mcagAdmin).status(1, 20);
        verifyNoInteractions(gateway, merchant);
    }

    @Test
    void depositInvestigationRejectsMembersAndUnrelatedAuthoritiesBeforeBusiness() throws Exception {
        mvc.perform(get("/api/compute/admin/gateway/deposits/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/compute/admin/gateway/deposits/status").header("Authorization", "Bearer member-token"))
                .andExpect(status().isUnauthorized());
        for (String authority : List.of("3", "301", "30101", "30200", "61006", "610061", "610052", "80200")) {
            authenticateAdminWith(authority);
            mvc.perform(get("/api/compute/admin/gateway/deposits/status").param("page", "2").param("pageSize", "20"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(mcagAdmin, gateway, merchant);
    }

    @Test
    void depositInvestigationRejectsMalformedPaginationBeforeBusiness() throws Exception {
        authenticateAdminWith("30100");
        when(results.failure(400, "server.param.error")).thenReturn(new Result(400, null, "server.param.error"));
        // 参数绑定沿用现有HTTP 200/业务码400契约，非法数值不得到达Service。
        for (String parameter : List.of("page", "pageSize")) {
            for (String value : List.of("abc", "1.5", "2147483648")) {
                mvc.perform(get("/api/compute/admin/gateway/deposits/status").param(parameter, value))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(400));
            }
        }
        verifyNoInteractions(mcagAdmin, gateway, merchant);
    }

    @Test
    void depositReconciliationRemainsSuperOnlyAndUsesOriginalReference() throws Exception {
        String body = "{\"reference\":\"deposit-order-one\",\"kind\":\"DEPOSIT\",\"remark\":\"核对原充值订单\",\"code\":\"123456\"}";
        for (String authority : List.of("30100", "30101", "610", "61005", "610051", "610061", "80204")) {
            authenticateAdminWith(authority);
            mvc.perform(post("/api/compute/admin/gateway/reconciliation").contentType("application/json").content(body))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(merchant, gateway);
        authenticateAdminWith("super");
        when(gateway.requestReconciliation("deposit-order-one", "DEPOSIT"))
                .thenReturn(Map.of("depositId", "deposit-order-one", "state", "REVIEW"));
        mvc.perform(post("/api/compute/admin/gateway/reconciliation").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.depositId").value("deposit-order-one"))
                .andExpect(jsonPath("$.data.state").value("REVIEW"));
        var order = inOrder(merchant, gateway);
        order.verify(merchant).requireSuperTotp("123456");
        order.verify(gateway).requestReconciliation("deposit-order-one", "DEPOSIT");
    }

    @Test
    void malformedDepositReconciliationNeverReachesTotpOrGateway() throws Exception {
        authenticateAdminWith("super");
        when(results.failure(400, "server.compute.request.invalid"))
                .thenReturn(new Result(400, null, "server.compute.request.invalid"));
        String body = "{\"reference\":\"deposit-order-one\",\"kind\":\"DEPOSIT\",\"remark\":\"核对原充值订单\",\"code\":\"123456\"}";
        for (String invalid : List.of(body.replace("deposit-order-one", ""), body.replace("deposit-order-one", "x".repeat(129)),
                body.replace("核对原充值订单", ""), body.replace("123456", ""), body.replace("123456", "1234567"))) {
            mvc.perform(post("/api/compute/admin/gateway/reconciliation").contentType("application/json").content(invalid))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(400));
        }
        verifyNoInteractions(merchant, gateway, mcagAdmin);
    }

    @Test
    void depositHashRecheckCannotBeAuthorizedByFinanceOrLegacyMerchantPermissions() throws Exception {
        for (String authority:List.of("30100","30101","80204","610","610061","610247")) {
            authenticateAdminWith(authority);
            mvc.perform(get("/api/compute/admin/gateway/deposits/recheck-options")).andExpect(status().isForbidden());
            mvc.perform(post("/api/compute/admin/gateway/deposits/recheck").contentType("application/json")
                    .content(depositRecheckJson())).andExpect(status().isForbidden());
        }
        verifyNoInteractions(merchant,gateway);
    }

    @Test
    void depositHashRecheckOnlyPassesAllowedCluesAndRejectsMalformedFields() throws Exception {
        authenticateAdminWith("super");
        when(merchant.depositRecheck(any())).thenReturn(Map.of("recheckId","r1","status","PENDING"));
        mvc.perform(post("/api/compute/admin/gateway/deposits/recheck").contentType("application/json").content(depositRecheckJson()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING"));
        verify(merchant).depositRecheck(new org.token.model.request.compute.DepositHashRecheckRequest("r1","USDT_TRON","TRON_MAINNET","a".repeat(64),"T-address","log:1","123456"));
        clearInvocations(merchant);
        // 既有全局参数绑定处理器通过响应业务码返回400，HTTP沿用200；仍必须在进入业务前拒绝。
        when(results.failure(400,"server.compute.request.invalid")).thenReturn(new Result(400,null,"server.compute.request.invalid"));
        mvc.perform(post("/api/compute/admin/gateway/deposits/recheck").contentType("application/json")
                .content(depositRecheckJson().replace("log:1","x".repeat(81))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(merchant,gateway);
    }

    private String depositRecheckJson() {
        return "{\"requestId\":\"r1\",\"assetId\":\"USDT_TRON\",\"networkId\":\"TRON_MAINNET\",\"txHash\":\""+"a".repeat(64)
                +"\",\"address\":\"T-address\",\"transferLocator\":\"log:1\",\"code\":\"123456\",\"merchantId\":\"other\",\"environment\":\"OTHER\",\"amount\":\"99999\"}";
    }

    @Test
    void legacyReconciliationCannotBeAuthorizedByFinanceOrMerchantMenuPermissions() throws Exception {
        for (String authority : List.of("610", "61006", "610061", "610062", "61024", "610247")) {
            authenticateAdminWith(authority);
            mvc.perform(post("/api/compute/admin/gateway/reconciliation").contentType("application/json")
                            .content("{\"reference\":\"WD_order\",\"kind\":\"PAYOUT\",\"remark\":\"核查原提款\",\"code\":\"123456\"}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(merchant, gateway);
    }

    @Test
    void superStillNeedsFreshOtpBeforeReconciliationCanReachGateway() throws Exception {
        authenticateAdminWith("super");
        doThrow(new BusinessException(403, "server.auth.totp.codeInvalid")).when(merchant).requireSuperTotp("123456");
        when(results.create(eq(403), eq(""), eq("server.auth.totp.codeInvalid")))
                .thenReturn(new Result(403, null, "server.auth.totp.codeInvalid"));
        mvc.perform(post("/api/compute/admin/gateway/reconciliation").contentType("application/json")
                        .content("{\"reference\":\"WD_order\",\"kind\":\"PAYOUT\",\"remark\":\"核查原提款\",\"code\":\"123456\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        verify(merchant).requireSuperTotp("123456");
        verifyNoInteractions(gateway);
    }
}
