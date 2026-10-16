package org.token.tools.media;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.IOException;
import javax.imageio.stream.FileImageInputStream;
import com.luciad.imageio.webp.WebPImageWriterSpi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

class SundryUtilsTest {

    @Test
    void convertsStaticImageToReadableWebp() throws Exception {
        BufferedImage image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, 8, 6);
        graphics.dispose();

        Path output = Files.createTempFile("exp-webp-", ".webp");
        try {
            SundryUtils.convertToWebp(image, output);
            assertTrue(Files.size(output) > 0);
            BufferedImage decoded;
            try (var input = new FileImageInputStream(output.toFile())) {
                var reader = SundryUtils.imageReader(input);
                assertEquals("com.twelvemonkeys.imageio.plugins.webp.WebPImageReader", reader.getClass().getName());
                try { reader.setInput(input); decoded = reader.read(0); }
                finally { reader.dispose(); }
            }
            assertNotNull(decoded);
            assertEquals(8, decoded.getWidth());
            assertEquals(6, decoded.getHeight());
        } finally {
            Files.deleteIfExists(output);
        }
    }

    @Test
    void preservesTransparencyAndWebpSignature() throws Exception {
        BufferedImage image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(3, 2, 0x80446688);
        Path output = Files.createTempFile("token-webp-alpha-", ".webp");
        try {
            SundryUtils.convertToWebp(image, output);
            byte[] bytes = Files.readAllBytes(output);
            assertEquals("RIFF", new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
            assertEquals("WEBP", new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII));
            try (var input = new FileImageInputStream(output.toFile())) {
                var reader = SundryUtils.imageReader(input);
                try {
                    reader.setInput(input);
                    BufferedImage actual = reader.read(0);
                    assertEquals(0, actual.getRGB(0, 0) >>> 24);
                    assertEquals(128, actual.getRGB(3, 2) >>> 24);
                } finally { reader.dispose(); }
            }
        } finally { Files.deleteIfExists(output); }
    }

    @Test
    void bundledLinuxNativesMatchTheirCpuArchitecture() throws Exception {
        for (var target : java.util.Map.of("aarch64", 183, "x86_64", 62).entrySet()) {
            try (var stream = WebPImageWriterSpi.class.getResourceAsStream("/native/Linux/" + target.getKey() + "/libwebp-imageio.so")) {
                assertNotNull(stream, target.getKey());
                byte[] header = stream.readNBytes(20);
                assertEquals(0x7f, header[0] & 0xff);
                assertEquals("ELF", new String(header, 1, 3, java.nio.charset.StandardCharsets.US_ASCII));
                assertEquals(2, header[4]); // ELF64，e_machine 必须对应真实 CPU。
                assertEquals(target.getValue().intValue(), ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getShort(18));
            }
        }
        assertTrue(kotlin.KotlinVersion.CURRENT.isAtLeast(2, 4, 0));
    }

    @Test
    void nativeLoadFailureBecomesAnIoFailureWithArchitectureDiagnostics() throws Exception {
        Path output = Files.createTempFile("token-webp-unavailable-", ".webp");
        try (var ignored = mockConstruction(WebPImageWriterSpi.class, (writer, context) ->
                when(writer.createWriterInstance()).thenThrow(new UnsatisfiedLinkError("wrong architecture")))) {
            IOException failure = assertThrows(IOException.class,
                    () -> SundryUtils.convertToWebp(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), output));
            assertTrue(failure.getMessage().contains("os.arch="));
            assertTrue(failure.getMessage().contains("libc"));
            assertTrue(failure.getCause() instanceof UnsatisfiedLinkError);
        } finally { Files.deleteIfExists(output); }
    }

    @Test
    void windowsSelectsSeparateImageAndFilePaths() {
        assertEquals("D:/exp/images", resolve(true, "image", "", ""));
        assertEquals("D:/exp/files", resolve(true, "file", "", ""));
    }

    @Test
    void linuxSelectsSeparateImageAndFilePaths() {
        assertEquals("/srv/exp/images", resolve(false, "img", "", ""));
        assertEquals("/srv/exp/files", resolve(false, "video", "", ""));
    }

    @Test
    void genericEnvironmentOverridesWinOnEitherOperatingSystem() {
        assertEquals("X:/override/images", resolve(true, "image", " X:/override/images ", ""));
        assertEquals("/override/files", resolve(false, "file", "", " /override/files "));
    }

    private String resolve(boolean windows, String type, String imageOverride, String fileOverride) {
        return SundryUtils.resolveStoragePath(
                windows,
                type,
                imageOverride,
                fileOverride,
                "D:/exp/images",
                "D:/exp/files",
                "/srv/exp/images",
                "/srv/exp/files"
        );
    }
}
