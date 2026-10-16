package org.token.controller.publicapi;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 游客首页只读摘要，不提供完整目录、内容详情或用户业务数据。 */
@org.token.custom.web.ComputeApi
@RestController
@RequiredArgsConstructor
public class ComputeHomeController {
    private final org.token.service.compute.ComputeHomeQueryService service;
    private final ResultFactory resultFactory;
    private final org.token.config.EnabledLocaleResolver locales;

    @GetMapping("/api/compute/public/home")
    public Result home(HttpServletRequest request) {
        var outcome = service.home(locales.resolve(request), request.getParameter("popupPage"));
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }
}
