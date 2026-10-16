package org.token.controller.admin;

import jakarta.validation.Valid;
import org.token.model.request.compute.McagCancelRequest;
import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.security.JwtUserPrincipal;
import org.token.custom.LogAction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 本人充值同步与全局财务异常查询，沿用原用户会话和数字权限。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "token.mcag.enabled", havingValue = "true")
public class ComputeMcagController {
    private final org.token.service.compute.ComputeMcagAdminService service;
    private final ResultFactory resultFactory;

    @GetMapping("/admin/gateway/deposits/status")
    @PreAuthorize("hasAnyAuthority('30100','super')")
    public Result status(@RequestParam(defaultValue = "1") int page,
                         @RequestParam(defaultValue = "20") int pageSize) {
        var outcome = service.status(page, pageSize);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/admin/gateway/payouts/status")
    @PreAuthorize("hasAnyAuthority('30200','super')")
    public Result payoutStatus(@RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int pageSize) {
        var outcome = service.payoutStatus(page, pageSize);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/admin/gateway/payouts/{reference}/cancel")
    @PreAuthorize("hasAuthority('super')")
    @LogAction(type="撤销尚未发出的MCAG提款")
    public Result cancel(@PathVariable String reference, @Valid @RequestBody McagCancelRequest request, @AuthenticationPrincipal JwtUserPrincipal administrator) {
        var outcome = service.cancel(reference, request, administrator);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }
}
