package org.token.controller.user;

import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.compute.ComputeMemberAccessService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 用户会话操作沿用后台安全链和独立数字权限，不授予用户任何后台身份。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute")
@RequiredArgsConstructor
public class ComputeImpersonationController {
    private final ComputeMemberAccessService access;
    private final ResultFactory results;

    @PostMapping("/auth/impersonation")
    public Result exchange(@RequestBody Map<String,String> request){return results.success(access.exchange(request.get("ticket")));}

}
