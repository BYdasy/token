package org.token.repository.compute;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.token.exception.BusinessException;
import org.token.util.identifier.ComputeBusinessNumbers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.token.repository.dao.compute.ComputeRecordMapper;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.support.RepositoryResults;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** 领域持久层：所有动态表名和字段名仅由服务端固定清单提供。 */
@Component
public class ComputeData {
    private static final ObjectMapper ID_LIST_MAPPER = new ObjectMapper();
    private static final TypeReference<List<Long>> ID_LIST_TYPE = new TypeReference<>() { };
    private static final Map<String,String> BUSINESS_PREFIXES=Map.ofEntries(
            Map.entry("compute_checkin","CK"),Map.entry("compute_order","PO"),Map.entry("compute_order_settlement","PS"),
            Map.entry("compute_deposit","DP"),Map.entry("compute_withdrawal","WD"),
            Map.entry("compute_transfer","TR"),Map.entry("compute_ledger","LG"),
            Map.entry("compute_reward_award","RW"),Map.entry("compute_member_operation","MO"),
            Map.entry("compute_activity_participation","AP"),Map.entry("compute_auto_contract","AC"),Map.entry("compute_auto_settlement","AS"));
    private static final Set<String> RECORD_TABLES = Set.of(
            "compute_activity",
            "compute_activity_attempt_grant",
            "compute_activity_invite_grant",
            "compute_activity_participation",
            "compute_activity_prize",
            "compute_address",
            "compute_address_guard",
            "compute_admin_member_scope",
            "compute_admin_table_columns",
            "compute_auto_contract",
            "compute_auto_settlement",
            "compute_checkin",
            "compute_company_file",
            "compute_content",
            "compute_content_popup_seen",
            "compute_content_recipient",
            "compute_content_translation_item",
            "compute_content_translation_task",
            "compute_currency",
            "compute_currency_asset",
            "compute_currency_network",
            "compute_deposit",
            "compute_exchange_rate",
            "compute_exchange_refresh",
            "compute_fee_rule",
            "compute_finance_snapshot",
            "compute_finance_sound_config",
            "compute_job",
            "compute_job_run",
            "compute_ledger",
            "compute_level",
            "compute_mcag_address",
            "compute_mcag_address_current",
            "compute_mcag_address_generation",
            "compute_mcag_channel",
            "compute_mcag_collection_batch",
            "compute_mcag_cursor",
            "compute_mcag_customer_identity",
            "compute_mcag_deposit_order",
            "compute_mcag_event",
            "compute_mcag_payout",
            "compute_member",
            "compute_member_group",
            "compute_member_invite_code",
            "compute_member_login_ticket",
            "compute_member_operation",
            "compute_member_session",
            "compute_merchant_snapshot",
            "compute_model",
            "compute_model_price",
            "compute_order",
            "compute_order_settlement",
            "compute_product",
            "compute_provider",
            "compute_recovery_request",
            "compute_relation_state",
            "compute_request",
            "compute_reward_award",
            "compute_reward_event",
            "system_setting",
            "compute_product_quote",
            "compute_system_message_delivery",
            "compute_system_message_template",
            "compute_transfer",
            "compute_transfer_rule",
            "compute_translation_form",
            "compute_wallet",
            "compute_wheel_adjustment",
            "compute_wheel_member",
            "compute_withdrawal");
    private final ComputeRecordMapper records;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final org.springframework.beans.factory.BeanFactory mapperBeans;

    public ComputeData(JdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, mapper, AnnotationMapperFactory.mapper(jdbc, ComputeRecordMapper.class), null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ComputeData(JdbcTemplate jdbc, ObjectMapper mapper, ComputeRecordMapper records,
                       org.springframework.beans.factory.BeanFactory mapperBeans) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.mapperBeans = mapperBeans;
        this.records = Objects.requireNonNull(records);
    }

    /** 业务辅助对象复用应用的 Mapper、插件和数据源；无容器构造器供隔离测试使用。 */
    public <T> T mapper(Class<T> type) {
        // MapperFactoryBean 初始化时才向 MyBatis 注册接口；必须先获取 Spring Mapper Bean，
        // 不能在其他 Bean 的构造阶段绕过它直接调用 SqlSessionTemplate.getMapper。
        return mapperBeans == null ? AnnotationMapperFactory.mapper(jdbc, type) : mapperBeans.getBean(type);
    }

    public JdbcTemplate jdbc() { return jdbc; }

    /** 将注解 Mapper 的原生列结果转换为既有 Compute API 契约。 */
    public static List<Map<String, Object>> normalizeRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) result.add(normalizeRow(row));
        return result;
    }

    public static Map<String, Object> normalizeRow(Map<String, Object> raw) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (raw == null) return row;
        raw.forEach((column, original) -> {
            String name = camel(column);
            Object value = original;
            if (value instanceof Timestamp time) value = time.toLocalDateTime().toInstant(ZoneOffset.UTC).toString();
            if (value instanceof LocalDateTime time) value = time.toInstant(ZoneOffset.UTC).toString();
            if (value instanceof java.sql.Date date) value = date.toLocalDate().toString();
            if (value instanceof java.sql.Time time) value = time.toLocalTime().toString();
            if (value instanceof BigDecimal decimal) value = decimal.toPlainString();
            if ("autoProviderIds".equals(name)) {
                if (value instanceof byte[] bytes) value = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                if (value instanceof String text) {
                    try { value = ID_LIST_MAPPER.readValue(text, ID_LIST_TYPE); }
                    catch (Exception invalid) { value = List.of(); }
                }
            }
            row.put(name, value);
        });
        return row;
    }

    public long insert(String table, Map<String, Object> values) {
        Map<String,Object> stored=new LinkedHashMap<>(values);
        String prefix=BUSINESS_PREFIXES.get(table);
        boolean generateNumber = prefix!=null && text(stored.get("order_no")).isEmpty();
        if (generateNumber) stored.put("order_no", ComputeBusinessNumbers.next(prefix));
        validateRecord(table, stored);
        for (int attempt = 0; attempt < 3; attempt++) {
            Map<String, Object> command = new LinkedHashMap<>();
            command.put("table", table);
            command.put("values", stored);
            try {
                records.insert(command);
                Number key = (Number) command.get("id");
                if (key == null) throw new BusinessException("server.compute.saveFailed");
                return key.longValue();
            } catch (DuplicateKeyException conflict) {
                // 仅重试本方法生成的编号碰撞，原业务幂等键冲突继续交给调用方处理。
                if (!generateNumber || attempt == 2 || records.orderNumberCount(table, stored.get("order_no")) == 0) throw conflict;
                stored.put("order_no", ComputeBusinessNumbers.next(prefix));
            }
        }
        throw new BusinessException("server.compute.saveFailed");
    }

    public Map<String, Object> page(String table, Long memberId, int page, int size) {
        // 调用方只能传本模块明确列出的表。
        if (!Set.of("compute_order", "compute_deposit", "compute_withdrawal", "compute_ledger", "compute_job_run").contains(table))
            throw new IllegalArgumentException("不支持的业务分页表");
        int limit = Math.min(100, Math.max(1, size));
        long offset = (long) (Math.max(1, page) - 1) * limit;
        return Map.of("records", normalizeRows(records.page(table, memberId, limit, offset)),
                "total", records.countPage(table, memberId));
    }

    public String setting(String key, String fallback) {
        List<Map<String, Object>> values = records.setting(key);
        return values.isEmpty() ? fallback : RepositoryResults.scalar(values, String.class);
    }

    public int update(String table, long id, Map<String, Object> values) {
        validateRecord(table, values);
        if (values.containsKey("id")) throw new IllegalArgumentException("更新内容不能修改记录主键");
        return records.update(table, id, values);
    }

    public Map<String, Object> required(String table, Object id, boolean lock) {
        validateTable(table);
        return RepositoryResults.required(records.byId(table, id, lock));
    }

    private static void validateRecord(String table, Map<String, Object> values) {
        validateTable(table);
        if (values.isEmpty() || values.keySet().stream().anyMatch(key -> !key.matches("[a-z][a-z0-9_]*")))
            throw new IllegalArgumentException("无效的记录字段");
    }

    private static void validateTable(String table) {
        if (!RECORD_TABLES.contains(table)) throw new IllegalArgumentException("不支持的业务数据表");
    }

    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new BusinessException("server.compute.invalidInput"); }
    }

    public Map<String, Object> jsonMap(String value) {
        try { return mapper.readValue(value, new TypeReference<>() {}); }
        catch (Exception exception) { throw new BusinessException("server.compute.invalidInput"); }
    }

    /** JDBC驱动的JSON列可能以UTF-8字节数组返回。 */
    public Map<String,Object> jsonMap(Object value) {
        return jsonMap(value instanceof byte[] bytes ? new String(bytes,java.nio.charset.StandardCharsets.UTF_8) : text(value));
    }

    public static BigDecimal decimal(Object value) {
        try { return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString()); }
        catch (NumberFormatException exception) { throw new BusinessException("server.compute.invalidAmount"); }
    }

    public static BigDecimal amount(Object value) {
        BigDecimal amount = decimal(value);
        if (amount.signum() <= 0 || amount.scale() > 8 || amount.precision() - amount.scale() > 18)
            throw new BusinessException("server.compute.invalidAmount");
        return amount.setScale(8, RoundingMode.UNNECESSARY);
    }

    public static long number(Object value) {
        try { return Long.parseLong(String.valueOf(value)); }
        catch (Exception exception) { throw new BusinessException("server.compute.invalidInput"); }
    }

    public static String text(Object value) { return value == null ? "" : value.toString().trim(); }
    public static boolean enabled(Object value) { return Boolean.TRUE.equals(value) || "1".equals(text(value)) || "true".equalsIgnoreCase(text(value)); }
    public static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    public static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    private static String camel(String value) {
        value = value.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(); boolean upper = false;
        for (char c : value.toCharArray()) {
            if (c == '_') { upper = true; continue; }
            out.append(upper ? Character.toUpperCase(c) : c); upper = false;
        }
        return out.toString();
    }
}
