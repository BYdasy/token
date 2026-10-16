package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 全局审核阈值复用费率查看/编辑权限和全局数据范围。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute/admin/fee-rules/withdrawal-review")
@RequiredArgsConstructor
public class ComputeWithdrawalReviewController {
    private final org.token.service.compute.ComputeWithdrawalSettingsService service;
    private final ResultFactory results;
    @GetMapping
    @PreAuthorize("hasAnyAuthority('61400','61401','super')")
    public Result read() { return results.success(service.review()); }
    @PutMapping
    @LogAction(type="提款审核阈值配置")
    @PreAuthorize("hasAnyAuthority('61401','super')")
    public Result save(@RequestBody Map<String,Object> body) { return results.success(service.saveReview(body)); }
}
