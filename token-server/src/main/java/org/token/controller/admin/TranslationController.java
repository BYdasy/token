package org.token.controller.admin;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.model.entity.Language;
import org.token.model.entity.Translation;
import org.token.model.request.translation.TemplateCompletionRequest;
import org.token.model.request.QueryParams;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.security.JwtUserPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 翻译、模板和语言配置管理接口。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TranslationController {
    private final org.token.service.system.TranslationManagementService service;
    private final ResultFactory resultFactory;

    @PostMapping("/translates/tem")
    @PreAuthorize("hasAnyAuthority('6060101','6060201','6060301','6060401','6060501','super')")
    public Result getTem() {
        var outcome = service.getTem();
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/translates/tem/add")
    @PreAuthorize("hasAnyAuthority('6060102','super')")
    @LogAction(type = "模板新增")
    public Result addTem(@RequestBody Translation translates) {
        var outcome = service.addTem(translates);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/translates/tem/update")
    @PreAuthorize("hasAnyAuthority('6060103','super')")
    @LogAction(type = "模板修改")
    public Result updateTem(@RequestBody Translation translates) {
        var outcome = service.updateTem(translates);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/translates/tem/delete")
    @PreAuthorize("hasAnyAuthority('6060104','super')")
    @LogAction(type = "模板删除")

    public Result deleteTem(@RequestBody JsonNode body) {
        var outcome = service.deleteTem(body);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/translates/list")
    @PreAuthorize("hasAnyAuthority('6060201','6060301','6060401','super')")
    public Result getTranslation(@RequestBody(required = false) QueryParams params) {
        var outcome = service.getTranslation(params);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/translates/language/page")
    @PreAuthorize("hasAnyAuthority('6060501','super')")
    public Result languagePage(@RequestBody(required = false) QueryParams params) {
        var outcome = service.languagePage(params);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/translates/language/options")
    @PreAuthorize("hasAnyAuthority('6060101','6060201','6060301','6060401','6060501','super')")
    public Result languageOptions(@RequestBody(required = false) Map<String, Object> body) {
        var outcome = service.languageOptions(body);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/translates/language/update")
    @PreAuthorize("hasAnyAuthority('6060502','super')")
    @LogAction(type = "更新多语言配置")
    public Result updateLanguage(@RequestBody Language request) {
        var outcome = service.updateLanguage(request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/translates/delete")
    @PreAuthorize("hasAnyAuthority('6060204','super')")
    @LogAction(type = "翻译删除")

    public Result removeTranslation(@RequestBody Translation translates) {
        var outcome = service.removeTranslation(translates);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/translates/getByKeyBatch")
    @PreAuthorize("hasAnyAuthority('6060201','6060301','6060401','super')")
    public Result getByKeyBatch(@RequestBody Translation translates) {
        var outcome = service.getByKeyBatch(translates);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/translates/translateToAllLanguages")
    @LogAction(type = "多语言翻译预览")
    @PreAuthorize("hasAnyAuthority('6060205','6060303','6060403','super')")
    public Result translateToAllLanguages(@RequestBody Translation translates) throws InterruptedException {
        var outcome = service.translateToAllLanguages(translates);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/translates/template/completion/overview")
    @PreAuthorize("hasAnyAuthority('6060105','super')")
    public Result templateCompletionOverview(@RequestBody TemplateCompletionRequest request) {
        var outcome = service.templateCompletionOverview(request);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/translates/template/fill-missing")
    @PreAuthorize("hasAnyAuthority('6060105','super')")
    @LogAction(type = "模板翻译补齐")
    public Result fillMissingTemplateLanguages(
            @RequestBody TemplateCompletionRequest request,
            @AuthenticationPrincipal JwtUserPrincipal principal
    ) {
        var outcome = service.fillMissingTemplateLanguages(request, principal);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/translates/template/fill-missing/task/{taskId}")
    @PreAuthorize("hasAnyAuthority('6060105','super')")
    public Result templateCompletionTask(
            @PathVariable Long taskId,
            @AuthenticationPrincipal JwtUserPrincipal principal
    ) {
        var outcome = service.templateCompletionTask(taskId, principal);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/translatesBatch/translateAdd")
    @PreAuthorize("hasAnyAuthority('6060202','6060302','6060402','super')")
    @LogAction(type = "批量翻译新增")

    public Result translateAndAddBatch(@RequestBody Translation translates) throws InterruptedException {
        var outcome = service.translateAndAddBatch(translates);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/translatesBatch/update")
    @PreAuthorize("hasAnyAuthority('6060203','6060303','6060403','super')")
    @LogAction(type = "批量翻译修改")

    public Result updateTranslationBatch(@RequestBody Translation translates) {
        var outcome = service.updateTranslationBatch(translates);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }
}
