package org.token.controller.publicapi;

import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.compute.ComputePresentationService;
import org.springframework.web.bind.annotation.*;

/** 沿用平台配置权限与用户独立认证，只读估值不会改动资金。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute")
@RequiredArgsConstructor
public class ComputeRuntimeController {
    private final ComputePresentationService service;

    private final ResultFactory results;

    @GetMapping("/public/runtime")
    public Result runtime() { return results.success(service.runtime()); }

}
