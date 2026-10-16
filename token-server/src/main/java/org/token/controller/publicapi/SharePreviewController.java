package org.token.controller.publicapi;

import jakarta.servlet.http.HttpServletRequest;
import org.token.service.compute.SharePreviewService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.Duration;
import java.util.UUID;

/** 对游客只公开后台选定的分享文案和单张图片，不公开业务内容、会员或配置全集。 */
@org.token.custom.web.ComputeApi
@RestController
public class SharePreviewController {
    private final SharePreviewService previews;
    public SharePreviewController(SharePreviewService previews) { this.previews = previews; }

    @GetMapping(value = SharePreviewService.SHARE_PATH, produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> page(HttpServletRequest request, @RequestParam(required = false) String target) {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", "default-src 'none'; img-src 'self' https:; style-src 'nonce-" + nonce + "'; script-src 'nonce-" + nonce + "'; base-uri 'none'; frame-ancestors 'none'")
                .header("X-Token-Share-Preview", "1")
                .header("Vary", "User-Agent")
                .header("X-Content-Type-Options", "nosniff").header("Referrer-Policy", "no-referrer")
                .body(previews.html(request, target, nonce));
    }

    @GetMapping(SharePreviewService.IMAGE_PATH)
    public ResponseEntity<byte[]> image() {
        byte[] bytes = previews.image();
        if (bytes == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).contentLength(bytes.length)
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .header("X-Content-Type-Options", "nosniff").body(bytes);
    }
}
