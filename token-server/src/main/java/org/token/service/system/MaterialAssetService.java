package org.token.service.system;

import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.token.custom.LoggingAspect;
import org.token.model.response.OperationOutcome;
import org.token.exception.BusinessException;
import org.token.model.entity.MaterialAsset;
import org.token.model.request.system.MaterialUpdateRequest;
import org.token.model.security.JwtUserPrincipal;
import org.token.tools.media.SundryUtils;

import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service

@RequiredArgsConstructor
public class MaterialAssetService {
    private static final Set<String> IMAGE_TYPES = Set.of("jpg", "jpeg", "png", "gif", "webp");
    private static final Set<String> VIDEO_TYPES = Set.of("mp4", "webm", "mov");
    private static final long MAX_IMAGE_SIZE = 15L * 1024 * 1024;
    private static final long MAX_VIDEO_SIZE = 50L * 1024 * 1024;
    static final int MAX_IMAGE_DIMENSION = 10_000;
    static final long MAX_IMAGE_PIXELS = 25_000_000L;

    private final org.token.repository.system.MaterialAssetRepository repository;

    public OperationOutcome page(
             Map<String, Object> body,
             JwtUserPrincipal principal
    ) {
        return OperationOutcome.success(repository.page(body, canManageMaterials(principal)));
    }

    /** 所有素材入口共用既有分类；只返回名称，不暴露其他素材信息。 */

    public OperationOutcome categories( JwtUserPrincipal principal) {
        return OperationOutcome.success(repository.categories(canManageMaterials(principal)));
    }

    public OperationOutcome upload(
             MultipartFile file,
             String name,
             String category,
             String tags,
             String remark,
             JwtUserPrincipal principal
    ) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("server.material.file.required");
        }
        String originalName = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "material";
        String extension = extension(originalName);
        boolean image = IMAGE_TYPES.contains(extension);
        boolean video = VIDEO_TYPES.contains(extension);
        if (!image && !video) {
            throw new BusinessException("server.material.file.type.invalid");
        }
        if (video && !canManageMaterials(principal)) {
            throw new BusinessException("server.material.avatar.imageOnly");
        }
        long sizeLimit = image ? MAX_IMAGE_SIZE : MAX_VIDEO_SIZE;
        if (file.getSize() > sizeLimit) {
            throw new BusinessException("server.material.file.tooLarge");
        }
        boolean convertToWebp = shouldConvertToWebp(extension);
        String storedExtension = convertToWebp ? "webp" : extension;
        String storedName = "mat_" + UUID.randomUUID().toString().replace("-", "") + "." + storedExtension;
        Path base = Path.of(SundryUtils.getPath(image ? "img" : "file", null)).toAbsolutePath().normalize();
        Files.createDirectories(base);
        Path target = base.resolve(storedName).normalize();
        if (!target.startsWith(base)) {
            throw new BusinessException("server.material.file.path.invalid");
        }
        Path temporary = base.resolve(storedName + ".uploading").normalize();
        boolean persisted = false;
        try {
            ImageDimensions dimensions = null;
            if (convertToWebp) {
                try (InputStream input = file.getInputStream()) {
                    dimensions = inspectImage(input);
                }
                if (!Set.of("jpeg", "png").contains(dimensions.format())) {
                    throw new BusinessException("server.material.image.invalid");
                }
                BufferedImage decoded;
                try (InputStream input = file.getInputStream(); ImageInputStream imageInput = new MemoryCacheImageInputStream(input)) {
                    ImageReader reader = SundryUtils.imageReader(imageInput);
                    if (reader == null) throw new BusinessException("server.material.image.invalid");
                    try {
                        reader.setInput(imageInput, true, true);
                        decoded = reader.read(0);
                    } finally {
                        reader.dispose();
                    }
                }
                if (decoded == null) {
                    throw new BusinessException("server.material.image.invalid");
                }
                SundryUtils.convertToWebp(decoded, temporary);
            } else {
                file.transferTo(temporary);
            }

            if (image) {
                if (dimensions == null) {
                    try (InputStream input = Files.newInputStream(temporary)) {
                        dimensions = inspectImage(input);
                    }
                    if (!extension.equals(dimensions.format())) {
                        throw new BusinessException("server.material.image.invalid");
                    }
                }
            } else if (!validVideo(temporary, extension)) {
                throw new BusinessException("server.material.video.invalid");
            }

            moveCompletedUpload(temporary, target);

            MaterialAsset asset = new MaterialAsset();
            asset.setName(truncate(StringUtils.hasText(name) ? name.trim() : stripExtension(originalName), 160));
            asset.setMediaType(image ? "IMAGE" : "VIDEO");
            asset.setMimeType(switch (storedExtension) {
                case "webp" -> "image/webp";
                case "gif" -> "image/gif";
                case "mp4" -> "video/mp4";
                case "webm" -> "video/webm";
                case "mov" -> "video/quicktime";
                default -> throw new BusinessException("server.material.file.type.invalid");
            });
            asset.setFileName(storedName);
            asset.setFileUrl("/api/material/" + (image ? "image/" : "video/") + storedName);
            asset.setFileSize(Files.size(target));
            asset.setCategory(truncate(trim(category), 80));
            asset.setTags(truncate(trim(tags), 300));
            asset.setRemark(truncate(trim(remark), 500));
            asset.setCreatedBy(principal == null ? null : principal.getUid());
            asset.setIsDel(0);
            asset.setCreatedAt(SundryUtils.getTime());
            asset.setUpdatedAt(SundryUtils.getTime());
            if (dimensions != null) {
                asset.setWidth(dimensions.width());
                asset.setHeight(dimensions.height());
            }
            if (repository.insert(asset) != 1) {
                throw new BusinessException("server.material.save.failed");
            }
            persisted = true;
            LoggingAspect.setLogDetails("上传" + (image ? "图片" : "视频") + "素材：" + asset.getName() + "，ID: " + asset.getId(), 1);
            return OperationOutcome.success(asset);
        } catch (Exception exception) {
            Files.deleteIfExists(temporary);
            if (!persisted) {
                Files.deleteIfExists(target);
            }
            throw exception;
        }
    }

    public OperationOutcome update( Long id,  MaterialUpdateRequest request) {
        MaterialAsset asset = repository.find(id);
        if (asset == null) throw new BusinessException("server.material.notFound");
        asset.setName(truncate(StringUtils.hasText(request.name()) ? request.name().trim() : asset.getName(), 160));
        asset.setCategory(truncate(trim(request.category()), 80));
        asset.setTags(truncate(trim(request.tags()), 300));
        asset.setRemark(truncate(trim(request.remark()), 500));
        asset.setUpdatedAt(SundryUtils.getTime());
        if (repository.update(asset) != 1) throw new BusinessException("server.material.update.failed");
        LoggingAspect.setLogDetails("修改素材：" + asset.getName() + "，ID: " + id, 1);
        return OperationOutcome.success(asset);
    }

    public OperationOutcome delete( Long id) {
        MaterialAsset asset = repository.find(id);
        if (asset == null) throw new BusinessException("server.material.notFound");
        if (repository.softDelete(id) != 1) throw new BusinessException("server.material.delete.failed");
        LoggingAspect.setLogDetails("软删除素材：" + asset.getName() + "，ID: " + id + "；源文件保留", 1);
        return OperationOutcome.success();
    }

    private String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean shouldConvertToWebp(String extension) {
        return Set.of("jpg", "jpeg", "png").contains(extension);
    }

    /**
     * 只读取图片头部的宽高，在创建完整像素缓冲区前阻断异常大图片。
     */
    private static ImageDimensions inspectImage(InputStream input) {
        try (ImageInputStream imageInput = new MemoryCacheImageInputStream(input)) {
            ImageReader reader = SundryUtils.imageReader(imageInput);
            if (reader == null) {
                throw new BusinessException("server.material.image.invalid");
            }
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateImageDimensions(width, height);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                return new ImageDimensions(width, height, "jpg".equals(format) ? "jpeg" : format);
            } finally {
                reader.dispose();
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("server.material.image.invalid");
        }
    }

    public static void validateImageDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new BusinessException("server.material.image.invalid");
        }
        long pixels = (long) width * height;
        if (width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION || pixels > MAX_IMAGE_PIXELS) {
            throw new BusinessException("server.material.file.tooLarge");
        }
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String truncate(String value, int maxLength) {
        return value != null && value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private boolean validVideo(Path file, String extension) {
        try (java.io.InputStream input = Files.newInputStream(file)) {
            byte[] header = input.readNBytes(16);
            if ("webm".equals(extension)) {
                return header.length >= 4 && (header[0] & 0xff) == 0x1a && (header[1] & 0xff) == 0x45
                        && (header[2] & 0xff) == 0xdf && (header[3] & 0xff) == 0xa3;
            }
            return header.length >= 12 && "ftyp".equals(new String(header, 4, 4, StandardCharsets.US_ASCII));
        } catch (Exception ignored) {
            return false;
        }
    }

    private void moveCompletedUpload(Path temporary, Path target) throws java.io.IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean canManageMaterials(JwtUserPrincipal principal) {
        return principal != null && principal.getAuthorities() != null
                && (principal.getAuthorities().stream().anyMatch(java.util.Set.of("60500", "60501", "60502", "60503")::contains)
                || principal.getAuthorities().contains("super"));
    }

    private record ImageDimensions(int width, int height, String format) {
    }
}
