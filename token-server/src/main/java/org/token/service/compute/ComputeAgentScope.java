package org.token.service.compute;

import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeAgentScopeRepository;
import org.token.model.security.JwtUserPrincipal;
import org.token.service.compute.ComputeMemberRelations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.*;
import static org.token.repository.compute.ComputeData.fields;

/** 数字权限决定操作能力，本类只按实时邀请链限制行范围；绑定和身份状态均不缓存。 */
@Service
public class ComputeAgentScope {
    private final ComputeAgentScopeRepository repository;
    private final ComputeMemberRelations relations;
    public record Scope(boolean global,List<Long> memberIds,boolean enabled) {}
    @Autowired
    public ComputeAgentScope(ComputeAgentScopeRepository repository,ComputeMemberRelations relations){this.repository=repository;this.relations=relations;}
    public ComputeAgentScope(JdbcTemplate jdbc,ComputeMemberRelations relations){this(new ComputeAgentScopeRepository(jdbc),relations);}
    public ComputeAgentScope(JdbcTemplate jdbc){this(jdbc,new ComputeMemberRelations(jdbc));}

    public ComputeMemberRelations relations(){return relations;}
    public int currentAdminUid(){
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!auth.isAuthenticated()||!(auth.getPrincipal() instanceof JwtUserPrincipal principal))throw denied();
        return principal.getUid();
    }
    public Scope current(){
        int uid=currentAdminUid();
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth.getAuthorities().stream().anyMatch(value->"super".equals(value.getAuthority())))return new Scope(true,List.of(),true);
        List<Long> roots=repository.roots(uid);
        if(!roots.isEmpty()){
            List<Long> active=repository.activeRoots(roots);
            return new Scope(false,active,!active.isEmpty());
        }
        boolean configured=repository.configured(uid);
        return new Scope(!configured,List.of(),!configured);
    }
    public org.token.model.query.MemberScope queryScope(){
        Scope scope=current();
        return new org.token.model.query.MemberScope(scope.global(),scope.enabled()?scope.memberIds():List.of());
    }
    public boolean isGlobalScope(){return current().global();}
    public boolean isGlobalAdmin(){return isGlobalScope();}
    public void requireGlobalScope(){if(!current().global())throw denied();}
    public void requireGlobalAdmin(){requireGlobalScope();}
    public Long currentRootMemberId(){var scope=current();return scope.memberIds().isEmpty()?null:scope.memberIds().get(0);}

    public void assertMember(long id){
        Scope scope=current();
        if(!repository.contains(id,scope.global(),scope.enabled(),scope.memberIds()))throw denied();
    }

    public void assertOwner(Long owner){
        if(owner==null){requireGlobalScope();return;}assertMember(owner);
    }
    public void assertActiveMember(long id){
        assertMember(id);
        if(!repository.activeMember(id))throw new BusinessException("server.compute.management.inviterUnavailable");
    }
    /** 匿名注册只接受真实用户的邀请码；缺省邀请码表示没有邀请人。 */
    public Long resolveInviter(String inviteCode){
        if(inviteCode==null||inviteCode.isBlank())return null;
        var invitation = repository.invitationEnabled();
        if (!invitation.isEmpty() && !Boolean.parseBoolean(invitation.get(0))) throw new BusinessException("server.compute.v11.invitationDisabled");
        List<Long> ids=repository.inviter(inviteCode.trim());
        if(ids.size()!=1)throw new BusinessException("server.compute.invalidInviteCode");
        return ids.get(0);
    }
    public Map<String,Object> description(){
        Scope scope=current();
        return fields("global",scope.global(),"canManageGlobal",scope.global(),"memberIds",scope.memberIds(),"rootMemberIds",scope.memberIds(),
                "enabled",scope.enabled());
    }
    public static BusinessException denied(){return new BusinessException(403,"server.compute.management.outOfScope");}
}
