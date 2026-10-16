package org.token.controller.publicapi;

import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.compute.ComputeCurrencyService;
import org.springframework.web.bind.annotation.*;

/** 币种权限独立声明；资产与网络配置只允许有全局范围的后台账号维护。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute")
@RequiredArgsConstructor
public class ComputeCurrencyPublicController {
    private final ComputeCurrencyService service;

    private final ResultFactory results;

    @GetMapping("/public/currencies")
    public Result publicChannels() { return results.success(service.publicChannels()); }

}
