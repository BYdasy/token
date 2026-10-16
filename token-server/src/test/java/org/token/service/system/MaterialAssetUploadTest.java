package org.token.service.system;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.token.exception.BusinessException;
import org.token.model.entity.MaterialAsset;
import org.token.repository.system.MaterialAssetRepository;
import org.token.tools.media.SundryUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MaterialAssetUploadTest {
    @TempDir Path temporary;
    private final MaterialAssetRepository repository = mock(MaterialAssetRepository.class);
    private final MaterialAssetService service = new MaterialAssetService(repository);
    private String oldImage;
    private String oldFile;

    @BeforeEach void storage() {
        oldImage = SundryUtils.getPath("img", null);
        oldFile = SundryUtils.getPath("file", null);
        new SundryUtils("UTC", temporary.toString(), temporary.toString(), oldImage, oldFile, oldImage, oldFile, 80);
    }

    @AfterEach void restoreStorage() {
        new SundryUtils("UTC", oldImage, oldFile, oldImage, oldFile, oldImage, oldFile, 80);
    }

    @Test void jpegAndTransparentPngBecomeActualWebpWithMatchingMetadata() throws Exception {
        when(repository.insert(any())).thenReturn(1);
        for (String format : new String[]{"jpeg", "png"}) {
            byte[] bytes = image(format);
            var result = service.upload(new MockMultipartFile("file", "asset." + format, "text/html", bytes),
                    null, null, null, null, null);
            MaterialAsset asset = (MaterialAsset) result.data();
            assertEquals("image/webp", asset.getMimeType());
            assertTrue(asset.getFileName().endsWith(".webp"));
            assertEquals(8, asset.getWidth());
            assertEquals(6, asset.getHeight());
            byte[] actual = Files.readAllBytes(temporary.resolve(asset.getFileName()));
            assertEquals("WEBP", new String(actual, 8, 4, java.nio.charset.StandardCharsets.US_ASCII));
            assertEquals(actual.length, asset.getFileSize());
        }
    }

    @Test void gifKeepsItsBytesAndUsesVerifiedMimeInsteadOfClientMime() throws Exception {
        when(repository.insert(any())).thenReturn(1);
        byte[] original = image("gif");
        MaterialAsset asset = (MaterialAsset) service.upload(new MockMultipartFile("file", "asset.gif", "text/html", original),
                null, null, null, null, null).data();
        assertEquals("image/gif", asset.getMimeType());
        assertTrue(asset.getFileName().endsWith(".gif"));
        assertArrayEquals(original, Files.readAllBytes(temporary.resolve(asset.getFileName())));
    }

    @Test void existingWebpUploadUsesPureJavaInspectionAndPreservesSource() throws Exception {
        when(repository.insert(any())).thenReturn(1);
        Path source = temporary.resolve("source.webp");
        SundryUtils.convertToWebp(new BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB), source);
        byte[] original = Files.readAllBytes(source);
        MaterialAsset asset = (MaterialAsset) service.upload(new MockMultipartFile("file", "asset.webp", "application/octet-stream", original),
                null, null, null, null, null).data();
        assertEquals("image/webp", asset.getMimeType());
        assertArrayEquals(original, Files.readAllBytes(temporary.resolve(asset.getFileName())));
    }

    @Test void rejectsForgedPreservedImageExtensionAndLeavesNoFileOrRow() throws Exception {
        assertThrows(BusinessException.class, () -> service.upload(new MockMultipartFile("file", "fake.webp", "image/webp", image("png")),
                null, null, null, null, null));
        verifyNoInteractions(repository);
        try (var files = Files.list(temporary)) { assertEquals(0, files.count()); }
    }

    @Test void rejectsInvalidImageAndLeavesNoFileOrRow() throws Exception {
        assertThrows(BusinessException.class, () -> service.upload(new MockMultipartFile("file", "fake.png", "image/png", "invalid".getBytes()),
                null, null, null, null, null));
        verifyNoInteractions(repository);
        try (var files = Files.list(temporary)) { assertEquals(0, files.count()); }
    }

    @Test void databaseFailureRemovesCompletedAndTemporaryFiles() throws Exception {
        when(repository.insert(any())).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.upload(new MockMultipartFile("file", "asset.png", "image/png", image("png")),
                null, null, null, null, null));
        try (var files = Files.list(temporary)) { assertEquals(0, files.count()); }
    }

    @Test void nativeArchitectureFailureLeavesNoUploadedFileOrDatabaseRow() throws Exception {
        byte[] original = image("png");
        try (var ignored = mockConstruction(com.luciad.imageio.webp.WebPImageWriterSpi.class, (writer, context) ->
                when(writer.createWriterInstance()).thenThrow(new UnsatisfiedLinkError("AMD64 on AARCH64")))) {
            assertThrows(java.io.IOException.class, () -> service.upload(new MockMultipartFile("file", "asset.png", "image/png", original),
                    null, null, null, null, null));
        }
        verifyNoInteractions(repository);
        try (var files = Files.list(temporary)) { assertEquals(0, files.count()); }
    }

    private static byte[] image(String format) throws Exception {
        BufferedImage image = new BufferedImage(8, 6, "jpeg".equals(format) ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        image.setRGB(3, 2, 0x80446688);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, output));
        return output.toByteArray();
    }
}
