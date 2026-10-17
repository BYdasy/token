package org.token.service.compute;

import org.token.exception.BusinessException;
import org.token.tools.media.SundryUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/** 公司资源沿用平台文件目录，放在独立私有子目录，不进入公开素材接口。 */
@Component
public class ComputeCompanyFileStorage {
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    public static final long MAX_VIDEO_BYTES = 100L * 1024 * 1024;
    private final Supplier<Path> configuredRoot;
    private final long uploadLimit;
    public record Stored(String fileName, String storedName, long size, String resourceType, String mimeType) {}

    @org.springframework.beans.factory.annotation.Autowired
    public ComputeCompanyFileStorage(MultipartProperties properties) {
        this(() -> Path.of(SundryUtils.getPath("file", null)), Math.min(limit(properties.getMaxFileSize().toBytes()),
                Math.max(0, limit(properties.getMaxRequestSize().toBytes()) - 1024 * 1024)));
    }
    ComputeCompanyFileStorage(Path root) { this(() -> root, MAX_VIDEO_BYTES); }
    private ComputeCompanyFileStorage(Supplier<Path> root, long uploadLimit) { this.configuredRoot = root; this.uploadLimit = uploadLimit; }
    private static long limit(long configured) { return configured < 0 ? Long.MAX_VALUE : configured; }
    public long maxVideoBytes() { return Math.min(MAX_VIDEO_BYTES, uploadLimit); }
    public long maxPdfBytes() { return Math.min(MAX_FILE_BYTES, uploadLimit); }

    public Stored save(MultipartFile file) {
        if (file == null || file.isEmpty()) throw invalid();
        boolean video = file.getOriginalFilename() != null && file.getOriginalFilename().toLowerCase(Locale.ROOT).matches(".*\\.(mp4|webm)$");
        String name = video ? videoName(file.getOriginalFilename()) : displayName(file.getOriginalFilename());
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String mime = video ? "video/" + extension : "application/pdf";
        if (!mime.equalsIgnoreCase(file.getContentType())) throw video ? invalidVideo() : invalid();
        long max = video ? maxVideoBytes() : maxPdfBytes();
        if (file.getSize() > max) throw video ? videoTooLarge() : tooLarge();
        Path temporary = null;
        try {
            Path directory = directory(true);
            String storedName = "company_" + UUID.randomUUID().toString().replace("-", "") + "." + extension;
            Path target = directory.resolve(storedName);
            temporary = Files.createTempFile(directory, ".company_", ".uploading");
            long size = 0;
            byte[] header = new byte[video ? 4096 : 8];
            int headerSize = 0;
            try (var input = file.getInputStream(); var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                for (int length; (length = input.read(buffer)) != -1;) {
                    size += length;
                    if (size > max) throw video ? videoTooLarge() : tooLarge();
                    int copy = Math.min(length, header.length - headerSize);
                    if (copy > 0) { System.arraycopy(buffer, 0, header, headerSize, copy); headerSize += copy; }
                    output.write(buffer, 0, length);
                }
            }
            if (size != file.getSize()) throw video ? invalidVideo() : invalid();
            if (video) {
                if (!validVideoHeader(header, headerSize, size, extension)) throw invalidVideo();
            } else if (headerSize < 8 || !new String(header, StandardCharsets.US_ASCII).matches("%PDF-(1\\.[0-7]|2\\.0)")) throw invalid();
            // 不覆盖任何已有文件；随机文件名不会由上传参数决定。
            Files.move(temporary, target);
            temporary = null;
            return new Stored(name, storedName, size, video ? "VIDEO" : "PDF", mime);
        } catch (IOException failure) {
            throw new UncheckedIOException("公司文件保存失败", failure);
        } finally {
            if (temporary != null) removeTemporary(temporary);
        }
    }

    public Resource read(String storedName, long expectedSize) { return read(storedName, expectedSize, false); }

    public Resource read(String storedName, long expectedSize, boolean video) {
        try {
            Path file = checkedFile(storedName);
            if (video != !storedName.endsWith(".pdf")) throw missing();
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(file)
                    || Files.size(file) != expectedSize || expectedSize <= 0 || expectedSize > (video ? MAX_VIDEO_BYTES : MAX_FILE_BYTES)) throw missing();
            return new FileSystemResource(file);
        } catch (NoSuchFileException failure) { throw missing(); }
        catch (IOException failure) { throw new UncheckedIOException("公司文件读取失败", failure); }
    }

    /** 仅用于失败的新上传补偿；已提交公司文件不会通过此方法删除。 */
    public void discard(Stored stored) {
        try { Files.deleteIfExists(checkedFile(stored.storedName())); }
        catch (IOException failure) { throw new UncheckedIOException("公司文件上传补偿失败", failure); }
    }

    private Path checkedFile(String name) throws IOException {
        if (name == null || !name.matches("company_[a-f0-9]{32}\\.(pdf|mp4|webm)")) throw missing();
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
    public static String videoName(String value) {
        if (value == null) throw invalidVideo();
        String name = value.trim();
        if (name.length() > 200 || !name.toLowerCase(Locale.ROOT).matches(".+\\.(mp4|webm)")
                || name.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '/' || c == '\\')) throw invalidVideo();
        return name;
    }

    /** 有界容器签名检查：不信任后缀或客户端MIME，不在请求线程进行视频转码。 */
    private static boolean validVideoHeader(byte[] bytes, int count, long size, String extension) {
        if ("mp4".equals(extension)) {
            if (count < 24 || !ascii(bytes, 4, 4).equals("ftyp")) return false;
            long boxSize = ((bytes[0] & 255L) << 24) | ((bytes[1] & 255L) << 16) | ((bytes[2] & 255L) << 8) | (bytes[3] & 255L);
            if (boxSize < 24 || boxSize > count || boxSize > size || boxSize % 4 != 0) return false;
            var brands = java.util.Set.of("isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "av01", "dash", "M4V ");
            for (int position = 8; position + 4 <= boxSize; position += 4) {
                if (position != 12 && brands.contains(ascii(bytes, position, 4))) return true;
            }
            return false;
        }
        if (count < 12 || (bytes[0] & 255) != 0x1a || (bytes[1] & 255) != 0x45 || (bytes[2] & 255) != 0xdf || (bytes[3] & 255) != 0xa3) return false;
        int[] cursor = {4};
        long headerSize = vint(bytes, count, cursor, false);
        if (headerSize < 0 || headerSize > count - cursor[0]) return false;
        int end = cursor[0] + (int) headerSize;
        while (cursor[0] < end) {
            long id = vint(bytes, end, cursor, true);
            long length = vint(bytes, end, cursor, false);
            if (id < 0 || length < 0 || length > end - cursor[0]) return false;
            if (id == 0x4282) return length == 4 && ascii(bytes, cursor[0], 4).equals("webm");
            cursor[0] += (int) length;
        }
        return false;
    }
    private static long vint(byte[] bytes, int end, int[] cursor, boolean preserveMarker) {
        if (cursor[0] >= end) return -1;
        int first = bytes[cursor[0]++] & 255, mask = 128, length = 1;
        while (mask > 0 && (first & mask) == 0) { mask >>= 1; length++; }
        if (mask == 0 || length > (preserveMarker ? 4 : 8) || cursor[0] + length - 1 > end) return -1;
        long value = preserveMarker ? first : first & (mask - 1);
        for (int index = 1; index < length; index++) value = (value << 8) | (bytes[cursor[0]++] & 255);
        return value;
    }
    private static String ascii(byte[] bytes, int start, int count) { return new String(bytes, start, count, StandardCharsets.US_ASCII); }
    private static void removeTemporary(Path file) {
        try { Files.deleteIfExists(file); }
        catch (IOException failure) { throw new UncheckedIOException("公司文件临时文件清理失败", failure); }
    }
    private static BusinessException invalid() { return new BusinessException("server.compute.companyFile.invalidPdf"); }
    private static BusinessException tooLarge() { return new BusinessException("server.compute.companyFile.tooLarge"); }
    private static BusinessException invalidVideo() { return new BusinessException("server.compute.companyFile.invalidVideo"); }
    private static BusinessException videoTooLarge() { return new BusinessException("server.compute.companyFile.videoTooLarge"); }
    static BusinessException missing() { return new BusinessException(404, "server.compute.companyFile.notFound"); }
}
