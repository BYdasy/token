package org.token.controller.user;

import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.TimeZoneCatalogService;
import org.token.model.security.ComputeMemberPrincipal;
import org.token.service.compute.ComputeUserTimeZoneService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 复用 IANA 搜索目录，所有接口均经过用户登录安全链。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute/user/time-zone")
@RequiredArgsConstructor
public class ComputeUserTimeZoneController {
    private final ComputeUserTimeZoneService service;
    private final TimeZoneCatalogService catalog;
    private final ResultFactory results;

    @GetMapping("/options")
    public Result options(@RequestParam(required=false) String q, @RequestParam(required=false) String selected) {
        return results.success(catalog.search(q, 24, selected));
    }
    @PutMapping
    public Result save(@AuthenticationPrincipal ComputeMemberPrincipal member, @RequestBody org.token.model.request.compute.UserTimeZoneRequest request) {
        return results.success(service.save(member.id(), request.timeZone()));
    }
}
