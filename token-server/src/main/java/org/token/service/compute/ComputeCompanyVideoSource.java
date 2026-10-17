package org.token.service.compute;

import org.token.exception.BusinessException;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** 只解析地址，不请求远程服务器；嵌入地址从精确白名单和视频ID重新生成。 */
final class ComputeCompanyVideoSource {
    record Source(String url, String playbackType, String embedUrl, String mimeType) {}
    private static final Set<String> YOUTUBE = Set.of("youtube.com", "www.youtube.com", "m.youtube.com", "youtube-nocookie.com", "www.youtube-nocookie.com");

    static Source parse(Object input) {
        try {
            String value = ComputeCompanyFileService.externalUrl(input);
            URI uri = URI.create(value);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            // 视频不得引向本机、局域网字面地址或非标准端口；服务端始终不下载外链。
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !host.contains(".") || host.endsWith(".localhost") || host.endsWith(".local")
                    || host.contains(":") || host.matches("[0-9.]+")) throw new IllegalArgumentException();
            String path = uri.getPath();
            if (YOUTUBE.contains(host) || "youtu.be".equals(host) || "www.youtu.be".equals(host)) {
                String id = null;
                if (host.endsWith("youtu.be") && path.matches("/[A-Za-z0-9_-]{11}")) id = path.substring(1);
                else if (path.matches("/(embed|shorts|live)/[A-Za-z0-9_-]{11}")) id = path.substring(path.lastIndexOf('/') + 1);
                else if ("/watch".equals(path)) id = query(uri, "v");
                if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) throw new IllegalArgumentException();
                return new Source("https://www.youtube.com/watch?v=" + id, "YOUTUBE", "https://www.youtube-nocookie.com/embed/" + id, null);
            }
            if (Set.of("vimeo.com", "www.vimeo.com", "player.vimeo.com").contains(host)) {
                if (!path.matches("/(video/)?[0-9]{1,12}")) throw new IllegalArgumentException();
                String id = path.substring(path.lastIndexOf('/') + 1);
                return new Source("https://vimeo.com/" + id, "VIMEO", "https://player.vimeo.com/video/" + id, null);
            }
            // 浏览器URL.pathname保留百分号转义；与前端使用同一后缀规则，避免保存后无法播放。
            String lower = uri.getRawPath().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".mp4") || lower.endsWith(".webm")) {
                return new Source(value, "DIRECT", null, lower.endsWith(".mp4") ? "video/mp4" : "video/webm");
            }
            throw new IllegalArgumentException();
        } catch (IllegalArgumentException | BusinessException failure) {
            throw new BusinessException("server.compute.companyFile.invalidVideoLink");
        }
    }

    private static String query(URI uri, String key) {
        if (uri.getRawQuery() == null) return null;
        String result = null;
        for (String part : uri.getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(key)) {
                if (result != null || pair.length != 2) throw new IllegalArgumentException();
                result = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return result;
    }
}
