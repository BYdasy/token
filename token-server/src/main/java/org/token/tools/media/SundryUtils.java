package org.token.tools.media;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * 认证接口与文件接口共用的基础工具。
 */
@Component
public class SundryUtils {

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("win");

    private static volatile String imagePath = "./data/images";
    private static volatile String filePath = "./data/files";
    private static volatile float webpQuality = 0.8f;

    public SundryUtils(
            @Value("${exp.time-zone:Asia/Shanghai}") String timeZone,
            @Value("${exp.storage.image-path:}") String configuredImagePath,
            @Value("${exp.storage.file-path:}") String configuredFilePath,
            @Value("${exp.storage.windows-image-path:C:/Users/73915/Desktop/in/}") String windowsImagePath,
            @Value("${exp.storage.windows-file-path:C:/Users/73915/Desktop/in/}") String windowsFilePath,
            @Value("${exp.storage.linux-image-path:/www/wwwroot/data/img/token/}") String linuxImagePath,
            @Value("${exp.storage.linux-file-path:/www/wwwroot/data/file/token/}") String linuxFilePath,
            @Value("${exp.image.webp-quality:80}") Integer configuredWebpQuality
    ) {
        imagePath = resolveStoragePath(
                IS_WINDOWS,
                "image",
                configuredImagePath,
                configuredFilePath,
                windowsImagePath,
                windowsFilePath,
                linuxImagePath,
                linuxFilePath
        );
        filePath = resolveStoragePath(
                IS_WINDOWS,
                "file",
                configuredImagePath,
                configuredFilePath,
                windowsImagePath,
                windowsFilePath,
                linuxImagePath,
                linuxFilePath
        );
        int quality = configuredWebpQuality == null ? 80 : configuredWebpQuality;
        webpQuality = Math.max(1, Math.min(quality, 100)) / 100.0f;
    }

    public static String getToken(HttpServletRequest request) {
        if (request == null) {
            return "";
        }

        String authorization = request.getHeader("Authorization");
        if (StringUtils.hasText(authorization)) {
            String token = authorization.trim();
            if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
                token = token.substring(7).trim();
            }
            if (StringUtils.hasText(token)) {
                return token;
            }
        }

        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("Authorization".equalsIgnoreCase(cookie.getName())) {
                    return StringUtils.hasText(cookie.getValue()) ? cookie.getValue().trim() : "";
                }
            }
        }
        return "";
    }

    public static String getPath(String type, String version) {
        String basePath = switch (type == null ? "" : type.trim().toLowerCase()) {
            case "img", "image" -> imagePath;
            case "file", "video" -> filePath;
            default -> throw new IllegalArgumentException("未知的存储类型：" + type);
        };

        Path path = Path.of(basePath);
        if (StringUtils.hasText(version)) {
            path = path.resolve(version.trim());
        }
        return path.normalize().toString();
    }

    public static LocalDateTime getTime() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * 把普通静态图片编码为 WebP。GIF 动图由上传入口直接保留，不调用本方法。
     */
    public static void convertToWebp(BufferedImage image, Path outputPath) throws IOException {
        if (image == null || outputPath == null) {
            throw new IOException("图片或输出路径不能为空");
        }
        ImageWriter writer = null;
        try {
            // 固定当前依赖的写入器，避免 ImageIO 插件顺序决定是否选中旧版 JNI 实现。
            writer = new com.luciad.imageio.webp.WebPImageWriterSpi().createWriterInstance();
            ImageWriteParam parameter = writer.getDefaultWriteParam();
            if (parameter.canWriteCompressed()) {
                parameter.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                if (parameter.getCompressionTypes() != null && parameter.getCompressionTypes().length > 0) {
                    parameter.setCompressionType(parameter.getCompressionTypes()[0]);
                }
                parameter.setCompressionQuality(webpQuality);
            }
            Path parent = outputPath.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                java.nio.file.Files.createDirectories(parent);
            }
            try (FileImageOutputStream output = new FileImageOutputStream(outputPath.toFile())) {
                writer.setOutput(output);
                writer.write(null, new IIOImage(image, null, null), parameter);
            }
            if (java.nio.file.Files.size(outputPath) == 0) {
                throw new IOException("WebP 编码器未生成图片");
            }
        } catch (LinkageError | IllegalStateException unavailable) {
            // JNI 的架构/系统库/临时目录错误必须转成受控失败，使上传入口清理临时文件。
            throw new IOException("WebP 编码器不可用（os.name=" + System.getProperty("os.name", "unknown")
                    + ", os.arch=" + System.getProperty("os.arch", "unknown")
                    + "）；请核对 JAR/lib 版本、原生库 CPU 架构、Linux libc 及原生库临时目录权限", unavailable);
        } finally {
            if (writer != null) writer.dispose();
        }
    }

    /** WebP 的尺寸校验及解码固定使用纯 Java 插件，不触发 JNI，调用方仍须在解码前限制像素数。 */
    public static ImageReader imageReader(ImageInputStream input) throws IOException {
        if (input == null) return null;
        var webp = new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi();
        if (webp.canDecodeInput(input)) return webp.createReaderInstance();
        Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
        return readers.hasNext() ? readers.next() : null;
    }

    static String resolveStoragePath(
            boolean windows,
            String type,
            String configuredImagePath,
            String configuredFilePath,
            String windowsImagePath,
            String windowsFilePath,
            String linuxImagePath,
            String linuxFilePath
    ) {
        String normalizedType = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        String override;
        String platformPath;
        String fallback;
        switch (normalizedType) {
            case "img", "image" -> {
                override = configuredImagePath;
                platformPath = windows ? windowsImagePath : linuxImagePath;
                fallback = "./data/images";
            }
            case "file", "video" -> {
                override = configuredFilePath;
                platformPath = windows ? windowsFilePath : linuxFilePath;
                fallback = "./data/files";
            }
            default -> throw new IllegalArgumentException("未知的存储类型：" + type);
        }

        if (StringUtils.hasText(override)) {
            return override.trim();
        }
        if (StringUtils.hasText(platformPath)) {
            return platformPath.trim();
        }
        return fallback;
    }
}
