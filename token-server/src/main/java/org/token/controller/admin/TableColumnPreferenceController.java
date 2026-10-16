package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.security.JwtUserPrincipal;
import org.token.service.impl.TableColumnPreferenceService;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 栏位是个人显示偏好；后台认证链保护接口，账号编号始终取自认证主体。 */
@RestController
@RequestMapping("/api/system/table-columns")
@RequiredArgsConstructor
public class TableColumnPreferenceController {
    private final TableColumnPreferenceService preferences;
    private final ResultFactory results;

    @GetMapping
    public Result read(@AuthenticationPrincipal JwtUserPrincipal principal, @RequestParam String pageKey) {
        return results.success(preferences.read(uid(principal), pageKey));
    }

    @PutMapping
    @org.token.custom.LogAction(type = "后台表格列设置")
    public Result save(@AuthenticationPrincipal JwtUserPrincipal principal, @RequestBody org.token.model.request.system.TableColumnPreferenceRequest request) {
        return results.success(preferences.save(uid(principal), request.pageKey(), request.columns()));
    }

    private static int uid(JwtUserPrincipal principal) {
        if (principal == null || principal.getUid() == null) {
            throw new AuthenticationCredentialsNotFoundException("后台账号未登录");
        }
        return principal.getUid();
    }
}
