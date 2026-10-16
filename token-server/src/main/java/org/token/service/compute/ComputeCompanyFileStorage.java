package org.token.service.compute;

import org.token.exception.BusinessException;
import org.token.tools.media.SundryUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/** 公司PDF沿用平台文件目录，放在独立私有子目录，不进入公开素材接口。 */
@Component
public class ComputeCompanyFileStorage {
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private final Supplier<Path> configuredRoot;
    public record Stored(String fileName, String storedName, long size) {}

    public ComputeCompanyFileStorage() { this(() -> Path.of(SundryUtils.getPath("file", null))); }
    ComputeCompanyFileStorage(Path root) { this(() -> root); }
    private ComputeCompanyFileStorage(Supplier<Path> root) { this.configuredRoot = root; }

    public Stored save(MultipartFile file) {
        if (file == null || file.isEmpty()) throw invalid();
        String name = displayName(file.getOriginalFilename());
        if (!"application/pdf".equalsIgnoreCase(file.getContentType())) throw invalid();
        if (file.getSize() > MAX_FILE_BYTES) throw tooLarge();
        Path temporary = null;
        try {
            Path directory = directory(true);
            String storedName = "company_" + UUID.randomUUID().toString().replace("-", "") + ".pdf";
            Path target = directory.resolve(storedName);
            temporary = Files.createTempFile(directory, ".company_", ".uploading");
            long size = 0;
            byte[] header = new byte[8];
            int headerSize = 0;
            try (var input = file.getInputStream(); var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                for (int length; (length = input.read(buffer)) != -1;) {
                    size += length;
                    if (size > MAX_FILE_BYTES) throw tooLarge();
                    int copy = Math.min(length, header.length - headerSize);
                    if (copy > 0) { System.arraycopy(buffer, 0, header, headerSize, copy); headerSize += copy; }
                    output.write(buffer, 0, length);
                }
            }
            if (size != file.getSize() || headerSize < 8 ||
                    !new String(header, StandardCharsets.US_ASCII).matches("%PDF-(1\\.[0-7]|2\\.0)")) throw invalid();
            // 不覆盖任何已有文件；随机文件名不会由上传参数决定。
            Files.move(temporary, target);
            temporary = null;
            return new Stored(name, storedName, size);
        } catch (IOException failure) {
            throw new UncheckedIOException("公司PDF保存失败", failure);
        } finally {
            if (temporary != null) removeTemporary(temporary);
        }
    }

    public Resource read(String storedName, long expectedSize) {
        try {
            Path file = checkedFile(storedName);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(file)
                    || Files.size(file) != expectedSize || expectedSize <= 0 || expectedSize > MAX_FILE_BYTES) throw missing();
            return new FileSystemResource(file);
        } catch (NoSuchFileException failure) { throw missing(); }
        catch (IOException failure) { throw new UncheckedIOException("公司PDF读取失败", failure); }
    }

    /** 仅用于失败的新上传补偿；已提交公司文件不会通过此方法删除。 */
    public void discard(Stored stored) {
        try { Files.deleteIfExists(checkedFile(stored.storedName())); }
        catch (IOException failure) { throw new UncheckedIOException("公司PDF上传补偿失败", failure); }
    }

    private Path checkedFile(String name) throws IOException {
        if (name == null || !name.matches("company_[a-f0-9]{32}\\.pdf")) throw missing();
        return directory(false).resolve(name);
    }

    private Path directory(boolean create) throws IOException {
        Path root = configuredRoot.get().toAbsolutePath().normalize();
        if (create) Files.createDirectories(root);
        Path realRoot = root.toRealPath();
        Path directory = realRoot.resolve("company-files");
        if (create) Files.createDirectories(directory);
        // 禁止专用子目录或文件通过软链接逃逸到其他素材/系统目录。
        if (Files.isSymbolicLink(directory) || !directory.toRealPath().startsWith(realRoot)) throw missing();
        return directory;
    }

    public static String displayName(String value) {
        if (value == null) throw invalid();
        String name = value.trim();
        if (name.length() < 5 || name.length() > 200 || !name.toLowerCase(Locale.ROOT).endsWith(".pdf")
                || name.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '/' || c == '\\')) throw invalid();
        return name;
    }
    private static void removeTemporary(Path file) {
        try { Files.deleteIfExists(file); }
        catch (IOException failure) { throw new UncheckedIOException("公司PDF临时文件清理失败", failure); }
    }
    private static BusinessException invalid() { return new BusinessException("server.compute.companyFile.invalidPdf"); }
    private static BusinessException tooLarge() { return new BusinessException("server.compute.companyFile.tooLarge"); }
    static BusinessException missing() { return new BusinessException(404, "server.compute.companyFile.notFound"); }
}
