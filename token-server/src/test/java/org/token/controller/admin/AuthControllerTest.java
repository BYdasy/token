package org.token.controller.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.token.model.entity.Account;
import org.token.model.entity.AccountProfile;
import org.token.tools.response.ResultFactory;
import org.token.model.security.JwtUserPrincipal;
import org.token.service.auth.AdminLoginAttemptService;
import org.token.service.AccountProfileService;
import org.token.service.AccountService;
import org.token.service.TotpService;
import org.token.model.response.TotpStatusView;
import org.token.tools.security.JWTUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AccountProfileService accountProfileService;

    @Mock
    private AccountService accountService;

    @Mock
    private ResultFactory resultFactory;

    @Mock
    private AdminLoginAttemptService loginAttempts;

    @Mock
    private TotpService totpService;
    @Mock
    private org.token.service.auth.AdminLoginSessionService loginSessions;

    @Mock
    private org.token.custom.security.ClientIpResolver clientIpResolver;

    @Mock
    private HttpServletRequest httpServletRequest;

    private AuthController controller;

    @BeforeEach
    void setUpJwtSecret() {
        ReflectionTestUtils.setField(JWTUtil.class, "jwtSecret", "unit-test-secret");
        controller = new AuthController(new org.token.service.admin.AdminAuthenticationService(accountProfileService, accountService, loginAttempts, totpService, org.mockito.Mockito.mock(org.token.service.RoleService.class), loginSessions), resultFactory, clientIpResolver);
    }

    @Test
    void loginWithValidPasswordReturnsAccessTokenWithoutSecondFactor() {
        Account account = new Account();
        account.setUid(1);
        account.setUsername("admin");
        account.setPassword("bcrypt-hash");
        account.setState(1);

        AccountProfile profile = new AccountProfile();
        profile.setUid(1);
        profile.setUsername("admin");
        profile.setLoginCount(0);

        when(accountService.findByUsername("admin")).thenReturn(account);
        when(accountProfileService.findProfile(1, "admin")).thenReturn(profile);
        when(loginAttempts.verifyPassword(account, "password")).thenReturn(AdminLoginAttemptService.Check.ACCEPTED);
        when(totpService.status(1)).thenReturn(new TotpStatusView(false, false));
        when(loginSessions.issue(eq(account), isNull(), anyLong())).thenReturn("isolated-session-token");
        when(clientIpResolver.resolve(httpServletRequest)).thenReturn("162.158.167.71");

        org.token.model.request.auth.AdminLoginRequest request = new org.token.model.request.auth.AdminLoginRequest();
        request.setUsername("admin");
        request.setPassword("password");

        controller.login(request, httpServletRequest);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass((Class) Map.class);
        verify(resultFactory).create(eq(0), captor.capture(), eq("server.success"));
        assertNotNull(captor.getValue().get("accessToken"));
        verify(accountProfileService).updateProfile("admin", 1, profile);
        verify(loginAttempts).succeeded(account);
        assertEquals("162.158.167.71", profile.getLoginIp());
        verify(clientIpResolver).resolve(httpServletRequest);
    }

    @Test
    void temporarilyLockedAccountGetsSpecificMessageAndNoNewSession() {
        Account account=new Account();account.setUid(1);account.setUsername("admin");account.setState(1);
        AccountProfile profile=new AccountProfile();profile.setUid(1);
        when(accountService.findByUsername("admin")).thenReturn(account);
        when(accountProfileService.findProfile(1,"admin")).thenReturn(profile);
        when(loginAttempts.verifyPassword(account,"password")).thenReturn(AdminLoginAttemptService.Check.LOCKED);
        var input=new org.token.model.request.auth.AdminLoginRequest();input.setUsername("admin");input.setPassword("password");
        controller.login(input,httpServletRequest);
        verify(resultFactory).create(-1,null,"server.auth.login.temporarilyLocked");
        verifyNoInteractions(loginSessions,totpService);
    }

    @Test
    void rejectedPasswordNeverReplacesOldSession() {
        Account account=new Account();account.setUid(1);account.setUsername("admin");account.setState(1);
        when(accountService.findByUsername("admin")).thenReturn(account);
        when(accountProfileService.findProfile(1,"admin")).thenReturn(new AccountProfile());
        when(loginAttempts.verifyPassword(account,"wrong")).thenReturn(AdminLoginAttemptService.Check.INVALID);
        var input=new org.token.model.request.auth.AdminLoginRequest();input.setUsername("admin");input.setPassword("wrong");
        controller.login(input,httpServletRequest);
        verify(resultFactory).create(-1,null,"server.login.error");
        verifyNoInteractions(loginSessions,totpService);
    }

    @Test
    void getCodesReturnsFrontendAuthoritiesWithoutInternalRoleMarker() {
        JwtUserPrincipal principal = JwtUserPrincipal.builder()
                .uid(1)
                .username("admin")
                .authorities(new LinkedHashSet<>(List.of("ROLE_1", "6060301", "super", "6060201")))
                .build();

        controller.getCodes(principal);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(resultFactory).create(eq(0), captor.capture(), eq("server.success"));

        assertEquals(List.of("6060201", "6060301", "super"), captor.getValue());
    }
}
