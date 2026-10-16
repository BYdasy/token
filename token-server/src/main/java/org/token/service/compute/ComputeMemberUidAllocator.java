package org.token.service.compute;

import org.token.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.token.repository.compute.ComputeMemberUidRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

/** 会员创建与设置保存先持有关系事务锁；只分配新UID，不改存量编号或自增种子。 */
@Service
public class ComputeMemberUidAllocator {
    public static final String USERNAME_LIMIT = "usernameCharacterLimitEnabled";
    public static final String BASE = "memberUidStartBase";
    public static final String STEP = "memberUidRandomStep";
    public static final String CURSOR = "memberUidLastAssigned";
    public static final long MAX_UID = 9_007_199_254_740_991L;
    public static final int MAX_STEP = 1_000_000;
    private final ComputeMemberUidRepository repository;
    private final IntUnaryOperator random;

    @Autowired
    public ComputeMemberUidAllocator(ComputeMemberUidRepository repository) { this(repository,new SecureRandom()::nextInt); }
    public ComputeMemberUidAllocator(JdbcTemplate jdbc) { this(new ComputeMemberUidRepository(jdbc)); }
    ComputeMemberUidAllocator(JdbcTemplate jdbc,IntUnaryOperator random) { this(new ComputeMemberUidRepository(jdbc),random); }
    ComputeMemberUidAllocator(ComputeMemberUidRepository repository,IntUnaryOperator random) { this.repository=repository;this.random=random; }

    public static boolean usernameLimit(Map<String, String> settings) {
        return !"false".equalsIgnoreCase(settings.get(USERNAME_LIMIT));
    }

    /** 调用方已持有关系锁；FOR UPDATE复核设置，避免注册在锁前读到旧开关。 */
    public boolean usernameLimitForWrite() {
        requireTransaction();
        // 与其他配置写入口同序锁定整表，首次建立UID游标也不能反向锁间隙。
        var values=repository.lockAllSettings();
        return usernameLimit(values);
    }

    public Map<String, Object> adminSettings() {
        Map<String, String> values = read(false);
        Long base = base(values);
        long maximum = currentMaximum();
        var result = new LinkedHashMap<String, Object>();
        result.put(BASE, base);
        result.put(STEP, step(values));
        result.put("memberUidCurrentMax", maximum);
        result.put("memberUidHighWatermark", highWater(values, maximum));
        return result;
    }

    /** 保存相同基数不重置，单改步长延续游标；未配置基数时只保存步长仍沿原自增。 */
    public void configure(Map<String, Object> request) {
        if (!request.containsKey(BASE) && !request.containsKey(STEP)) return;
        requireTransaction();
        Map<String, String> values = read(true);
        Long previous = base(values);
        Long next = previous;
        if (request.containsKey(BASE)) next = integer(request.get(BASE), 1, MAX_UID - 1);
        int nextStep = request.containsKey(STEP) ? (int) integer(request.get(STEP), 1, MAX_STEP) : step(values);
        long high = highWater(values, currentMaximum());
        if (!Objects.equals(previous, next) && next != null && next <= high)
            throw new BusinessException("server.compute.memberUid.baseTooSmall");
        if (next != null && Math.max(high, next) > MAX_UID - nextStep)
            throw new BusinessException("server.compute.memberUid.exhausted");
        if (next != null) put(BASE, next);
        put(STEP, nextStep);
        // 首次建立游标只记录真实已用下限，基数本身尚未分配给任何会员。
        put(CURSOR, high);
    }

    /** null表示保留数据库原自增；指定规则只返回本事务拟分配的UID。 */
    public Long nextId() {
        requireTransaction();
        Map<String, String> values = read(true);
        Long base = base(values);
        if (base == null) return null;
        int step = step(values);
        long position = Math.max(base, highWater(values, currentMaximum()));
        if (position > MAX_UID - step) throw new BusinessException("server.compute.memberUid.exhausted");
        return position + random.applyAsInt(step) + 1;
    }

    /** 与会员INSERT同事务；后续失败会一起回滚，已删除会员的历史高水位也不会下降。 */
    public void recordAssigned(long id) {
        requireTransaction();
        if (id < 1 || id > MAX_UID) throw new BusinessException("server.compute.memberUid.exhausted");
        Map<String, String> values = read(true);
        long previous = cursor(values);
        put(CURSOR, Math.max(previous, id));
    }

    private Map<String, String> read(boolean lock) { return repository.settings(lock); }

    private long currentMaximum() {
        // 创建和设置保存均为RC且已持关系锁，因此读最新已提交MAX；不锁旧最高会员而与资金入口反序。
        return repository.currentMaximum();
    }

    private long highWater(Map<String, String> values, long maximum) {
        long high = Math.max(maximum, cursor(values));
        // 配置游标已有时只采用已提交会员事实，不能把失败INSERT残留的自增推进当成随机步长。
        if (base(values) == null || !values.containsKey(CURSOR)) high = Math.max(high, autoIncrementFloor());
        return high;
    }

    private long autoIncrementFloor() { return repository.autoIncrementFloor(); }

    private static Long base(Map<String, String> values) {
        return values.containsKey(BASE) ? integer(values.get(BASE), 1, MAX_UID - 1) : null;
    }

    private static int step(Map<String, String> values) {
        return values.containsKey(STEP) ? (int) integer(values.get(STEP), 1, MAX_STEP) : 1;
    }

    private static long cursor(Map<String, String> values) {
        return values.containsKey(CURSOR) ? integer(values.get(CURSOR), 0, Long.MAX_VALUE) : 0;
    }

    private static long integer(Object raw, long minimum, long maximum) {
        try {
            String value = Objects.toString(raw, "");
            if (!value.matches("[0-9]{1,19}")) throw new IllegalArgumentException();
            long parsed = Long.parseLong(value);
            if (parsed < minimum || parsed > maximum) throw new IllegalArgumentException();
            return parsed;
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException("server.compute.memberUid.ruleInvalid");
        }
    }

    private void put(String key, long value) { repository.put(key,value,LocalDateTime.now(ZoneOffset.UTC)); }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("会员UID及身份规则必须在关系事务锁内处理");
    }
}
