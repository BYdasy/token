package org.token.controller.admin;

import org.token.service.compute.ComputeAccountService;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.custom.LoggingAspect;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.security.JwtUserPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 找回申请必须先由有权限的管理员核验身份；接口仅返回一次短期凭据。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute/admin/recoveries")
@RequiredArgsConstructor
public class ComputeRecoveryAdminController {
    private final ComputeAccountService accounts;
    private final ResultFactory results;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('20500','20501','super')")
    public Result list(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        return results.success(accounts.recoveries(page, size, keyword, startTime, endTime));
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize("hasAnyAuthority('20501','super')")
    @LogAction(type = "算力会员找回凭据签发")
    public Result issue(@PathVariable long id, @AuthenticationPrincipal JwtUserPrincipal admin) {
        var issued = accounts.issueRecovery(id, admin.getUid());
        LoggingAspect.setLogDetails("算力会员找回凭据签发成功，申请编号：" + id, 1);
        return results.success(issued);
    }
}
