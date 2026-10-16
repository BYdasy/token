package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.security.JwtUserPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 信息、用户任务和公告使用同一契约，页面及类型独立。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute/admin/content")
@RequiredArgsConstructor
public class ComputeContentAdminController {
    private final org.token.service.compute.ComputeContentManagementService service;
    private final ResultFactory resultFactory;

    @GetMapping
    @PreAuthorize("(hasAnyAuthority('super')) or ((#query['kind'] == 'MESSAGE' and hasAnyAuthority('70500','70501')) or (#query['kind'] == 'TASK' and hasAnyAuthority('70600','70601')) or (#query['kind'] == 'ANNOUNCEMENT' and hasAnyAuthority('70800','70801')) or (#query['kind'] == 'ARTICLE' and hasAnyAuthority('70900','70901')))")
    public Result list(@RequestParam Map<String,String> query){
        var outcome = service.list(query);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('70500','70501','70600','70601','70800','70801','70900','70901','super')")
    public Result detail(@PathVariable long id){
        var outcome = service.detail(id);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping
    @PreAuthorize("(hasAnyAuthority('super')) or ((#body['kind'] == 'MESSAGE' and hasAnyAuthority('70501')) or (#body['kind'] == 'TASK' and hasAnyAuthority('70601')) or (#body['kind'] == 'ANNOUNCEMENT' and hasAnyAuthority('70801')) or (#body['kind'] == 'ARTICLE' and hasAnyAuthority('70901')))")
    @LogAction(type="用户内容保存")
    public Result save(@RequestBody Map<String,Object> body,@AuthenticationPrincipal JwtUserPrincipal admin){
        var outcome = service.save(body, admin);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PutMapping("/{id}/enabled")
    @PreAuthorize("hasAnyAuthority('70501','70601','70801','70901','super')")
    @LogAction(type="用户内容启停")
    public Result enabled(@PathVariable long id,@RequestBody Map<String,Object> body){
        var outcome = service.enabled(id, body);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyAuthority('70501','70601','70801','70901','super')")
    @LogAction(type="用户内容发布")
    public Result publish(@PathVariable long id){
        var outcome = service.publish(id);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/{id}/recall")
    @PreAuthorize("hasAnyAuthority('70501','70601','70801','70901','super')")
    @LogAction(type="用户内容召回")
    public Result recall(@PathVariable long id){
        var outcome = service.recall(id);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('70501','70601','70801','70901','super')")
    @LogAction(type="用户内容删除")
    public Result delete(@PathVariable long id){
        var outcome = service.delete(id);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/{id}/recipients")
    @PreAuthorize("hasAnyAuthority('70500','70501','70600','70601','super')")
    public Result recipients(@PathVariable long id,@RequestParam Map<String,String> query){
        var outcome = service.recipients(id, query);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/{id}/recipients/{memberId}/release")
    @PreAuthorize("hasAnyAuthority('70601','super')")
    @LogAction(type="解除用户任务限制")
    public Result releaseRestriction(@PathVariable long id,@PathVariable long memberId,@AuthenticationPrincipal JwtUserPrincipal admin){
        var outcome = service.releaseRestriction(id, memberId, admin);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @PostMapping("/{id}/translations")
    @PreAuthorize("hasAnyAuthority('70501','70601','70801','70901','super')")
    @LogAction(type="用户内容翻译")
    public Result translate(@PathVariable long id,@RequestBody(required=false) Map<String,Object> body,@AuthenticationPrincipal JwtUserPrincipal admin) {
        var outcome = service.translate(id, body, admin);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }

    @GetMapping("/{id}/translation-tasks/{taskId}")
    @PreAuthorize("hasAnyAuthority('70500','70501','70600','70601','70800','70801','70900','70901','super')")
    public Result translationTask(@PathVariable long id,@PathVariable long taskId,@AuthenticationPrincipal JwtUserPrincipal admin){
        var outcome = service.translationTask(id, taskId, admin);
        return resultFactory.create(outcome.code(), outcome.data(), outcome.messageKey());
    }
}
