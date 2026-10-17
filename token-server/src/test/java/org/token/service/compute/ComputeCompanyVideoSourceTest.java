package org.token.service.compute;

import org.junit.jupiter.api.Test;
import org.token.exception.BusinessException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ComputeCompanyVideoSourceTest {
    @Test void youtubeEmbedsAreRebuiltFromOnlyValidatedIdsAndIgnoreTrackingAndAutoplay() {
        for (String link : List.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ&autoplay=1", "https://youtu.be/dQw4w9WgXcQ?si=foo",
                "https://m.youtube.com/shorts/dQw4w9WgXcQ", "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ")) {
            var source = ComputeCompanyVideoSource.parse(link);
            assertEquals("YOUTUBE", source.playbackType());
            assertEquals("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ", source.embedUrl());
            assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", source.url());
        }
    }

    @Test void vimeoEmbedsAndHttpsVideoFilesAreRecognizedWithoutRemoteAccess() {
        assertEquals("https://player.vimeo.com/video/123456789", ComputeCompanyVideoSource.parse("https://vimeo.com/123456789?autoplay=1").embedUrl());
        assertEquals("VIMEO", ComputeCompanyVideoSource.parse("https://player.vimeo.com/video/123456789").playbackType());
        var mp4 = ComputeCompanyVideoSource.parse("https://media.example.org/video.MP4?signature=opaque");
        assertEquals("DIRECT", mp4.playbackType()); assertEquals("video/mp4", mp4.mimeType());
        assertEquals("https://media.example.org/video.MP4?signature=opaque", mp4.url());
        assertNull(mp4.embedUrl());
        assertEquals("video/webm", ComputeCompanyVideoSource.parse("https://media.example.org/video.webm").mimeType());
    }

    @Test void arbitraryIframesLocalTargetsCredentialsAndMalformedIdsAreRejected() {
        for (String link : List.of("javascript:alert(1)", "data:text/html,hello", "http://example.org/a.mp4", "https://example.org/page",
                "https://youtube.com.evil.org/watch?v=dQw4w9WgXcQ", "https://example.org/embed/dQw4w9WgXcQ",
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&v=xxxxxxxxxxx", "https://youtube.com/embed/%3Ciframe%3E",
                "https://media.example.org/video.mp4#fragment", "https://youtu.be/dQw4w9WgXcQ#t=2",
                "https://media.example.org/video%2Emp4",
                "https://youtube.com/watch?v=bad", "https://vimeo.com/channels/123", "https://localhost/a.mp4", "https://127.0.0.1/a.mp4",
                "https://[::1]/a.mp4", "https://internal.local/a.webm", "https://example.org:8443/a.mp4", "https://user:pass@example.org/a.mp4",
                "https://example.org\\@evil.org/a.mp4", "//example.org/a.mp4")) {
            assertEquals("server.compute.companyFile.invalidVideoLink", assertThrows(BusinessException.class, () -> ComputeCompanyVideoSource.parse(link), link).getMessage());
        }
    }
}
