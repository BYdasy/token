package org.token.controller.admin;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.model.request.system.IpWhitelistSaveRequest;
import org.token.model.request.system.IpWhitelistSwitchRequest;
import org.token.model.request.system.LongIdRequest;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.security.JwtUserPrincipal;
import org.token.custom.security.ClientIpResolver;
import org.token.model.response.IpWhitelistStatusView;
import org.token.service.IpWhitelistService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/system/ip-whitelist")
@RequiredArgsConstructor
public class IpWhitelistController {

    private final IpWhitelistService whitelistService;
    private final ResultFactory resultFactory;
    private final ClientIpResolver clientIpResolver;

    @PostMapping("/list")
    @PreAuthorize("hasAnyAuthority('60801','super')")
    public Result list() {
        return resultFactory.success(whitelistService.listAll());
    }

    @GetMapping("/status")
    @PreAuthorize("hasAnyAuthority('60801','super')")
    public Result status(HttpServletRequest request) {
        String clientIp = clientIpResolver.resolve(request);
        boolean directLocal = clientIpResolver.isDirectLocalRequest(request);
        return resultFactory.success(new IpWhitelistStatusView(
                whitelistService.isEnabled(),
                clientIp,
                directLocal || whitelistService.isAllowed(clientIp),
                whitelistService.activeRuleCount()
        ));
    }

    @PostMapping("/create")
    @PreAuthorize("hasAnyAuthority('60802','super')")
    @LogAction(type = "IP白名单新增")
    public Result create(
            @RequestBody IpWhitelistSaveRequest request,
            @AuthenticationPrincipal JwtUserPrincipal principal
    ) {
        return resultFactory.success(whitelistService.create(request, principal == null ? null : principal.getUid()));
    }

    @PostMapping("/update")
    @PreAuthorize("hasAnyAuthority('60803','super')")
    @LogAction(type = "IP白名单修改")
    public Result update(@RequestBody IpWhitelistSaveRequest request, HttpServletRequest httpServletRequest) {
        String clientIp = clientIpResolver.resolve(httpServletRequest);
        return resultFactory.success(whitelistService.update(
                request,
                clientIp,
                clientIpResolver.isDirectLocalRequest(httpServletRequest)
        ));
    }

    @PostMapping("/delete")
    @PreAuthorize("hasAnyAuthority('60804','super')")
    @LogAction(type = "IP白名单删除")
    public Result delete(@RequestBody LongIdRequest request, HttpServletRequest httpServletRequest) {
        String clientIp = clientIpResolver.resolve(httpServletRequest);
        whitelistService.delete(
                request == null ? null : request.getId(),
                clientIp,
                clientIpResolver.isDirectLocalRequest(httpServletRequest)
        );
        return resultFactory.success();
    }

    @PostMapping("/status/update")
    @PreAuthorize("hasAnyAuthority('60805','super')")
    @LogAction(type = "IP白名单开关")
    public Result updateStatus(
            @RequestBody IpWhitelistSwitchRequest request,
            @AuthenticationPrincipal JwtUserPrincipal principal,
            HttpServletRequest httpServletRequest
    ) {
        boolean enabled = request != null && Boolean.TRUE.equals(request.getEnabled());
        String clientIp = clientIpResolver.resolve(httpServletRequest);
        whitelistService.setEnabled(
                enabled,
                principal == null ? null : principal.getUid(),
                clientIp,
                clientIpResolver.isDirectLocalRequest(httpServletRequest)
        );
        return resultFactory.success(Map.of("enabled", enabled));
    }
}
