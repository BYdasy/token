package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.compute.ComputeAnalyticsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute/admin")
@RequiredArgsConstructor
public class ComputeAnalyticsController {
    private final ComputeAnalyticsService analytics;
    private final ResultFactory results;
    @GetMapping("/analytics")
    @PreAuthorize("hasAnyAuthority('10100','super')")
    public Result overview(){return results.success(analytics.dashboardReport());}

    @GetMapping("/reports/global")
    @PreAuthorize("hasAnyAuthority('40300','super')")
    public Result globalReport(@RequestParam Map<String,String> query){return results.success(analytics.globalReport(query));}
}
