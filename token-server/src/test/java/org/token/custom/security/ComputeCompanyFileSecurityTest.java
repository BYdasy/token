package org.token.custom.security;

import org.token.model.security.JwtUserPrincipal;

import org.token.model.security.ComputeMemberPrincipal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.token.config.EnabledLocaleResolver;
import org.token.config.SecurityConfig;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.custom.security.*;
import org.token.custom.web.RequestTimeZoneFilter;
import org.token.config.compute.ComputeSecurityConfig;
import org.token.controller.admin.ComputeCompanyFileAdminController;
import org.token.controller.user.ComputeCompanyFileUserController;
import org.token.controller.publicapi.ComputeCompanyVideoPublicController;
import org.token.controller.admin.ComputeFormTranslationController;
import org.token.service.compute.ComputeAccountService;
import org.token.service.compute.ComputeCompanyFileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用真实双安全链与方法授权，服务/会话均隔离，不读取真实数据库与存储。 */
@WebMvcTest({ComputeCompanyFileAdminController.class, ComputeCompanyFileUserController.class, ComputeCompanyVideoPublicController.class, ComputeFormTranslationController.class})
@Import({ComputeSecurityConfig.class, SecurityConfig.class, org.token.service.compute.ComputeFormTranslationService.class})
@Tag("regression")
class ComputeCompanyFileSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean ComputeCompanyFileService files;
    @MockitoBean org.token.service.compute.ComputeContentTranslationService translations;
    @MockitoBean org.token.service.compute.ComputeContentService contentService;
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
        when(accounts.authenticate("member-token")).thenReturn(new ComputeMemberPrincipal(7, "member"));
        when(results.success(any())).thenAnswer(call -> new Result(0, call.getArgument(0), "成功"));
        when(results.create(anyInt(), any(), anyString())).thenAnswer(call -> new Result(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        when(results.failure(anyInt(), anyString(), any())).thenAnswer(call -> new Result(call.getArgument(0), null, call.getArgument(1)));
        for (var filter : new org.springframework.web.filter.OncePerRequestFilter[]{adminJwt, adminIp, adminTotp, timezone}) {
            doAnswer(call -> { ((FilterChain) call.getArgument(2)).doFilter(call.getArgument(0), call.getArgument(1)); return null; })
                    .when(filter).doFilter(any(), any(), any());
        }
        doAnswer(call -> { ((HttpServletResponse) call.getArgument(1)).setStatus(401); return null; }).when(entryPoint).commence(any(), any(), any());
        doAnswer(call -> { ((HttpServletResponse) call.getArgument(1)).setStatus(403); return null; }).when(denied).handle(any(), any(), any());
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    void admin(String authority) throws Exception {
        // 与现有安全边界测试一致，在管理员JWT过滤器位置建立身份。
        // 请求前直接写线程上下文会被无状态安全链的上下文加载覆盖。
        doAnswer(call -> {
            var principal = JwtUserPrincipal.builder().uid(9).username("company-test").authorities(Set.of(authority)).build();
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, AuthorityUtils.createAuthorityList(authority)));
            try {
                ((FilterChain) call.getArgument(2)).doFilter(call.getArgument(0), call.getArgument(1));
            } finally {
                SecurityContextHolder.clearContext();
            }
            return null;
        }).when(adminJwt).doFilter(any(), any(), any());
    }

    @Test void listAndPdfRequireRealMemberCredentialsAndNeverAcceptAdministratorBearer() throws Exception {
        for (String path : List.of("/api/compute/user/company-files", "/api/compute/user/company-files/1/pdf", "/api/compute/user/company-files/1/video")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Bearer admin-token")).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(files);
        when(files.userList(anyString())).thenReturn(List.of(Map.of("id", 1, "fileName", "执照.pdf", "size", 20, "url", "/api/compute/user/company-files/1/pdf")));
        mvc.perform(get("/api/compute/user/company-files").header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].fileName").value("执照.pdf"));
        byte[] bytes = "%PDF-1.7\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        when(files.pdf(1, false)).thenReturn(ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(new ByteArrayResource(bytes)));
        mvc.perform(get("/api/compute/user/company-files/1/pdf").header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF)).andExpect(content().bytes(bytes));
    }

    @Test void memberAndUnrelatedAdminPermissionsCannotReachCompanyAdministration() throws Exception {
        mvc.perform(get("/api/compute/admin/company-files").header("Authorization", "Bearer member-token")).andExpect(status().isUnauthorized());
        admin("20100");
        mvc.perform(get("/api/compute/admin/company-files")).andExpect(status().isForbidden());
        admin("20100");
        mvc.perform(get("/api/compute/admin/company-files/1/pdf")).andExpect(status().isForbidden());
        verifyNoInteractions(files);
    }

    @Test void companyReadCannotWriteAndCompanyEditAndSuperCan() throws Exception {
        when(files.adminList(anyMap())).thenReturn(Map.of("records", List.of(), "total", 0));
        when(files.upload(any())).thenReturn(Map.of("id", 1));
        when(files.update(eq(1L), anyMap())).thenReturn(Map.of("id", 1));
        admin("71000");
        mvc.perform(get("/api/compute/admin/company-files")).andExpect(status().isOk());
        admin("71000");
        mvc.perform(multipart("/api/compute/admin/company-files").file(new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[]{1})))
                .andExpect(status().isForbidden());
        admin("71000");
        mvc.perform(put("/api/compute/admin/company-files/1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        for (String permission : List.of("71001", "super")) {
            admin(permission);
            mvc.perform(multipart("/api/compute/admin/company-files").file(new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[]{1})))
                    .andExpect(status().isOk());
            admin(permission);
            mvc.perform(put("/api/compute/admin/company-files/1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk());
        }
        verify(files, times(2)).upload(any());
        verify(files, times(2)).update(eq(1L), anyMap());
    }

    @Test void externalLinkCreationRequiresCompanyWriteLeafAndDoesNotGrantReadLeafMutation() throws Exception {
        when(files.createLink(anyMap())).thenReturn(Map.of("id", 9, "resourceType", "LINK"));
        admin("71000");
        mvc.perform(post("/api/compute/admin/company-files/links").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verify(files, never()).createLink(anyMap());
        admin("71001");
        mvc.perform(post("/api/compute/admin/company-files/links").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.resourceType").value("LINK"));
        verify(files).createLink(anyMap());
    }

    @Test void companyTitleTranslationAndPollingRequireCompanyEditLeaf() throws Exception {
        when(translations.submitForm(eq("COMPANY_FILE"), anyMap(), eq(9))).thenReturn(Map.of("taskId", 12));
        when(translations.formTask("COMPANY_FILE", 12, 9)).thenReturn(Map.of("taskId", 12));
        for (String deniedCode : List.of("71000", "70901", "70101", "70201")) {
            admin(deniedCode);
            mvc.perform(post("/api/compute/admin/translation-forms/COMPANY_FILE").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
            admin(deniedCode);
            mvc.perform(get("/api/compute/admin/translation-forms/COMPANY_FILE/12")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(translations);
        admin("71001");
        mvc.perform(post("/api/compute/admin/translation-forms/COMPANY_FILE").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.taskId").value(12));
        admin("71001");
        mvc.perform(get("/api/compute/admin/translation-forms/COMPANY_FILE/12")).andExpect(status().isOk());
        verify(translations).submitForm(eq("COMPANY_FILE"), anyMap(), eq(9));
        verify(translations).formTask("COMPANY_FILE", 12, 9);
    }

    @Test void homepageVideoReadsArePublicEvenWithExpiredCredentialsButPrivateDocumentsAndWritesAreNot() throws Exception {
        when(files.homepageVideos(anyString())).thenReturn(List.of(Map.of("id", 7, "resourceType", "VIDEO")));
        when(files.video(7, false, true)).thenReturn(ResponseEntity.ok().contentType(MediaType.parseMediaType("video/mp4"))
                .body(new ByteArrayResource(new byte[]{0, 1, 2, 3})));
        mvc.perform(get("/api/compute/public/company-videos").header("Authorization", "Bearer expired"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].resourceType").value("VIDEO"));
        mvc.perform(get("/api/compute/public/company-videos/7/video").header("Authorization", "Bearer expired"))
                .andExpect(status().isOk()).andExpect(content().contentType("video/mp4"));
        mvc.perform(get("/api/compute/public/company-videos/7/video").header("Range", "bytes=1-2"))
                .andExpect(status().isPartialContent()).andExpect(header().string("Content-Range", "bytes 1-2/4"))
                .andExpect(content().bytes(new byte[]{1, 2}));
        mvc.perform(head("/api/compute/public/company-videos/7/video")).andExpect(status().isOk()).andExpect(content().bytes(new byte[0]));
        verify(accounts, never()).authenticate(anyString());
        verify(accounts, never()).recordSessionActivity(anyString());
        mvc.perform(get("/api/compute/public/company-videos/7/pdf")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/compute/public/company-videos").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void videoLinkWritesAndPrivateVideoReadsUseTheExistingExactCompanyPermissions() throws Exception {
        when(files.createVideoLink(anyMap())).thenReturn(Map.of("id", 8, "resourceType", "VIDEO_LINK"));
        when(files.video(8, true, false)).thenReturn(ResponseEntity.ok().contentType(MediaType.parseMediaType("video/webm"))
                .body(new ByteArrayResource(new byte[]{1, 2})));
        admin("71000");
        mvc.perform(post("/api/compute/admin/company-files/video-links").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        admin("20100");
        mvc.perform(get("/api/compute/admin/company-files/8/video")).andExpect(status().isForbidden());
        verify(files, never()).createVideoLink(anyMap());
        verify(files, never()).video(anyLong(), anyBoolean(), anyBoolean());
        admin("71000");
        mvc.perform(get("/api/compute/admin/company-files/8/video")).andExpect(status().isOk());
        for (String permission : List.of("71001", "super")) {
            admin(permission);
            mvc.perform(post("/api/compute/admin/company-files/video-links").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.resourceType").value("VIDEO_LINK"));
        }
        verify(files, times(2)).createVideoLink(anyMap());
    }

    @Test void rangeRequestsCannotBypassMemberAuthentication() throws Exception {
        String url = "/api/compute/user/company-files/7/video";
        mvc.perform(get(url).header("Range", "bytes=1-2")).andExpect(status().isUnauthorized());
        mvc.perform(get(url).header("Range", "bytes=1-2").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(files);
        when(files.video(7, false, false)).thenReturn(ResponseEntity.ok().contentType(MediaType.parseMediaType("video/mp4"))
                .body(new ByteArrayResource(new byte[]{0, 1, 2, 3})));
        mvc.perform(get(url).header("Range", "bytes=1-2").header("Authorization", "Bearer member-token"))
                .andExpect(status().isPartialContent()).andExpect(header().string("Content-Range", "bytes 1-2/4"))
                .andExpect(content().bytes(new byte[]{1, 2}));
        verify(files).video(7, false, false);
    }
}
