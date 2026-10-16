package org.token.service.compute;

import jakarta.servlet.http.HttpServletRequest;
import org.token.model.response.SystemSettingsView;
import org.token.exception.BusinessException;
import org.token.custom.security.ClientIpResolver;
import org.token.service.SystemSettingService;
import org.token.service.compute.SharePreviewRules;
import org.token.tools.media.SundryUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
public class SharePreviewService {
    public static final String SHARE_PATH = "/api/compute/public/share";
    public static final String IMAGE_PATH = "/api/compute/public/share-image";
    private final SystemSettingService settings;
    private final ClientIpResolver clientIps;
    private volatile ImageCache imageCache;
    private record ImageCache(String key, long expiresAt, byte[] bytes) { }

    public SharePreviewService(SystemSettingService settings, ClientIpResolver clientIps) {
        this.settings = settings; this.clientIps = clientIps;
    }

    public String html(HttpServletRequest request, String destination, String nonce) {
        // 仅可信Nginx的内部爬虫转发可提供原页面路径；普通API片段会清除该头。
        String forwardedTarget = clientIps.isTrustedProxyRequest(request) ? request.getHeader("X-Token-Share-Target") : null;
        boolean internalPreview = destination == null && forwardedTarget != null && !forwardedTarget.isBlank();
        if (internalPreview) destination = forwardedTarget;
        String target = SharePreviewRules.target(destination);
        String origin = origin(request);
        SystemSettingsView current = settings.getPublicSettings();
        String title = blank(current.getUserShareTitle()) ? current.getUserSiteName() : current.getUserShareTitle();
        String description = current.getUserShareDescription();
        String image = configuredImage(current);
        String imageUrl = image.startsWith(SharePreviewRules.LOCAL_IMAGE_PREFIX)
                ? origin + IMAGE_PATH + "?v=" + Integer.toUnsignedString(image.hashCode(), 36) : image;
        StringBuilder html = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        html.append("<title>").append(escape(title)).append("</title>");
        meta(html, "property", "og:type", "website");
        meta(html, "property", "og:title", title);
        meta(html, "property", "og:description", description);
        meta(html, "property", "og:site_name", current.getUserSiteName());
        meta(html, "property", "og:url", origin + target);
        meta(html, "name", "description", description);
        meta(html, "name", "twitter:card", imageUrl.isEmpty() ? "summary" : "summary_large_image");
        meta(html, "name", "twitter:title", title);
        meta(html, "name", "twitter:description", description);
        if (!imageUrl.isEmpty()) {
            meta(html, "property", "og:image", imageUrl);
            meta(html, "property", "og:image:alt", title);
            meta(html, "name", "twitter:image", imageUrl);
            if (image.startsWith(SharePreviewRules.LOCAL_IMAGE_PREFIX)) meta(html, "property", "og:image:type", "image/jpeg");
        }
        boolean inspect = "1".equals(request.getParameter("preview"));
        if (inspect) {
            html.append("<style nonce=\"").append(escape(nonce)).append("\">body{margin:0;padding:32px;background:#f2f5f9;font:16px/1.6 system-ui,sans-serif;color:#182438}article{max-width:640px;margin:auto;overflow:hidden;background:#fff;border-radius:16px;box-shadow:0 8px 30px #10204018}img{display:block;width:100%;max-height:360px;object-fit:contain;background:#edf0f4}section{padding:24px}h1{margin:0 0 12px;font-size:24px;overflow-wrap:anywhere}p{white-space:pre-wrap;overflow-wrap:anywhere}a{color:#147ef5;overflow-wrap:anywhere}</style>");
        }
        html.append("</head><body><article>");
        if (inspect && !imageUrl.isEmpty()) html.append("<img src=\"").append(escape(imageUrl)).append("\" alt=\"").append(escape(title)).append("\">");
        html.append("<section><h1>").append(escape(title)).append("</h1><p>").append(escape(description)).append("</p><a id=\"share-entry\" href=\"").append(escape(target)).append("\">")
                .append(escape(origin + target)).append("</a></section></article>");
        // 内部分流时浏览器地址本来就是目的地，不能再次跳回自身；旧公开API链接仍引导真人进入SPA。
        if (!inspect && !internalPreview) html.append("<script nonce=\"").append(escape(nonce)).append("\">location.replace(document.getElementById('share-entry').getAttribute('href'));</script>");
        html.append("</body></html>");
        return html.toString();
    }

    /** 只读取管理员选择的本地素材，转换为JPEG供不执行JS的分享爬虫读取。 */
    public synchronized byte[] image() {
        String image = configuredImage(settings.getPublicSettings());
        if (!image.startsWith(SharePreviewRules.LOCAL_IMAGE_PREFIX)) return null;
        try {
            Path base = Path.of(SundryUtils.getPath("img", null)).toRealPath();
            Path file = base.resolve(image.substring(SharePreviewRules.LOCAL_IMAGE_PREFIX.length())).toRealPath();
            if (!file.startsWith(base) || !Files.isRegularFile(file) || Files.size(file) > 15L * 1024 * 1024) return null;
            String key = image + ":" + Files.getLastModifiedTime(file).toMillis() + ":" + Files.size(file);
            ImageCache cached = imageCache;
            if (cached != null && cached.key().equals(key) && cached.expiresAt() > System.currentTimeMillis()) return cached.bytes();
            try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
                if (input == null) return null;
                ImageReader reader = SundryUtils.imageReader(input);
                if (reader == null) return null;
                try {
                    reader.setInput(input);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || width > 10_000 || height > 10_000 || (long) width * height > 25_000_000) return null;
                    BufferedImage source = reader.read(0);
                    double scale = Math.min(1d, 1200d / Math.max(width, height));
                    BufferedImage jpeg = new BufferedImage(Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)), BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = jpeg.createGraphics();
                    try {
                        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, jpeg.getWidth(), jpeg.getHeight());
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        graphics.drawImage(source, 0, 0, jpeg.getWidth(), jpeg.getHeight(), null);
                    } finally { graphics.dispose(); }
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    if (!ImageIO.write(jpeg, "jpeg", output)) return null;
                    byte[] bytes = output.toByteArray();
                    imageCache = new ImageCache(key, System.currentTimeMillis() + 300_000, bytes);
                    return bytes;
                } finally { reader.dispose(); }
            }
        } catch (Exception unavailable) { return null; }
    }

    static String configuredImage(SystemSettingsView current) {
        try { return SharePreviewRules.image(current.getUserShareImage()); }
        catch (BusinessException invalidOldValue) { return ""; }
    }

    String origin(HttpServletRequest request) {
        String scheme = request.getScheme();
        if (clientIps.isTrustedProxyRequest(request)) {
            String forwarded = request.getHeader("X-Forwarded-Proto");
            if (List.of("https", "http").contains(forwarded == null ? "" : forwarded)) scheme = forwarded;
        }
        try {
            String host = request.getHeader("Host");
            if (host == null || host.isBlank()) throw new IllegalArgumentException();
            URI uri = URI.create(scheme + "://" + host);
            if (!List.of("http", "https").contains(scheme) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || !uri.getRawPath().isEmpty()
                    || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
            return uri.toASCIIString();
        } catch (IllegalArgumentException invalid) { throw new BusinessException("server.settings.shareTargetInvalid"); }
    }

    private static boolean blank(String text) { return text == null || text.isBlank(); }
    private static String escape(String text) { return HtmlUtils.htmlEscape(text == null ? "" : text, "UTF-8"); }
    private static void meta(StringBuilder html, String attribute, String name, String value) {
        html.append("<meta ").append(attribute).append("=\"").append(name).append("\" content=\"").append(escape(value)).append("\">");
    }
}
