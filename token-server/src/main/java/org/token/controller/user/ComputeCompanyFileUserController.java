package org.token.controller.user;

import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.compute.ComputeCompanyFileService;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.i18n.LocaleContextHolder;
import java.util.Locale;

/** 由独立用户安全链鉴权；只返回启用公司文件，不对游客开放。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute/user/company-files")
@RequiredArgsConstructor
public class ComputeCompanyFileUserController {
    private final ComputeCompanyFileService service;
    private final ResultFactory results;

    @GetMapping
    public Result list() { return results.success(service.userList(LocaleContextHolder.getLocale().toLanguageTag().toLowerCase(Locale.ROOT))); }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<Resource> pdf(@PathVariable long id) { return service.pdf(id, false); }
}
