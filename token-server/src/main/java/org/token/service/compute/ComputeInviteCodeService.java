package org.token.service.compute;

import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeInviteCodeRepository;
import org.token.service.compute.ComputeAgentScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 注册与后台换码共用永久占用目录；废弃码永不分配给其他用户。 */
@Service
public class ComputeInviteCodeService {
    public static final String DEFAULT_MODE = "ALPHANUMERIC";
    public static final int DEFAULT_LENGTH = 12;
    private static final String DIGITS = "0123456789";
    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final Set<String> MODES = Set.of("DIGITS", "LETTERS", "ALPHANUMERIC");
    private final ComputeInviteCodeRepository repository;
    private final ComputeAgentScope scope;
    private final SecureRandom random;

    @Autowired
    public ComputeInviteCodeService(ComputeInviteCodeRepository repository,ComputeAgentScope scope) { this(repository,scope,new SecureRandom()); }
    public ComputeInviteCodeService(JdbcTemplate jdbc,ComputeAgentScope scope) { this(new ComputeInviteCodeRepository(jdbc),scope); }
    ComputeInviteCodeService(JdbcTemplate jdbc,ComputeAgentScope scope,SecureRandom random) { this(new ComputeInviteCodeRepository(jdbc),scope,random); }
    ComputeInviteCodeService(ComputeInviteCodeRepository repository,ComputeAgentScope scope,SecureRandom random) { this.repository=repository;this.scope=scope;this.random=random; }

    public record Policy(String mode, int length) {}

    public static Policy policy(Object modeValue, Object lengthValue) {
        String mode = modeValue == null ? DEFAULT_MODE : String.valueOf(modeValue);
        try {
            String rawLength = lengthValue == null ? String.valueOf(DEFAULT_LENGTH) : String.valueOf(lengthValue);
            if (!MODES.contains(mode) || !rawLength.matches("[0-9]{1,2}")) throw new IllegalArgumentException();
            int length = Integer.parseInt(rawLength);
            if (length < 6 || length > 32) throw new IllegalArgumentException();
            return new Policy(mode, length);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("server.compute.inviteCode.ruleInvalid");
        }
    }

    /** 调用方已持有关系事务锁；与注册、后台开户、换码按同一顺序串行。 */
    String nextCode() {
        Map<String, String> settings = repository.policy();
        Policy policy = policy(settings.get("inviteCodeMode"), settings.get("inviteCodeLength"));
        for (int attempt = 0; attempt < 128; attempt++) {
            String code = generate(policy);
            if (repository.unused(code)) return code;
        }
        throw new BusinessException("server.compute.inviteCode.exhausted");
    }

    private String generate(Policy policy) {
        String alphabet = switch (policy.mode()) {
            case "DIGITS" -> DIGITS;
            case "LETTERS" -> LETTERS;
            default -> DIGITS + LETTERS;
        };
        StringBuilder result = new StringBuilder(policy.length());
        // 组合模式保证实际同时含数字和字母；不只把两组字符放入候选池。
        if ("ALPHANUMERIC".equals(policy.mode())) {
            result.append(DIGITS.charAt(random.nextInt(DIGITS.length())));
            result.append(LETTERS.charAt(random.nextInt(LETTERS.length())));
        }
        while (result.length() < policy.length()) result.append(alphabet.charAt(random.nextInt(alphabet.length())));
        for (int index = result.length() - 1; index > 0; index--) {
            int other = random.nextInt(index + 1);
            char value = result.charAt(index);
            result.setCharAt(index, result.charAt(other)); result.setCharAt(other, value);
        }
        return result.toString();
    }

    /** 和新用户写入属于同一事务，后续任一步失败会一起回滚。 */
    void recordCreated(long memberId, String code) {
        repository.recordCreated(memberId,code,now());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> rotate(long memberId, Map<String, Object> request) {
        String expected = Objects.toString(request.get("expectedInviteCode"), "");
        String requestNo = Objects.toString(request.get("requestNo"), "");
        if (expected.isBlank() || expected.length() > 32) throw new BusinessException("server.compute.invalidInput");
        if (!requestNo.matches("[A-Za-z0-9:_-]{8,64}")) throw new BusinessException("server.compute.requestIdRequired");
        scope.relations().lock();
        scope.assertMember(memberId);
        String current = repository.lockCurrent(memberId);
        List<Map<String, Object>> previous = repository.previous(memberId,requestNo);
        if (!previous.isEmpty()) {
            if (!expected.equals(previous.get(0).get("previous_code"))) throw new BusinessException("server.compute.requestConflict");
            if (!current.equals(previous.get(0).get("code"))) throw new BusinessException("server.compute.inviteCode.changed");
            return Map.of("memberId", memberId, "inviteCode", current);
        }
        if (!expected.equals(current)) throw new BusinessException("server.compute.inviteCode.changed");
        List<Map<String, Object>> old = repository.owner(current);
        if (old.isEmpty()) recordCreated(memberId, current);
        else if (((Number) old.get(0).get("member_id")).longValue() != memberId || old.get(0).get("retired_at") != null)
            throw new BusinessException("server.compute.inviteCode.changed");
        String next = nextCode();
        LocalDateTime now = now();
        repository.recordRotation(memberId,current,next,requestNo,now);
        repository.retire(memberId,current,now);
        repository.replace(memberId,next,now);
        return Map.of("memberId", memberId, "inviteCode", next);
    }

    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}
