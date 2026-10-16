package org.token.custom.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.token.config.EnabledLocaleResolver;
import org.token.config.SecurityConfig;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.custom.security.*;
import org.token.custom.web.RequestTimeZoneFilter;
import org.token.config.compute.ComputeSecurityConfig;
import org.token.controller.publicapi.SharePreviewController;
import org.token.service.compute.ComputeAccountService;
import org.token.service.compute.SharePreviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实双安全链，隔离会话和业务服务；不连接数据库或外部站点。 */
@WebMvcTest(SharePreviewController.class)
@Import({ComputeSecurityConfig.class, SecurityConfig.class})
class SharePreviewSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean SharePreviewService previews;
    @MockitoBean ComputeAccountService accounts;
    @MockitoBean ResultFactory results;
    @MockitoBean org.token.custom.LoggingAspect logging;
    @MockitoBean EnabledLocaleResolver locales;
    @MockitoBean ClientIpResolver ipResolver;
    @MockitoBean JwtAuthenticationFilter adminJwt;
    @MockitoBean IpWhitelistFilter adminIp;
    @MockitoBean TotpEnforcementFilter adminTotp;
    @MockitoBean RequestTimeZoneFilter timezone;
    @MockitoBean JwtAuthenticationEntryPoint entryPoint;
    @MockitoBean JwtAccessDeniedHandler denied;

    @BeforeEach void setup() throws Exception {
        when(results.failure(anyInt(), anyString(), any())).thenAnswer(call -> new Result(call.getArgument(0), null, call.getArgument(1)));
        for (var filter : new org.springframework.web.filter.OncePerRequestFilter[]{adminJwt, adminIp, adminTotp, timezone}) {
            doAnswer(call -> { ((FilterChain) call.getArgument(2)).doFilter(call.getArgument(0), call.getArgument(1)); return null; })
                    .when(filter).doFilter(any(), any(), any());
        }
        doAnswer(call -> { ((HttpServletResponse) call.getArgument(1)).setStatus(401); return null; }).when(entryPoint).commence(any(), any(), any());
        doAnswer(call -> { ((HttpServletResponse) call.getArgument(1)).setStatus(403); return null; }).when(denied).handle(any(), any(), any());
        when(previews.html(any(), nullable(String.class), anyString())).thenReturn("<!doctype html><meta property=\"og:title\" content=\"Token\">");
        when(previews.image()).thenReturn(new byte[]{1,2,3});
    }

    @Test void crawlersReadExactPublicHtmlAndImageWithNoAdminWhitelistAccess() throws Exception {
        mvc.perform(get(SharePreviewService.SHARE_PATH)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(result.getResponse().getHeaders("Vary")
                        .stream().anyMatch(value -> value.contains("User-Agent"))))
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("X-Token-Share-Preview", "1"));
        mvc.perform(head(SharePreviewService.SHARE_PATH)).andExpect(status().isOk());
        mvc.perform(get(SharePreviewService.IMAGE_PATH)).andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"));
        mvc.perform(head(SharePreviewService.IMAGE_PATH)).andExpect(status().isOk());
        verifyNoInteractions(accounts, adminIp);
    }

    @Test void staleBearerCannotBlockPublicSharingOrRenewUserActivity() throws Exception {
        mvc.perform(get(SharePreviewService.SHARE_PATH).header("Authorization", "Bearer expired"))
                .andExpect(status().isOk());
        mvc.perform(get(SharePreviewService.IMAGE_PATH).header("Authorization", "Bearer expired"))
                .andExpect(status().isOk());
        verifyNoInteractions(accounts);
    }

    @Test void sharingDoesNotOpenPostsPrivatePagesOrOtherPublicPaths() throws Exception {
        mvc.perform(post(SharePreviewService.SHARE_PATH)).andExpect(status().isUnauthorized());
        mvc.perform(post(SharePreviewService.IMAGE_PATH)).andExpect(status().isUnauthorized());
        for (String path : List.of("/api/compute/public/share/other", "/api/compute/public/secrets", "/api/compute/user/me", "/api/system/settings"))
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(previews);
    }
}
