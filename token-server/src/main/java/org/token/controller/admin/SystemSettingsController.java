package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.custom.LoggingAspect;
import org.token.model.request.system.SystemSettingsUpdateRequest;
import org.token.model.request.system.AccountTimeZoneRequest;
import org.token.exception.BusinessException;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.security.JwtUserPrincipal;
import org.token.service.SystemSettingService;
import org.token.service.TimeZoneCatalogService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 网站基础设置与服务端展示时间接口。 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemSettingsController {

    private final SystemSettingService settingService;
    private final ResultFactory resultFactory;
    private final TimeZoneCatalogService timeZoneCatalogService;

    @GetMapping("/time/time-zones")
    public Result timeZones(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String selected
    ) {
        return resultFactory.success(timeZoneCatalogService.search(q, limit, selected));
    }

    @RequestMapping(value = "/time/time-zone", method = {RequestMethod.PUT, RequestMethod.POST})
    @LogAction(type = "基础设置修改")
    public Result updateOwnTimeZone(
            @RequestBody AccountTimeZoneRequest request,
            @AuthenticationPrincipal JwtUserPrincipal principal
    ) {
        if (principal == null || principal.getUid() == null) {
            throw new BusinessException("server.session.expired");
        }
        throw new BusinessException(403, "server.settings.systemTimeZoneOnly");
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAnyAuthority('60101','60102','super')")
    public Result settings() {
        return resultFactory.success(settingService.getSettings());
    }

    @GetMapping("/settings/cache/status")
    @PreAuthorize("hasAnyAuthority('60101','60102','super')")
    public Result cacheStatus() {
        return resultFactory.success(settingService.getSettings().getCache());
    }

    @RequestMapping(value = {"/settings", "/settings/update"}, method = {RequestMethod.PUT, RequestMethod.POST})
    @PreAuthorize("hasAnyAuthority('60102','super')")
    @LogAction(type = "基础设置修改")
    public Result updateSettings(
            @RequestBody SystemSettingsUpdateRequest request,
            @AuthenticationPrincipal JwtUserPrincipal principal
    ) {
        Integer uid = principal == null ? null : principal.getUid();
        var updated = settingService.updateSettings(request, uid);
        LoggingAspect.setLogDetails("修改系统基础设置成功", 1);
        return resultFactory.success(updated);
    }

}
