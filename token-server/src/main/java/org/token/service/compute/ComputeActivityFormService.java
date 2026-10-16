package org.token.service.compute;

import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import org.token.custom.LoggingAspect;
import org.token.model.response.OperationOutcome;
import org.token.model.security.JwtUserPrincipal;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeContentTranslationService;
import java.util.*;

/** 活动翻译仅修改独立表单草稿；读取按真实活动分类和原管理员复核。 */
@Service

@RequiredArgsConstructor
public class ComputeActivityFormService {
    private final ComputeContentTranslationService translations;
    private final org.token.repository.compute.ComputeControllerQueryRepository repository;

    public OperationOutcome submit( Map<String,Object> request, JwtUserPrincipal admin) {
        Map<String,Object> body=new LinkedHashMap<>(request);
        String type=ComputeData.text(request.get("type"));
        if(request.get("sourceId")!=null)type=repository.activityType(ComputeData.number(request.get("sourceId")));
        ComputeActivityApplicationService.assertTypeAccess(type,true);
        body.put("type",type);body.put("bodyHtml",request.get("content"));
        Map<String,Object> incoming=new LinkedHashMap<>();
        if(request.get("translations") instanceof Map<?,?> values)values.forEach((language,value)->{
            if(value instanceof Map<?,?> fields)incoming.put(String.valueOf(language),ComputeData.fields("title",fields.get("title"),"bodyHtml",fields.get("content"),"description",fields.get("description")));
        });
        body.put("translations",incoming);
        Map<String,Object> task=translations.submitForm("ACTIVITY",body,admin.getUid());
        LoggingAspect.setLogDetails("提交活动翻译，分类："+type+"，任务："+task.get("taskId"),1);
        return OperationOutcome.success(view(task));
    }

    public OperationOutcome progress( long taskId, JwtUserPrincipal admin) {
        ComputeActivityApplicationService.assertTypeAccess(translations.formActivityType(taskId,admin.getUid()),false);
        return OperationOutcome.success(view(translations.formTask("ACTIVITY",taskId,admin.getUid())));
    }
    private Map<String,Object> view(Map<String,Object> task) {
        Map<String,Object> output=new LinkedHashMap<>(task),converted=new LinkedHashMap<>();
        if(task.get("translations") instanceof Map<?,?> values)values.forEach((language,value)->{
            if(value instanceof Map<?,?> fields)converted.put(String.valueOf(language),ComputeData.fields("title",fields.get("title"),"description",ComputeData.text(fields.get("description")),"content",ComputeData.text(fields.get("bodyHtml"))));
        });
        output.put("translations",converted);return output;
    }
}
