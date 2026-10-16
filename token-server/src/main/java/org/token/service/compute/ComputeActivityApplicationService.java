package org.token.service.compute;

import org.springframework.stereotype.Service;
import org.token.service.compute.ComputeActivityService;
import org.token.service.compute.ComputeWheelService;
import org.token.model.security.JwtUserPrincipal;

import lombok.RequiredArgsConstructor;
import org.token.model.security.ComputeMemberPrincipal;
import org.token.repository.compute.ComputeData;
import org.token.custom.LoggingAspect;
import org.token.model.response.OperationOutcome;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

import java.util.Map;

/** 活动沿用数字管理权限和独立会员身份，所有归属来自服务端认证主体。 */
@Service

@RequiredArgsConstructor
public class ComputeActivityApplicationService {
    private final ComputeActivityService activities;
    private final ComputeData data;
    private final org.token.repository.compute.ComputeControllerQueryRepository repository;

    private final ComputeWheelService wheel;

    public OperationOutcome activities( Map<String, String> query) {
        String type=ComputeData.text(query.get("type"));
        if(ComputeData.enabled(query.get("wheelOptions"))&&type.isBlank())type="WHEEL";
        assertTypeAccess(type,false);
        return OperationOutcome.success(activities.adminPage(query));
    }

    public OperationOutcome save( Map<String, Object> request) {
        assertTypeAccess(ComputeData.text(request.get("type")),true);
        if(request.get("id")!=null)assertActivityAccess(ComputeData.number(request.get("id")),true);
        Map<String, Object> saved = activities.save(request);
        LoggingAspect.setLogDetails("保存算力活动，编号：" + saved.get("id"), 1);
        return OperationOutcome.success(saved);
    }

    public OperationOutcome delete( long id) {
        assertActivityAccess(id,true);
        activities.delete(id); LoggingAspect.setLogDetails("删除未参与的停用活动，编号：" + id, 1); return OperationOutcome.success();
    }

    public OperationOutcome records( Map<String, String> query) { assertTypeAccess(ComputeData.text(query.get("type")),false);return OperationOutcome.success(activities.participations(null, query)); }

    public OperationOutcome members( long id, Map<String,String> query){assertActivityAccess(id,false);return OperationOutcome.success(wheel.members(id,query));}

    public OperationOutcome adjust( long id, long memberId, Map<String,Object> request, JwtUserPrincipal admin){
        assertActivityAccess(id,true);
        Map<String,Object> result=wheel.adjust(id,memberId,request,admin.getUid());LoggingAspect.setLogDetails("调整活动次数，活动："+id+"，会员："+memberId,1);return OperationOutcome.success(result);
    }

    public OperationOutcome reset( long id){assertActivityAccess(id,true);wheel.reset(id);LoggingAspect.setLogDetails("转盘开启新轮次，保留历史："+id,1);return OperationOutcome.success();}

    public OperationOutcome redeem( long id, Map<String,Object> request, JwtUserPrincipal admin){
        String actualType = repository.participationType(id);
        assertTypeAccess(actualType,true);
        if(!"WHEEL".equals(actualType))throw new org.springframework.security.access.AccessDeniedException("该活动不使用转盘兑奖");
        String mode=ComputeData.text(request.get("mode"));Map<String,Object> result=wheel.redeem(id,mode,admin.getUid());LoggingAspect.setLogDetails("转盘兑奖，记录："+id+"，方式："+mode,1);return OperationOutcome.success(result);
    }

    public OperationOutcome statistics( Map<String,String> query){assertTypeAccess(ComputeData.text(query.get("type")),false);return OperationOutcome.success(wheel.statistics(query));}

    public OperationOutcome publicActivities( ComputeMemberPrincipal member,  Map<String, String> query) {
        requirePublicAccess(member); return OperationOutcome.success(activities.publicPage(query, member == null ? null : member.id()));
    }

    public OperationOutcome detail( ComputeMemberPrincipal member,  long id) {
        requirePublicAccess(member); return OperationOutcome.success(activities.detail(id, member == null ? null : member.id()));
    }

    public OperationOutcome participate( ComputeMemberPrincipal member,  long id,  Map<String, Object> request) {
        return OperationOutcome.success(activities.participate(uid(member), id, ComputeData.text(request.get("requestId"))));
    }

    public OperationOutcome myRecords( ComputeMemberPrincipal member,  Map<String, String> query) {
        return OperationOutcome.success(activities.participations(uid(member), query));
    }

    private void assertActivityAccess(long id,boolean write) {
        assertTypeAccess(repository.activityType(id),write);
    }
    public static void assertTypeAccess(String type,boolean write) {
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if(auth==null)throw new org.springframework.security.access.AccessDeniedException("需要活动权限");
        var codes=auth.getAuthorities().stream().map(org.springframework.security.core.GrantedAuthority::getAuthority).collect(java.util.stream.Collectors.toSet());
        if(codes.contains("super"))return;
        String leaf=switch(type){case "WHEEL"->"502";case "RED_PACKET"->"503";case "CHEST"->"504";default->"";};
        if(leaf.isEmpty())throw new org.springframework.security.access.AccessDeniedException("需要明确的活动分类");
        boolean permitted=codes.contains(leaf+"01")||(!write&&codes.contains(leaf+"00"));
        if(!permitted)throw new org.springframework.security.access.AccessDeniedException("无权访问该分类活动");
    }

    private void requirePublicAccess(ComputeMemberPrincipal member) {
        if (member == null && !ComputeData.enabled(data.setting("guestEnabled", "true"))) uid(null);
    }
    private static long uid(ComputeMemberPrincipal member) {
        if (member == null) throw new AuthenticationCredentialsNotFoundException("需要会员登录");
        return member.id();
    }
}
