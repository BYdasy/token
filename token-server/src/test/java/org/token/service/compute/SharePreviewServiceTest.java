package org.token.service.compute;

import org.token.model.response.SystemSettingsView;
import org.token.exception.BusinessException;
import org.token.custom.security.ClientIpResolver;
import org.token.service.SystemSettingService;
import org.token.service.compute.SharePreviewRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.token.tools.media.SundryUtils;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SharePreviewServiceTest {
    @TempDir Path temporary;
    private final SystemSettingService settings = mock(SystemSettingService.class);
    private final SharePreviewService service = new SharePreviewService(settings, new ClientIpResolver("127.0.0.1"));
    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", SharePreviewService.SHARE_PATH);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "members.example:8443");
        request.addHeader("X-Forwarded-Proto", "https");
        return request;
    }
    @Test void rendersMetadataWithoutJavascriptAndEscapesConfiguredContent() {
        when(settings.getPublicSettings()).thenReturn(SystemSettingsView.builder().userSiteName("Token")
                .userShareTitle("Prize \"</title><script>bad()</script>")
                .userShareDescription("A & B").userShareImage("/api/material/image/mat_test.webp").build());
        String html = service.html(request(), "/register?invite=AB12CD&lang=en", "test-nonce");
        assertTrue(html.contains("property=\"og:title\""));
        assertTrue(html.contains("A &amp; B"));
        assertTrue(html.contains("https://members.example:8443/api/compute/public/share-image?v="));
        assertTrue(html.contains("href=\"/register?invite=AB12CD&amp;lang=en\""));
        assertFalse(html.contains("<script>bad()"));
        assertTrue(html.contains("nonce=\"test-nonce\""));
    }
    @Test void rejectsExternalEncodedAndRecursiveDestinations() {
        for (String destination : List.of("https://evil.test", "//evil.test", "/\\evil.test", "/%2fevil.test", "/a/../register", "/api/compute/public/share", "/token/"))
            assertThrows(BusinessException.class, () -> SharePreviewRules.target(destination), destination);
        assertEquals("/register?invite=A%26B", SharePreviewRules.target("/register?invite=A%26B"));
    }
    @Test void rejectsUnsafeImagesWithoutFetchingAnyExternalResource() {
        for (String image : List.of("javascript:alert(1)", "//evil.test/x.jpg", "/api/material/image/../secrets", "https://user:pass@site.test/image.png", "https://site.test:8443/i.png", "/private/secret.png"))
            assertThrows(BusinessException.class, () -> SharePreviewRules.image(image), image);
        assertEquals("https://cdn.example/image.jpg", SharePreviewRules.image("https://cdn.example/image.jpg"));
    }
    @Test void ignoresUntrustedSchemeAndForwardedHost() {
        var request = request(); request.setRemoteAddr("203.0.113.9");
        request.addHeader("X-Forwarded-Host", "evil.test");
        assertEquals("http://members.example:8443", service.origin(request));
        request.removeHeader("Host"); request.addHeader("Host", "site.test\"/><script>");
        assertThrows(BusinessException.class, () -> service.origin(request));
    }
    @Test void publishesOnlyChosenLocalImageAsBoundedJpeg() throws Exception {
        String oldImage = SundryUtils.getPath("img", null), oldFile = SundryUtils.getPath("file", null);
        new SundryUtils("UTC", temporary.toString(), oldFile, oldImage, oldFile, oldImage, oldFile, 80);
        try {
            ImageIO.write(new BufferedImage(1800, 900, BufferedImage.TYPE_INT_ARGB), "png", temporary.resolve("mat_preview.png").toFile());
            when(settings.getPublicSettings()).thenReturn(SystemSettingsView.builder().userShareImage("/api/material/image/mat_preview.png").build());
            byte[] jpeg = service.image(); assertNotNull(jpeg);
            BufferedImage actual = ImageIO.read(new ByteArrayInputStream(jpeg));
            assertEquals(1200, actual.getWidth()); assertEquals(600, actual.getHeight());
            when(settings.getPublicSettings()).thenReturn(SystemSettingsView.builder().userShareImage("https://cdn.example/a.png").build());
            assertNull(service.image());
        } finally { new SundryUtils("UTC", oldImage, oldFile, oldImage, oldFile, oldImage, oldFile, 80); }
    }
    @Test void existingWebpShareImageDecodesWithoutNativeReaderAndPublishesJpeg() throws Exception {
        String oldImage = SundryUtils.getPath("img", null), oldFile = SundryUtils.getPath("file", null);
        new SundryUtils("UTC", temporary.toString(), oldFile, oldImage, oldFile, oldImage, oldFile, 80);
        try {
            SundryUtils.convertToWebp(new BufferedImage(16, 8, BufferedImage.TYPE_INT_ARGB), temporary.resolve("mat_preview.webp"));
            when(settings.getPublicSettings()).thenReturn(SystemSettingsView.builder().userShareImage("/api/material/image/mat_preview.webp").build());
            byte[] bytes = service.image();
            assertNotNull(bytes);
            assertEquals(0xff, bytes[0] & 0xff);
            assertEquals(0xd8, bytes[1] & 0xff);
            BufferedImage actual = ImageIO.read(new ByteArrayInputStream(bytes));
            assertEquals(16, actual.getWidth());
            assertEquals(8, actual.getHeight());
            assertEquals(0xffffff, actual.getRGB(0, 0) & 0xffffff);
        } finally { new SundryUtils("UTC", oldImage, oldFile, oldImage, oldFile, oldImage, oldFile, 80); }
    }
    @Test void internalCrawlerForwardingPreservesOriginalPageOnlyFromTrustedProxy() {
        when(settings.getPublicSettings()).thenReturn(SystemSettingsView.builder().userSiteName("Token").build());
        var request = request(); request.addHeader("X-Token-Share-Target", "/register?invite=ABC123");
        assertTrue(service.html(request, null, "nonce").contains("href=\"/register?invite=ABC123\""));
        request.setRemoteAddr("203.0.113.9");
        assertFalse(service.html(request, null, "nonce").contains("invite=ABC123"));
    }

    @Test void ordinaryRootCrawlerReceivesMetadataWithoutRedirectingBackToItself() {
        when(settings.getPublicSettings()).thenReturn(SystemSettingsView.builder().userSiteName("Members")
                .userShareTitle("Saved title").userShareDescription("Saved description").build());
        var request = request();
        request.addHeader("X-Token-Share-Target", "/?invite=A%2BB&lang=en&v=current");
        String html = service.html(request, null, "nonce");
        assertTrue(html.contains("property=\"og:url\" content=\"https://members.example:8443/?invite=A%2BB&amp;lang=en&amp;v=current\""));
        assertTrue(html.contains("property=\"og:title\" content=\"Saved title\""));
        assertTrue(html.contains("property=\"og:description\" content=\"Saved description\""));
        assertTrue(html.contains("href=\"/?invite=A%2BB&amp;lang=en&amp;v=current\""));
        assertFalse(html.contains("<script"));
        assertFalse(html.contains("location.replace"));
    }

    @Test void legacyApiEntryStillNavigatesHumansAndCannotBeChangedByForwardedOrQueryHeaders() {
        when(settings.getPublicSettings()).thenReturn(SystemSettingsView.builder().userSiteName("Members").build());
        var request = request();
        request.addHeader("X-Token-Share-Target", "/?invite=wrong");
        String html = service.html(request, "/register?invite=RIGHT", "nonce");
        assertTrue(html.contains("href=\"/register?invite=RIGHT\""));
        assertTrue(html.contains("location.replace"));
        assertFalse(html.contains("invite=wrong"));
        request.setRemoteAddr("203.0.113.9");
        html = service.html(request, null, "nonce");
        assertTrue(html.contains("location.replace"));
        assertFalse(html.contains("invite=wrong"));
    }

    @Test void internallyForwardedRootUsesEachRequestedDomainWithoutTrustingForwardedHost() {
        when(settings.getPublicSettings()).thenReturn(SystemSettingsView.builder().userSiteName("Members").build());
        for (String host : List.of("first.example", "second.example:8443", "[::1]:5578")) {
            var request = request();
            request.removeHeader("Host");
            request.addHeader("Host", host);
            request.addHeader("X-Forwarded-Host", "wrong.example");
            request.addHeader("X-Token-Share-Target", "/");
            String html = service.html(request, null, "nonce");
            assertTrue(html.contains("property=\"og:url\" content=\"https://" + host + "/\""));
            assertFalse(html.contains("wrong.example"));
            assertFalse(html.contains("location.replace"));
        }
    }

    @Test void savedPreviewShowsConfiguredCardWithoutNavigatingAway() {
        when(settings.getPublicSettings()).thenReturn(SystemSettingsView.builder().siteName("ADMIN ONLY")
                .userSiteName("Members").userShareTitle("User title").userShareDescription("User description")
                .userShareImage("https://cdn.example/card.png").build());
        var request = request(); request.setParameter("preview", "1");
        String html = service.html(request, "/", "preview-nonce");
        assertTrue(html.contains("<h1>User title</h1>"));
        assertTrue(html.contains("<img src=\"https://cdn.example/card.png\""));
        assertTrue(html.contains("style nonce=\"preview-nonce\""));
        assertFalse(html.contains("location.replace"));
        assertFalse(html.contains("ADMIN ONLY"));
    }

    @Test void ordinaryCrawlerRequestUsesUserSettingsAndKeepsEncodedInviteQuery() {
        when(settings.getPublicSettings()).thenReturn(SystemSettingsView.builder().siteName("ADMIN ONLY")
                .userSiteName("Members").userShareDescription("Description from settings").build());
        var request = request();
        request.addHeader("User-Agent", "TelegramBot (like TwitterBot)");
        request.addHeader("X-Token-Share-Target", "/register?invite=A%2BB&lang=en");
        String html = service.html(request, null, "nonce");
        assertTrue(html.contains("property=\"og:title\" content=\"Members\""));
        assertTrue(html.contains("https://members.example:8443/register?invite=A%2BB&amp;lang=en"));
        assertFalse(html.contains("ADMIN ONLY"));
    }

    @Test void userEntryUrlAllowsConfiguredWebsitesButRejectsAdminApiAndUnsafeTargets() {
        assertEquals("", SharePreviewRules.siteUrl(" "));
        assertEquals("https://members.example/", SharePreviewRules.siteUrl("https://members.example/"));
        assertEquals("http://localhost:5578/", SharePreviewRules.siteUrl("http://localhost:5578/"));
        for (String value : List.of("javascript:alert(1)", "//evil.test", "https://user:pass@members.test/", "https://members.test/token/", "https://members.test/api/compute", "https://members.test/?ticket=secret", "https://members.test/#token", "https://members.test/a/../token/")) {
            assertThrows(BusinessException.class, () -> SharePreviewRules.siteUrl(value), value);
        }
    }
}
