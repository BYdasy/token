package org.token.controller.publicapi;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.token.custom.security.ClientIpResolver;
import org.token.custom.web.ComputeApi;
import org.token.model.response.Result;
import org.token.service.compute.ComputeBootstrapService;
import org.token.tools.response.ResultFactory;
import org.springframework.web.bind.annotation.*;

/** 用户端公开启动配置。 */
@ComputeApi
@RestController
@RequestMapping("/api/compute")
@RequiredArgsConstructor
public class ComputePublicController {
    private final ComputeBootstrapService bootstrap;
    private final ClientIpResolver ipResolver;
    private final ResultFactory results;

    @GetMapping("/public/bootstrap")
    public Result bootstrap(@RequestParam(required = false) String lang, HttpServletRequest request) {
        return results.success(bootstrap.bootstrap(lang, ipResolver.resolve(request)));
    }
}
