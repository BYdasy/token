package org.token.service.compute;

import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import org.token.model.response.OperationOutcome;
import org.token.model.security.JwtUserPrincipal;
import org.token.service.compute.ComputeContentTranslationService;
import java.util.Map;

/** 当前表单的异步翻译预览，结果不修改业务内容，查询严格绑定提交账号。 */
@Service

@RequiredArgsConstructor
public class ComputeFormTranslationService {
    private final ComputeContentTranslationService translation;
    private final org.token.service.compute.ComputeContentService contentService;

    public OperationOutcome content( Map<String,Object> body, JwtUserPrincipal admin){
        if(body.get("sourceId")!=null){
            var row=contentService.adminDetail(org.token.repository.compute.ComputeData.number(body.get("sourceId")));
            var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            boolean superAdmin=authentication!=null&&authentication.getAuthorities().stream().anyMatch(a->java.util.Set.of("super").contains(a.getAuthority()));
            if(!superAdmin&&!java.util.Objects.equals(row.get("kind"),body.get("kind")))throw new org.springframework.security.access.AccessDeniedException("内容分类与翻译源记录不一致");
        }
        return OperationOutcome.success(translation.submitForm("CONTENT",body,admin.getUid()));
    }

    public OperationOutcome contentTask( long taskId, JwtUserPrincipal admin){
        String kind=translation.formContentKind(taskId,admin.getUid());
        var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        var codes=authentication==null?java.util.Set.<String>of():authentication.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority).collect(java.util.stream.Collectors.toSet());
        boolean superAdmin=codes.contains("super");
        String required=switch(kind){case "MESSAGE"->"70501";case "TASK"->"70601";case "ANNOUNCEMENT"->"70801";case "ARTICLE"->"70901";default->"";};
        if(!superAdmin&&(required.isEmpty()||!codes.contains(required)))throw new org.springframework.security.access.AccessDeniedException("无权读取该分类的翻译草稿");
        return OperationOutcome.success(translation.formTask("CONTENT",taskId,admin.getUid()));
    }

    public OperationOutcome catalog( String resource, Map<String,Object> body, JwtUserPrincipal admin){return OperationOutcome.success(translation.submitForm(resource,body,admin.getUid()));}

    public OperationOutcome catalogTask( String resource, long taskId, JwtUserPrincipal admin){return OperationOutcome.success(translation.formTask(resource,taskId,admin.getUid()));}

    public OperationOutcome companyFile( Map<String,Object> body, JwtUserPrincipal admin){
        return OperationOutcome.success(translation.submitForm("COMPANY_FILE",body,admin.getUid()));
    }

    public OperationOutcome companyFileTask( long taskId, JwtUserPrincipal admin){
        return OperationOutcome.success(translation.formTask("COMPANY_FILE",taskId,admin.getUid()));
    }
}
