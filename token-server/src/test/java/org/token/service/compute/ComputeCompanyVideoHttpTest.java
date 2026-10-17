package org.token.service.compute;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.token.controller.publicapi.ComputeCompanyVideoPublicController;
import org.token.tools.response.ResultFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用真实MVC资源转换器校验浏览器拖动播放的Range协议，不读真实配置。 */
class ComputeCompanyVideoHttpTest {
    @TempDir Path directory;

    @Test void resourceResponsesSupportRangesSuffixHeadAndInvalidRanges() throws Exception {
        byte[] bytes = ComputeCompanyFileTest.MP4;
        Path file = Files.write(directory.resolve("clip.mp4"), bytes);
        var service = mock(ComputeCompanyFileService.class);
        when(service.video(7, false, true)).thenReturn(ResponseEntity.ok().contentType(MediaType.parseMediaType("video/mp4"))
                .contentLength(bytes.length).header("Cache-Control", "private, no-store").header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(file)));
        var mvc = MockMvcBuilders.standaloneSetup(new ComputeCompanyVideoPublicController(service, mock(ResultFactory.class))).build();
        String url = "/api/compute/public/company-videos/7/video";
        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().bytes(bytes)).andExpect(header().string("Accept-Ranges", "bytes"));
        mvc.perform(get(url).header("Range", "bytes=4-11")).andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 4-11/" + bytes.length))
                .andExpect(content().contentType("video/mp4")).andExpect(content().bytes(Arrays.copyOfRange(bytes, 4, 12)));
        mvc.perform(get(url).header("Range", "bytes=-4")).andExpect(status().isPartialContent())
                .andExpect(content().bytes(Arrays.copyOfRange(bytes, bytes.length - 4, bytes.length)));
        mvc.perform(get(url).header("Range", "bytes=99999-")).andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */" + bytes.length));
        mvc.perform(head(url)).andExpect(status().isOk()).andExpect(header().string("Content-Length", String.valueOf(bytes.length)))
                .andExpect(content().bytes(new byte[0]));
    }
}
