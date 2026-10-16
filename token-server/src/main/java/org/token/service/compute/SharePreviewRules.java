package org.token.service.compute;

import org.token.exception.BusinessException;
import java.net.URI;
import java.util.Locale;

/** 分享公开入口仅接收站内目的地和已配置的安全图片，不提供任意跳转/代理下载。 */
public final class SharePreviewRules {
    private SharePreviewRules() { }
    public static final String LOCAL_IMAGE_PREFIX = "/api/material/image/";

    public static String text(String value, int limit) {
        String result = value == null ? "" : value.trim();
        if (result.length() > limit || result.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r'))
            throw new BusinessException("server.settings.shareInvalid");
        return result;
    }

    /** 仅用于生成公开分享链接，不供服务端下载或改变业务 API 来源。 */
    public static String siteUrl(String value) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty()) return "";
        if (result.length() > 2048 || result.contains("\\") || result.chars().anyMatch(Character::isISOControl))
            throw new BusinessException("server.settings.shareInvalid");
        try {
            URI uri = URI.create(result);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
            target(uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());
            return uri.toASCIIString();
        } catch (IllegalArgumentException invalid) { throw new BusinessException("server.settings.shareInvalid"); }
    }

    public static String image(String value) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty()) return result;
        if (result.length() > 2048 || result.contains("\\") || result.chars().anyMatch(Character::isISOControl))
            throw new BusinessException("server.settings.shareInvalid");
        if (result.startsWith(LOCAL_IMAGE_PREFIX)
                && result.substring(LOCAL_IMAGE_PREFIX.length()).matches("[A-Za-z0-9_-]+\\.(?i:jpg|jpeg|png|gif|webp)")) return result;
        try {
            URI uri = URI.create(result);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) throw new IllegalArgumentException();
            return result;
        } catch (IllegalArgumentException invalid) { throw new BusinessException("server.settings.shareInvalid"); }
    }

    public static String target(String value) {
        if (value == null || value.isBlank()) return "/";
        if (value.length() > 2048 || !value.startsWith("/") || value.startsWith("//") || value.contains("\\")
                || value.chars().anyMatch(Character::isISOControl)) throw new BusinessException("server.settings.shareTargetInvalid");
        try {
            URI uri = URI.create(value);
            String path = uri.getRawPath();
            // 目的地路径不使用转义，查询参数仍保留合法邀请码等；拒绝编码斜线与循环分享入口。
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null
                    || path == null || path.contains("%") || !uri.normalize().getRawPath().equals(path)
                    || path.startsWith("/api/") || path.equals("/api") || path.startsWith("/token/") || path.equals("/token"))
                throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException invalid) { throw new BusinessException("server.settings.shareTargetInvalid"); }
    }
}
