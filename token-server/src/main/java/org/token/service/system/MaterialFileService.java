package org.token.service.system;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import org.token.exception.BusinessException;
import org.token.model.response.MaterialFileLocation;
import org.token.tools.media.SundryUtils;
import org.springframework.stereotype.Service;

/** 公开素材读取只解析受控目录内已有文件，HTTP 类型和范围响应由控制器处理。 */
@Service
public class MaterialFileService {
    private static final Set<String> IMAGE_TYPES = Set.of("jpg", "jpeg", "png", "gif", "webp");
    private static final Set<String> VIDEO_TYPES = Set.of("mp4", "webm", "mov");

    public MaterialFileLocation resolve(String filename, boolean image) throws IOException {
        if (filename == null || filename.isBlank() || filename.contains("/") || filename.contains("\\"))
            throw new BusinessException(400, "server.material.file.path.invalid");
        int dot = filename.lastIndexOf('.');
        String extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!(image ? IMAGE_TYPES : VIDEO_TYPES).contains(extension))
            throw new BusinessException(400, "server.material.file.type.invalid");
        Path base = Path.of(SundryUtils.getPath(image ? "img" : "file", null)).toAbsolutePath().normalize();
        Path target = base.resolve(filename).normalize();
        if (!target.startsWith(base) || !Files.isRegularFile(target) || !Files.isReadable(target))
            throw new BusinessException(404, "server.material.notFound");
        return new MaterialFileLocation(target, Files.size(target));
    }
}
