package org.token.controller.publicapi;

import lombok.RequiredArgsConstructor;
import org.token.exception.BusinessException;
import org.token.service.system.MaterialFileService;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.io.IOException;

@RestController
@RequestMapping("/api/material")
@RequiredArgsConstructor
public class MaterialFileController {
    private final MaterialFileService service;

    @GetMapping("/image/{filename:.+}")
    public ResponseEntity<Resource> image(@PathVariable String filename) throws IOException {
        return file(filename, true);
    }

    @GetMapping("/video/{filename:.+}")
    public ResponseEntity<Resource> video(@PathVariable String filename) throws IOException {
        return file(filename, false);
    }

    private ResponseEntity<Resource> file(String filename, boolean image) throws IOException {
        try {
            var location = service.resolve(filename, image);
            Resource resource = new UrlResource(location.path().toUri());
            MediaType mediaType = MediaTypeFactory.getMediaType(filename)
                    .orElse(image ? MediaType.IMAGE_JPEG : MediaType.APPLICATION_OCTET_STREAM);
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .contentLength(location.size())
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                            .filename(filename, StandardCharsets.UTF_8).build().toString())
                    .body(resource);
        } catch (BusinessException rejected) {
            return ResponseEntity.status(rejected.getCode()).build();
        }
    }
}
