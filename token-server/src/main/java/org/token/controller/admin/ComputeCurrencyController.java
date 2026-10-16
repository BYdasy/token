package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 币种权限独立声明；资产与网络配置只允许有全局范围的后台账号维护。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute")
@RequiredArgsConstructor
public class ComputeCurrencyController {
    private final org.token.service.compute.ComputeCurrencyManagementService service;
    private final ResultFactory resultFactory;

    @GetMapping("/admin/{kind:currencies|currency-assets|currency-networks}")
    @PreAuthorize("hasAnyAuthority('70400','70401','70402','super')")
    public Result page(@PathVariable String kind, @RequestParam Map<String, String> query) {
        var outcome = service.page(kind, query);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/admin/currencies/options")
    @PreAuthorize("hasAnyAuthority('70400','70401','70402','20300','20301','20302','30101','30201','super')")
    public Result options() {
        var outcome = service.options();
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/admin/{kind:currencies|currency-assets|currency-networks}")
    @PreAuthorize("hasAnyAuthority('70402','super')")
    @LogAction(type = "新增币种配置")
    public Result create(@PathVariable String kind, @RequestBody Map<String, Object> request) {
        var outcome = service.create(kind, request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PutMapping("/admin/{kind:currencies|currency-assets|currency-networks}/{id}")
    @PreAuthorize("hasAnyAuthority('70401','super')")
    @LogAction(type = "修改币种配置")
    public Result update(@PathVariable String kind, @PathVariable long id, @RequestBody Map<String, Object> request) {
        var outcome = service.update(kind, id, request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }
}
