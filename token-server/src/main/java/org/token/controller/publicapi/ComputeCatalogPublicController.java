package org.token.controller.publicapi;

import lombok.RequiredArgsConstructor;
import org.token.model.security.ComputeMemberPrincipal;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 用户业务端点，只从独立会员身份读取数据归属。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute")
@RequiredArgsConstructor
public class ComputeCatalogPublicController {
    private final org.token.service.compute.ComputeUserApplicationService service;
    private final ResultFactory resultFactory;
    private final org.token.config.EnabledLocaleResolver locales;

    @GetMapping("/public/catalog")
    public Result catalog(@AuthenticationPrincipal ComputeMemberPrincipal principal, jakarta.servlet.http.HttpServletRequest request) {
        var outcome = service.catalog(principal, locales.resolve(request));
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

}
