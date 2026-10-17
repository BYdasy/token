package org.token.controller.publicapi;

import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.service.compute.ComputeCompanyFileService;
import org.token.tools.response.ResultFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

/** 首页公开公司视频；服务层逐次检查启用及首页发布状态。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute/public/company-videos")
@RequiredArgsConstructor
public class ComputeCompanyVideoPublicController {
    private final ComputeCompanyFileService service;
    private final ResultFactory results;

    @GetMapping
    public Result list() {
        return results.success(service.homepageVideos(LocaleContextHolder.getLocale().toLanguageTag().toLowerCase(Locale.ROOT)));
    }

    @GetMapping("/{id}/video")
    public ResponseEntity<Resource> video(@PathVariable long id, jakarta.servlet.http.HttpServletRequest request) {
        var response = service.video(id, false, true);
        // 显式HEAD保留授权和长度校验，只返回头，不依赖Servlet容器丢弃媒体正文。
        return "HEAD".equals(request.getMethod()) ? ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).build() : response;
    }
}
