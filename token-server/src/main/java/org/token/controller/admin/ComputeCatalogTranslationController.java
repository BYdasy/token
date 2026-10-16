package org.token.controller.admin;

import lombok.RequiredArgsConstructor;
import org.token.custom.LogAction;
import org.token.custom.LoggingAspect;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.model.security.JwtUserPrincipal;
import org.token.service.compute.ComputeContentTranslationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 产品与会员等级复用内容补译任务，供应商处理不占用HTTP请求。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute/admin/catalog")
@RequiredArgsConstructor
public class ComputeCatalogTranslationController {
    private final ComputeContentTranslationService translation;
    private final ResultFactory results;
    @PostMapping("/{resource:products|levels}/{id}/translations")
    @PreAuthorize("(hasAnyAuthority('super')) or ((#resource == 'products' and hasAnyAuthority('70101')) or (#resource == 'levels' and hasAnyAuthority('70201')))")
    @LogAction(type="产品等级翻译")
    public Result submit(@PathVariable String resource,@PathVariable long id,@RequestBody(required=false) Map<String,Object> body,@AuthenticationPrincipal JwtUserPrincipal principal){
        var result=translation.submitSavedForm(resource,id,body==null?null:body.get("targetLanguages"),principal.getUid());
        LoggingAspect.setLogDetails("提交产品等级翻译，模块："+resource+"，编号："+id+"，任务："+result.get("taskId"),1);return results.success(result);
    }
    @GetMapping("/{resource:products|levels}/{id}/translation-tasks/{taskId}")
    @PreAuthorize("(hasAnyAuthority('super')) or ((#resource == 'products' and hasAnyAuthority('70100','70101')) or (#resource == 'levels' and hasAnyAuthority('70200','70201')))")
    public Result progress(@PathVariable String resource,@PathVariable long id,@PathVariable long taskId,@AuthenticationPrincipal JwtUserPrincipal principal){return results.success(translation.formTask(resource,taskId,principal.getUid(),id));}
}
