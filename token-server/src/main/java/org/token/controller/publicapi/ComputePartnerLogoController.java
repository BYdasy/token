package org.token.controller.publicapi;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Set;

/** 两端共用的内置品牌素材，仅允许固定文件名，不接受任意资源路径。 */
@org.token.custom.web.ComputeApi
@RestController
public class ComputePartnerLogoController {
    private static final Set<String> FILES = Set.of("openai.svg", "google.svg", "microsoft.svg", "meta.svg",
            "apple.svg", "amazon.svg", "deepseek.svg", "anthropic.svg", "xai.svg");

    @GetMapping("/api/compute/public/partner-logos/{name}")
    public ResponseEntity<Resource> logo(@PathVariable String name) {
        if (!FILES.contains(name)) return ResponseEntity.notFound().build();
        var resource = new ClassPathResource("partner-logos/" + name);
        if (!resource.exists()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("image/svg+xml"))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
                .body(resource);
    }
}
