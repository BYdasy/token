package org.token.service.compute;

import org.springframework.stereotype.Service;
import org.token.service.compute.ComputeContentService;
import org.token.service.compute.ComputeContentTranslationService;

import lombok.RequiredArgsConstructor;
import org.token.custom.LoggingAspect;
import org.token.model.response.OperationOutcome;
import org.token.model.security.JwtUserPrincipal;
import java.util.Map;

/** 信息、用户任务和公告使用同一契约，页面及类型独立。 */
@Service

@RequiredArgsConstructor
public class ComputeContentManagementService {
    private final ComputeContentService service;
    private final ComputeContentTranslationService translation;

    public OperationOutcome list( Map<String,String> query){return OperationOutcome.success(service.adminList(query));}

    public OperationOutcome detail( long id){var row=service.adminDetail(id);requireKind(row,false);return OperationOutcome.success(row);}

    public OperationOutcome save( Map<String,Object> body, JwtUserPrincipal admin){var value=service.save(body,admin.getUid());LoggingAspect.setLogDetails("保存用户内容，编号："+value.get("id"),1);return OperationOutcome.success(value);}

    public OperationOutcome enabled( long id, Map<String,Object> body){
        requireKind(service.adminDetail(id),true);
        if(!(body.get("enabled") instanceof Boolean active))throw new org.token.exception.BusinessException("server.compute.content.invalid");
        var value=service.setEnabled(id,active);LoggingAspect.setLogDetails((active?"启用":"停用")+"用户内容，编号："+id,1);return OperationOutcome.success(value);
    }

    public OperationOutcome publish( long id){requireKind(service.adminDetail(id),true);var value=service.publish(id);LoggingAspect.setLogDetails("提交用户内容发布，编号："+id,1);return OperationOutcome.success(value);}

    public OperationOutcome recall( long id){requireKind(service.adminDetail(id),true);service.recall(id);LoggingAspect.setLogDetails("召回用户内容，编号："+id,1);return OperationOutcome.success();}

    public OperationOutcome delete( long id){requireKind(service.adminDetail(id),true);service.delete(id);LoggingAspect.setLogDetails("删除已停用用户内容，编号："+id,1);return OperationOutcome.success();}

    public OperationOutcome recipients( long id, Map<String,String> query){requireKind(service.adminDetail(id),false);return OperationOutcome.success(service.recipients(id,query));}

    public OperationOutcome releaseRestriction( long id, long memberId, JwtUserPrincipal admin){
        requireKind(service.adminDetail(id),true);
        service.releaseRestriction(id,memberId,admin.getUid());
        LoggingAspect.setLogDetails("解除用户任务限制，内容编号："+id+"，用户编号："+memberId,1);
        return OperationOutcome.success();
    }

    public OperationOutcome translate( long id, Map<String,Object> body, JwtUserPrincipal admin) {
        requireKind(service.adminDetail(id),true);
        var value=translation.submitSavedForm("CONTENT",id,body==null?null:body.get("targetLanguages"),admin.getUid());
        LoggingAspect.setLogDetails("提交用户内容补译，编号："+id+"，任务："+value.get("taskId"),1);
        return OperationOutcome.success(value);
    }

    public OperationOutcome translationTask( long id, long taskId, JwtUserPrincipal admin){requireKind(service.adminDetail(id),false);return OperationOutcome.success(translation.formTask("CONTENT",taskId,admin.getUid(),id));}

    /** ID 入口必须按持久分类复核，不能用请求中声称的分类越权访问其他内容。分类保存后不可变。 */
    private void requireKind(Map<String,Object> row, boolean write) {
        var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        var codes=authentication==null?java.util.Set.<String>of():authentication.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority).collect(java.util.stream.Collectors.toSet());
        if(codes.contains("super"))return;
        String base=switch(String.valueOf(row.get("kind"))){case "MESSAGE"->"705";case "TASK"->"706";case "ANNOUNCEMENT"->"708";case "ARTICLE"->"709";default->"";};
        if(!base.isEmpty()&&(codes.contains(base+"01")||(!write&&codes.contains(base+"00"))))return;
        throw new org.springframework.security.access.AccessDeniedException("无权操作该分类内容");
    }
}
