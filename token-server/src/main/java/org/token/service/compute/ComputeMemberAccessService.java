package org.token.service.compute;

import org.token.repository.compute.ComputeMemberAccessRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.token.exception.BusinessException;
import org.token.model.security.JwtUserPrincipal;
import org.token.custom.security.PermissionCodes;
import org.token.service.compute.ComputeAgentScope;
import org.token.service.compute.ComputeCredentials;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** 管理员代用户登录只交换短期一次性票据，不在网址中暴露用户会话或后台凭据。 */
@Service
public class ComputeMemberAccessService {
    private final ComputeMemberAccessRepository repository;
    private final ComputeAgentScope scope;
    private final ComputeAccountService accounts;

    @Autowired public ComputeMemberAccessService(ComputeMemberAccessRepository repository,ComputeAgentScope scope,ComputeAccountService accounts){this.repository=repository;this.scope=scope;this.accounts=accounts;}
    public ComputeMemberAccessService(JdbcTemplate jdbc,ComputeAgentScope scope,ComputeAccountService accounts){this(new ComputeMemberAccessRepository(jdbc),scope,accounts);}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> issue(long memberId) {
        scope.relations().lock();
        scope.assertActiveMember(memberId);
        String memberHash = repository.requireMemberHash(memberId);
        int adminUid = scope.currentAdminUid();
        String adminHash = repository.adminHash(adminUid);
        String ticket = ComputeCredentials.randomToken();
        LocalDateTime expiry = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(60);
        repository.deleteExpiredTickets();
        repository.insertTicket(ComputeCredentials.digest(ticket),memberId,adminUid,adminHash,memberHash,expiry);
        return Map.of("ticket",ticket,"expiresAt",expiry.toInstant(ZoneOffset.UTC).toString());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> exchange(String ticket) {
        if(ticket==null || !ticket.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        scope.relations().lock();
        String digest=ComputeCredentials.digest(ticket);
        List<Map<String,Object>> tickets=repository.tickets(digest);
        if(tickets.size()!=1)throw invalid();
        Map<String,Object> value=tickets.get(0);
        long memberId=((Number)value.get("member_id")).longValue();
        List<String> hashes=repository.lockMemberHashes(memberId);
        if(hashes.size()!=1 || !Objects.equals(hashes.get(0),value.get("member_credential_hash")))throw invalid();
        List<Map<String,Object>> admins=repository.admins(value.get("admin_uid"));
        if(admins.size()!=1 || !Objects.equals(admins.get(0).get("password"),value.get("admin_credential_hash")))throw invalid();
        Map<String,Object> admin=admins.get(0);
        Set<String> codes=new LinkedHashSet<>(PermissionCodes.fromRole(Objects.toString(admin.get("role"),"")));
        if("1".equals(String.valueOf(admin.get("is_super"))) || Boolean.TRUE.equals(admin.get("is_super")))codes.add("super");
        if(Collections.disjoint(codes,Set.of("20105","super")))throw invalid();
        // 兑换时重新计算授权行范围，旧票据不能跨越后来修改的账号绑定或邀请关系。
        var previous=SecurityContextHolder.getContext().getAuthentication();
        try {
            var principal=JwtUserPrincipal.builder().uid(((Number)admin.get("uid")).intValue()).username(Objects.toString(admin.get("username"),"")).authorities(codes).build();
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,codes.stream().map(SimpleGrantedAuthority::new).toList()));
            scope.assertActiveMember(memberId);
        } finally { SecurityContextHolder.getContext().setAuthentication(previous); }
        if(repository.consumeTicket(digest)!=1)throw invalid();
        String token=accounts.issueSession(memberId,hashes.get(0));
        return Map.of("token",token,"member",accounts.member(memberId));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int forceLogout(long memberId) {
        scope.relations().lock();scope.assertMember(memberId);
        repository.lockMember(memberId);
        repository.deleteTickets(memberId);
        return repository.deleteSessions(memberId);
    }

    public List<Map<String,Object>> ancestors(long memberId) {
        scope.assertMember(memberId);
        List<Map<String,Object>> chain=new ArrayList<>(scope.relations().ancestors(memberId));
        Collections.reverse(chain);chain.remove(0);
        // 用户已在授权范围内，链路只展示关系定位所需用户名和层级，不扩展范围外用户的管理权限或资料。
        return chain.stream().map(row->Map.<String,Object>of("username",Objects.toString(row.get("username"),""),"depth",row.get("depth"))).toList();
    }

    private static BusinessException invalid(){return new BusinessException(401,"server.compute.v8.impersonationExpired");}
}
