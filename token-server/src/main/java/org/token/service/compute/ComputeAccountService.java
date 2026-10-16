package org.token.service.compute;

import org.token.model.request.compute.ComputeAuthRequests;
import org.token.model.request.compute.ComputeMemberCreateRequest;
import org.token.model.response.compute.ComputeMemberCreated;
import org.token.service.compute.ComputeCredentials;

import lombok.RequiredArgsConstructor;
import org.token.model.security.ComputeMemberPrincipal;
import org.token.service.compute.ComputeBusinessService;
import org.token.service.compute.ComputeAgentScope;
import org.token.exception.BusinessException;
import org.token.util.PasswordUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.token.repository.compute.ComputeAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 独立会员账户、短期会话与人工核验的找回流程。 */
@Service
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class ComputeAccountService {
    private final ComputeAccountRepository repository;
    private final PasswordUtils passwords;
    private final ComputeBusinessService business;
    private final ComputeAgentScope agentScope;
    private final ComputeSystemMessageService systemMessages;
    private final ComputeVipPeriodService vipPeriods;
    private final ComputeLevelNames levelNames;
    private final org.token.service.auth.LoginSessionPolicy loginSessionPolicy;
    private final ComputeInviteCodeService inviteCodes;
    private final ComputeMemberUidAllocator memberUids;

    public ComputeAccountService(JdbcTemplate jdbc, PasswordUtils passwords, ComputeBusinessService business, ComputeAgentScope agentScope, ComputeSystemMessageService systemMessages, ComputeVipPeriodService vipPeriods, ComputeLevelNames levelNames, org.token.service.auth.LoginSessionPolicy loginSessionPolicy, ComputeInviteCodeService inviteCodes, ComputeMemberUidAllocator memberUids) {
        this(new ComputeAccountRepository(jdbc),passwords,business,agentScope,systemMessages,vipPeriods,levelNames,loginSessionPolicy,inviteCodes,memberUids);
    }

    public ComputeAccountService(JdbcTemplate jdbc, PasswordUtils passwords, ComputeBusinessService business, ComputeAgentScope agentScope, ComputeSystemMessageService systemMessages, ComputeVipPeriodService vipPeriods, ComputeLevelNames levelNames, org.token.service.auth.LoginSessionPolicy loginSessionPolicy, ComputeInviteCodeService inviteCodes) {
        this(jdbc, passwords, business, agentScope, systemMessages, vipPeriods, levelNames, loginSessionPolicy, inviteCodes, new ComputeMemberUidAllocator(jdbc));
    }

    public ComputeAccountService(JdbcTemplate jdbc, PasswordUtils passwords, ComputeBusinessService business, ComputeAgentScope agentScope, ComputeSystemMessageService systemMessages, ComputeVipPeriodService vipPeriods, ComputeLevelNames levelNames, org.token.service.auth.LoginSessionPolicy loginSessionPolicy) {
        this(jdbc, passwords, business, agentScope, systemMessages, vipPeriods, levelNames, loginSessionPolicy, new ComputeInviteCodeService(jdbc, agentScope));
    }

    public ComputeAccountService(JdbcTemplate jdbc, PasswordUtils passwords, ComputeBusinessService business, ComputeAgentScope agentScope, ComputeSystemMessageService systemMessages, ComputeVipPeriodService vipPeriods, ComputeLevelNames levelNames) {
        this(jdbc, passwords, business, agentScope, systemMessages, vipPeriods, levelNames, null);
    }

    public ComputeAccountService(JdbcTemplate jdbc, PasswordUtils passwords, ComputeBusinessService business, ComputeAgentScope agentScope, ComputeSystemMessageService systemMessages, ComputeVipPeriodService vipPeriods) {
        this(jdbc, passwords, business, agentScope, systemMessages, vipPeriods, null);
    }

    public ComputeAccountService(JdbcTemplate jdbc, PasswordUtils passwords, ComputeBusinessService business, ComputeAgentScope agentScope, ComputeSystemMessageService systemMessages) {
        this(jdbc, passwords, business, agentScope, systemMessages, null);
    }

    /** 兼容旧领域测试的显式构造；Spring使用包含系统消息服务的完整构造器。 */
    public ComputeAccountService(JdbcTemplate jdbc, PasswordUtils passwords, ComputeBusinessService business, ComputeAgentScope agentScope) {
        this(jdbc, passwords, business, agentScope, null);
    }

    public Map<String, String> settings() { return repository.settings(); }

    public Map<String, Object> member(long id) {
        List<Map<String, Object>> rows = repository.member(id);
        if (rows.isEmpty()) throw new BusinessException(401, "server.compute.session.expired");
        if (levelNames != null) levelNames.decorate(rows, "level", "levelName");
        return rows.get(0);
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String, Object> register(ComputeAuthRequests.Register input) {
        Map<String, String> config = settings();
        Map<String, String> clean = validatedIdentity(input, config);
        if (Boolean.parseBoolean(config.getOrDefault("invitationEnabled", "true")) && Boolean.parseBoolean(config.getOrDefault("inviteCodeRequired", "false"))
                && (input.inviteCode() == null || input.inviteCode().isBlank()))
            throw new BusinessException("server.compute.invite.required");
        String hash = passwords.hashPassword(input.password());
        agentScope.relations().lock();
        boolean limitCharacters = memberUids.usernameLimitForWrite();
        if (clean.get("username") != null) validateNewUsername(clean.get("username"), 3, limitCharacters, input.username().trim());
        Long inviterId = agentScope.resolveInviter(input.inviteCode());
        if (inviterId != null && agentScope.relations().ancestors(inviterId).size() > ComputeMemberRelations.MAX_DEPTH)
            throw new BusinessException("server.compute.relation.depthLimit");
        long id = insertMember(clean, hash, inviterId, null, 1, true);
        if (vipPeriods != null) vipPeriods.initialize(id, 1);
        business.initializeMember(id);
        if (systemMessages != null) systemMessages.welcome(id);
        agentScope.relations().changed();
        String token = issueSession(id, hash);
        return Map.of("token", token, "member", member(id));
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ComputeMemberCreated createFromAdmin(ComputeMemberCreateRequest input) {
        ComputeAgentScope.Scope currentScope = agentScope.current();
        if (!currentScope.enabled()) throw ComputeAgentScope.denied();
        Map<String, String> clean = validatedIdentity(new ComputeAuthRequests.Register(input.username(), input.email(), input.phone(), input.password(), input.inviteCode()),
                Map.of("usernameEnabled","true","usernameRequired","true","emailEnabled","true","emailRequired","false","phoneEnabled","true","phoneRequired","false",
                        ComputeMemberUidAllocator.USERNAME_LIMIT, settings().getOrDefault(ComputeMemberUidAllocator.USERNAME_LIMIT, "true")));
        String hash = passwords.hashPassword(input.password());
        agentScope.relations().lock();
        validateNewUsername(clean.get("username"), 3, memberUids.usernameLimitForWrite(), input.username().trim());
        Long inviterId = agentScope.resolveInviter(input.inviteCode());
        int depth = 0;
        if (inviterId != null) {
            agentScope.assertActiveMember(inviterId);
            depth = agentScope.relations().ancestors(inviterId).size();
            if (depth > ComputeMemberRelations.MAX_DEPTH) throw new BusinessException("server.compute.relation.depthLimit");
        } else agentScope.requireGlobalScope();
        boolean enabled = input.enabled() == null || input.enabled();
        long id = insertMember(clean, hash, inviterId, null, 1, enabled);
        if (vipPeriods != null) vipPeriods.initialize(id, 1);
        if (Boolean.TRUE.equals(input.isTest())) repository.markTest(id);
        business.initializeWallets(id);
        if (systemMessages != null) systemMessages.welcome(id);
        agentScope.relations().changed();
        Map<String,Object> created=repository.creationInfo(id);
        return new ComputeMemberCreated(id,clean.get("username"),clean.get("email"),clean.get("phone"),1,enabled,
                (String)created.get("inviteCode"),inviterId,null,depth,(String)created.get("createdAt"));
    }

    private Map<String, String> validatedIdentity(ComputeAuthRequests.Register input, Map<String, String> config) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("username", input.username());
        fields.put("email", input.email());
        fields.put("phone", input.phone());
        Map<String, String> clean = new LinkedHashMap<>();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            String key = field.getKey();
            String value = field.getValue() == null || field.getValue().isBlank() ? null : field.getValue().trim();
            boolean enabled = Boolean.parseBoolean(config.getOrDefault(key + "Enabled", "true"));
            boolean required = Boolean.parseBoolean(config.getOrDefault(key + "Required", "username".equals(key) ? "true" : "false"));
            if ((!enabled && value != null) || (enabled && required && value == null)) {
                throw new BusinessException("server.compute.account.fields");
            }
            if (value != null && "username".equals(key)) validateNewUsername(value, 3, ComputeMemberUidAllocator.usernameLimit(config), value);
            if (value != null && "phone".equals(key) && !value.matches("\\+?[0-9]{6,20}")) {
                throw new BusinessException("server.compute.account.invalid");
            }
            if (value != null && "email".equals(key) && (value.length() > 120 || !value.matches("[^\\s@]+@[^\\s@]+")))
                throw new BusinessException("server.compute.account.invalid");
            clean.put(key, value == null ? null : value.toLowerCase(Locale.ROOT));
        }
        if (clean.values().stream().allMatch(value -> value == null)) throw new BusinessException("server.compute.account.fields");
        ComputeCredentials.validatePassword(input.password());
        return clean;
    }

    /** 只约束新写入；旧用户名的登录和找回继续沿用原规范化与数据库比较规则。 */
    private static void validateNewUsername(String value, int minimumLength, boolean limitCharacters, String original) {
        if (value.length() < minimumLength || value.length() > 40)
            throw new BusinessException("server.compute.account.invalid");
        // 必须先检查原字符再转小写，防止部分Unicode字符转小写后变为ASCII而绕过限制。
        if (limitCharacters && !original.matches("[A-Za-z0-9_-]+"))
            throw new BusinessException("server.compute.username.asciiRule");
    }

    private Long memberInviter(String inviteCode) {
        if (inviteCode == null || inviteCode.isBlank()) return null;
        if (inviteCode.length() > 32) throw new BusinessException("server.compute.invite.invalid");
        List<Long> ids = repository.memberInviter(inviteCode.trim());
        return ids.isEmpty() ? null : ids.get(0);
    }

    private long insertMember(Map<String, String> clean, String hash, Long inviterId, Long groupId, int level, boolean enabled) {
        String invite = inviteCodes.nextCode();
        Long assignedId = memberUids.nextId();
        long memberId;
        try {
            memberId=repository.insertMember(assignedId,clean,hash,level,enabled,invite,inviterId,groupId,LocalDateTime.now(ZoneOffset.UTC));
        } catch (DuplicateKeyException ex) { throw new BusinessException("server.compute.account.exists"); }
        memberUids.recordAssigned(memberId);
        inviteCodes.recordCreated(memberId, invite);
        return memberId;
    }

    @Transactional
    public Map<String, Object> login(String method, ComputeAuthRequests.Login input) {
        if (!List.of("username", "email", "phone").contains(method)) throw new BusinessException("server.compute.account.invalid");
        if (!Boolean.parseBoolean(settings().getOrDefault(method + "Enabled", "true"))) throw new BusinessException("server.compute.feature.disabled");
        // 列名只能来自上面的固定白名单，不接受调用方提供任意 SQL 标识符。
        List<Map<String, Object>> rows = repository.login(method,input.account().trim().toLowerCase(Locale.ROOT));
        if (rows.isEmpty()) {
            passwords.matchesPassword(input.password(), "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy");
            throw new BusinessException(401, "server.compute.login.failed");
        }
        Map<String, Object> row = rows.get(0);
        String hash = String.valueOf(row.get("password_hash"));
        boolean enabled = "1".equals(String.valueOf(row.get("enabled"))) || Boolean.TRUE.equals(row.get("enabled"));
        if (!enabled || !passwords.matchesPassword(input.password(), hash)) throw new BusinessException(401, "server.compute.login.failed");
        long id = ((Number) row.get("id")).longValue();
        String token = issueSession(id, hash);
        return Map.of("token", token, "member", member(id));
    }

    public ComputeMemberPrincipal authenticate(String token) {
        if (token == null || token.length() > 128) return null;
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minus(userIdle());
        // 旧会话没有稳定签发顺序；发现多个有效登录时要求重新登录，不按可续期的活跃时间猜测新设备。
        List<ComputeMemberPrincipal> principals = repository.authenticate(ComputeCredentials.digest(token),cutoff);
        if(principals.isEmpty())return null;
        return principals.get(0);
    }

    /** 注册、正常登录及模拟登录在同一会员锁内替换旧会话，同一账号仅保留一次有效登录。 */
    @Transactional
    public String issueSession(long memberId, String credentialHash) {
        List<String> currentHashes = repository.lockActiveHashes(memberId);
        if (currentHashes.size() != 1 || !java.util.Objects.equals(currentHashes.get(0), credentialHash))
            throw new BusinessException(401, "server.compute.session.expired");
        String token = ComputeCredentials.randomToken();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // 只有通过当前凭据复核的成功登录才能替换会话；插入失败时由同一事务恢复旧会话。
        repository.deleteSessions(memberId);
        repository.insertSession(ComputeCredentials.digest(token),memberId,credentialHash,now.plus(userIdle()),now);
        return token;
    }

    public void recordSessionActivity(String token) {
        if (token == null || token.length() > 128) return;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        java.time.Duration idle = userIdle();
        // 只更新当前仍有效的会话；异步旧响应无法重建已退出、强制下线或过期的记录。
        repository.sessionActivity(ComputeCredentials.digest(token),now,now.plus(idle),now.minus(idle));
    }

    private java.time.Duration userIdle() {
        return loginSessionPolicy == null ? java.time.Duration.ofHours(72) : loginSessionPolicy.userIdle();
    }

    /** 只记录已经通过认证的访问来源；注册管理员本人不冒充新建用户的注册来源。 */
    public void recordAccess(long id,String ip,boolean registration) {
        String country=org.token.tools.geo.IpUtils.getCountryCode(ip);
        repository.recordAccess(id,ip,country,registration);
        // 登录来源只更新用户资料；不能替其他浏览器的空闲会话续期。
    }

    @Transactional
    public void changePassword(long id, ComputeAuthRequests.Password input) {
        ComputeCredentials.validatePassword(input.newPassword());
        String old = repository.lockPassword(id);
        if (!passwords.matchesPassword(input.oldPassword(), old)) throw new BusinessException("server.compute.password.currentIncorrect");
        repository.password(id,passwords.hashPassword(input.newPassword()));
        repository.deleteSessions(id);
    }

    /** 支付密码始终在用户锁内验证当前凭据，不能作为免验证的密码重置入口。 */
    @Transactional
    public void changePayPassword(long id, Map<String, String> input) {
        ComputeCredentials.validatePayPassword(input.get("newPayPassword"));
        String login = java.util.Objects.toString(input.get("loginPassword"), ""), oldPay = java.util.Objects.toString(input.get("oldPayPassword"), "");
        if (login.length() > 64 || oldPay.length() > 64) throw new BusinessException("server.compute.ui.credentialsRequired");
        List<Map<String, Object>> rows = repository.lockPayCredentials(id);
        if (rows.isEmpty()) throw new BusinessException(401, "server.compute.session.expired");
        var row = rows.get(0);
        if (!passwords.matchesPassword(login, String.valueOf(row.get("password_hash"))))
            throw new BusinessException("server.compute.password.currentIncorrect");
        String previous = row.get("pay_password_hash") == null ? "" : row.get("pay_password_hash").toString();
        if (!previous.isBlank() && !passwords.matchesPassword(oldPay, previous))
            throw new BusinessException("server.compute.payPasswordInvalid");
        repository.payPassword(id,passwords.hashPassword(input.get("newPayPassword")));
    }

    @Transactional
    public Map<String, Object> updateProfile(long id, ComputeAuthRequests.Profile input) {
        agentScope.relations().lock();
        // 兼容写入口先锁自己的会员行，再复核设置，保持会员资金操作的既有锁方向。
        repository.lockMember(id);
        Map<String, Object> previous = member(id);
        Map<String, String> config = settings();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("username", input.username()); fields.put("email", input.email()); fields.put("phone", input.phone());
        for (var entry : fields.entrySet()) {
            if (entry.getValue() == null) continue;
            String key = entry.getKey();
            if (!Boolean.parseBoolean(config.getOrDefault(key + "Enabled", "true"))) throw new BusinessException("server.compute.feature.disabled");
            String value = entry.getValue().trim();
            if ("username".equals(key)) validateNewUsername(value, 4, memberUids.usernameLimitForWrite(), value);
            value = value.toLowerCase(Locale.ROOT);
            if ("username".equals(key)) validateNewUsername(value, 4, false, value);
            if (value.isEmpty() || ("phone".equals(key) && !value.matches("\\+?[0-9]{6,20}")))
                throw new BusinessException("server.compute.account.invalid");
            previous.put(key, value);
        }
        try {
            repository.profile(id,previous);
        } catch (DuplicateKeyException ex) { throw new BusinessException("server.compute.account.exists"); }
        return member(id);
    }

    public void logout(String token) {
        if (token != null) repository.logout(ComputeCredentials.digest(token));
    }

    @Transactional
    public void requestRecovery(ComputeAuthRequests.Recovery input) {
        Map<String, String> config = settings();
        if (!Boolean.parseBoolean(config.getOrDefault("forgotPasswordEnabled", "true"))
                || !List.of("username", "email", "phone").contains(input.method())
                || !Boolean.parseBoolean(config.getOrDefault(input.method() + "Enabled", "true"))) {
            throw new BusinessException("server.compute.feature.disabled");
        }
        String account = input.account().trim().toLowerCase(Locale.ROOT);
        List<Long> members = repository.recoveryMember(input.method(),account);
        if (members.isEmpty()) return;
        long id = members.get(0);
        Integer pending = repository.pendingRecoveryCount(id);
        if (pending != null && pending > 0) return;
        repository.requestRecovery(id,input.method(),account);
    }

    public Map<String, Object> recoveries(int page, int size, String keyword, String startTime, String endTime) {
        var scope=agentScope.current();
        return repository.recoveries(page,size,keyword,startTime,endTime,scope.global(),scope.enabled(),scope.memberIds());
    }

    @Transactional
    public Map<String, Object> issueRecovery(long requestId, int adminId) {
        agentScope.relations().lock();
        List<Long> ids = repository.pendingRecoveryMembers(requestId);
        if (ids.isEmpty()) throw new BusinessException("server.compute.recovery.invalid");
        long memberId = ids.get(0);
        agentScope.assertMember(memberId);
        repository.requireMemberLock(memberId);
        agentScope.assertMember(memberId);
        List<Long> pending = repository.lockPendingRecovery(requestId);
        if (pending.isEmpty()) throw new BusinessException("server.compute.recovery.invalid");
        repository.expireRecoveries(memberId);
        String token = ComputeCredentials.randomToken();
        Instant expires = Instant.now().plus(30, ChronoUnit.MINUTES);
        repository.issueRecovery(requestId,ComputeCredentials.digest(token),LocalDateTime.ofInstant(expires,ZoneOffset.UTC),adminId);
        return Map.of("token", token, "expiresAt", expires.toString());
    }

    @Transactional
    public void resetPassword(ComputeAuthRequests.Reset input) {
        if (!Boolean.parseBoolean(settings().getOrDefault("forgotPasswordEnabled", "true"))) throw new BusinessException("server.compute.feature.disabled");
        ComputeCredentials.validatePassword(input.newPassword());
        List<Long> ids = repository.validRecoveryMembers(ComputeCredentials.digest(input.token()));
        if (ids.isEmpty()) throw new BusinessException("server.compute.recovery.invalid");
        long id = ids.get(0);
        repository.requireMemberLock(id);
        List<Long> valid = repository.lockValidRecovery(ComputeCredentials.digest(input.token()));
        if (valid.isEmpty()) throw new BusinessException("server.compute.recovery.invalid");
        repository.password(id,passwords.hashPassword(input.newPassword()));
        repository.useRecovery(ComputeCredentials.digest(input.token()));
        repository.deleteSessions(id);
    }
}
