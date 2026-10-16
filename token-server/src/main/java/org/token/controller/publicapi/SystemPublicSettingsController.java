package org.token.controller.publicapi;

import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.security.JwtUserPrincipal;
import org.token.service.SystemSettingService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 网站基础设置与服务端展示时间接口。 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemPublicSettingsController {

    private final SystemSettingService settingService;
    private final ResultFactory resultFactory;

    @GetMapping("/settings/public")
    public Result publicSettings() {
        return resultFactory.success(settingService.getPublicSettings());
    }

    @GetMapping("/time")
    public Result serverTime(@AuthenticationPrincipal JwtUserPrincipal principal) {
        return resultFactory.success(settingService.getServerTime());
    }

}
