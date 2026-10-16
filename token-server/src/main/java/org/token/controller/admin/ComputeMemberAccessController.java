package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.custom.LoggingAspect;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.compute.ComputeMemberAccessService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 用户会话操作沿用后台安全链和独立数字权限，不授予用户任何后台身份。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute")
@RequiredArgsConstructor
public class ComputeMemberAccessController {
    private final ComputeMemberAccessService access;
    private final ResultFactory results;

    @PostMapping("/admin/members/{id}/impersonation")
    @PreAuthorize("hasAnyAuthority('20105','super')")
    @LogAction(type="模拟用户登录")
    public Result impersonate(@PathVariable long id){
        var issued=access.issue(id);
        LoggingAspect.setLogDetails("为用户 UID："+id+" 签发一次性模拟登录票据",1);
        return results.success(issued);
    }

    @PostMapping("/admin/members/{id}/force-logout")
    @PreAuthorize("hasAnyAuthority('20106','super')")
    @LogAction(type="强制用户下线")
    public Result forceLogout(@PathVariable long id){
        int revoked=access.forceLogout(id);
        LoggingAspect.setLogDetails("强制用户 UID："+id+" 下线，失效会话："+revoked,1);
        return results.success(Map.of("revoked",revoked));
    }

    @GetMapping("/admin/members/{id}/ancestors")
    @PreAuthorize("hasAnyAuthority('20100','20101','20102','20103','20104','20105','20106','super')")
    public Result ancestors(@PathVariable long id){return results.success(access.ancestors(id));}

}
