package org.token.service.compute;

import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.regex.Pattern;

/** 隔离 H2 夹具只读取当前结构快照；不读取历史迁移、不加载任何项目数据库数据。 */
public final class TokenTestSchema {
    private static final Map<String, String> TABLES = definitions();
    private TokenTestSchema() { }

    public static void createAll(JdbcTemplate jdbc) { create(jdbc, TABLES.keySet().toArray(String[]::new)); }

    public static void create(JdbcTemplate jdbc, String... tables) {
        assertIsolated(jdbc);
        for (String table : tables) {
            String definition = definition(table);
            if (exists(jdbc, table)) continue;
            var dependencies = Pattern.compile("(?i)REFERENCES\\s+`?(\\w+)`?").matcher(definition);
            while (dependencies.find()) {
                String dependency = dependencies.group(1);
                if (!dependency.equals(table)) create(jdbc, dependency);
            }
            jdbc.execute(normalize(definition).replaceFirst("(?i)CREATE TABLE(?: IF NOT EXISTS)?", "CREATE TABLE IF NOT EXISTS"));
        }
    }

    /** 为有意精简的夹具补齐当前表的列；现有类型、约束和测试数据保持原样。 */
    public static void complete(JdbcTemplate jdbc, String... tables) {
        assertIsolated(jdbc);
        for (String table : tables) {
            if (!exists(jdbc, table)) continue;
            String sql = normalize(definition(table));
            var existing = new HashSet<>(jdbc.queryForList("SELECT LOWER(column_name) FROM information_schema.columns WHERE LOWER(table_name)=?", String.class, table));
            for (String part : parts(sql.substring(sql.indexOf('(') + 1, sql.lastIndexOf(')')))) {
                var column = Pattern.compile("^\\s*`?(\\w+)`?\\s+(.+)$", Pattern.DOTALL).matcher(part);
                if (!column.matches()) continue;
                String name = column.group(1);
                if (List.of("PRIMARY", "UNIQUE", "KEY", "INDEX", "CONSTRAINT", "CHECK", "FOREIGN").contains(name.toUpperCase())) continue;
                if (!existing.contains(name.toLowerCase()))
                    jdbc.execute("ALTER TABLE " + table + " ADD COLUMN `" + name + "` " + column.group(2));
            }
        }
    }

    public static void apply(JdbcTemplate jdbc) {
        administrators(jdbc);
        create(jdbc, "system_setting", "compute_member_invite_code", "compute_relation_state", "compute_admin_member_scope", "compute_address_guard",
                "compute_member_operation", "compute_currency_asset", "compute_currency_network", "compute_provider",
                "compute_auto_contract", "compute_auto_settlement", "compute_system_message_template",
                "compute_system_message_delivery", "compute_member_login_ticket", "compute_exchange_rate",
                "compute_exchange_refresh", "compute_reward_event", "compute_reward_award", "compute_content_popup_seen",
                "compute_finance_snapshot", "compute_product_quote");
        content(jdbc);
        for (String table : TABLES.keySet()) {
            if (table.startsWith("compute_") && exists(jdbc, table)) complete(jdbc, table);
        }
        jdbc.update("INSERT INTO compute_relation_state(singleton_id,version) SELECT 1,1 WHERE NOT EXISTS(SELECT 1 FROM compute_relation_state WHERE singleton_id=1)");
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value,group_name,revision,updated_at) SELECT 'compute.reward.configuration','{\"configuration\":{\"depositRules\":[],\"effectiveRules\":[]},\"activatedAt\":null}','reward',0,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM system_setting WHERE setting_key='compute.reward.configuration')");
        if (exists(jdbc, "compute_currency")) seedCurrencies(jdbc);
    }

    public static void administrators(JdbcTemplate jdbc) {
        // 名称投影测试无需准备认证凭据，账户完整结构由安全与启动测试验证。
        jdbc.execute("CREATE TABLE IF NOT EXISTS ad_master(uid INT PRIMARY KEY,username VARCHAR(100),scope_configured_at DATETIME(6))");
        jdbc.execute("ALTER TABLE ad_master ADD COLUMN IF NOT EXISTS scope_configured_at DATETIME(6)");
        jdbc.update("INSERT INTO ad_master(uid,username) SELECT 1,'admin' WHERE NOT EXISTS(SELECT 1 FROM ad_master WHERE uid=1)");
    }

    public static void content(JdbcTemplate jdbc) {
        create(jdbc, "compute_content", "compute_content_recipient");
        complete(jdbc, "compute_content", "compute_content_recipient", "compute_content_translation_task");
        complete(jdbc, "compute_product", "compute_level", "compute_model");
    }

    public static void activities(JdbcTemplate jdbc) {
        create(jdbc, "compute_activity_attempt_grant", "compute_activity_invite_grant");
        complete(jdbc, "compute_activity", "compute_activity_prize", "compute_activity_participation", "compute_wheel_member");
    }

    public static void translation(JdbcTemplate jdbc) {
        create(jdbc, "compute_translation_form");
        administrators(jdbc);
    }

    public static void inviteCodes(JdbcTemplate jdbc) { create(jdbc, "compute_member_invite_code"); }
    public static void usdAccounting(JdbcTemplate jdbc) { complete(jdbc, "compute_deposit", "compute_withdrawal"); nativeDepositPrecision(jdbc); }
    public static void withdrawalRules(JdbcTemplate jdbc) { complete(jdbc, "compute_fee_rule", "compute_withdrawal"); }
    public static void financeSnapshots(JdbcTemplate jdbc) { create(jdbc, "compute_finance_snapshot"); nativeDepositPrecision(jdbc); }
    static void nativeDepositPrecision(JdbcTemplate jdbc) {
        if (exists(jdbc, "compute_deposit")) jdbc.execute("ALTER TABLE compute_deposit ALTER COLUMN amount SET DATA TYPE DECIMAL(50,30)");
        if (exists(jdbc, "compute_finance_snapshot")) {
            jdbc.execute("ALTER TABLE compute_finance_snapshot ALTER COLUMN native_amount SET DATA TYPE DECIMAL(50,30)");
            jdbc.execute("ALTER TABLE compute_finance_snapshot ALTER COLUMN native_net_amount SET DATA TYPE DECIMAL(50,30)");
        }
    }

    public static void seedCurrencies(JdbcTemplate jdbc) {
        create(jdbc, "compute_currency_asset", "compute_currency_network");
        jdbc.update("INSERT INTO compute_currency_asset(asset_code,asset_name,created_at,updated_at) SELECT DISTINCT c.currency,c.currency,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM compute_currency c WHERE NOT EXISTS(SELECT 1 FROM compute_currency_asset a WHERE a.asset_code=c.currency)");
        jdbc.update("INSERT INTO compute_currency_network(network_code,network_name,chain_type,created_at,updated_at) SELECT DISTINCT c.network,c.network,CASE WHEN c.network='TRC20' THEN 'TRON' WHEN c.network IN ('ERC20','BEP20') THEN 'EVM' ELSE 'OTHER' END,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM compute_currency c WHERE NOT EXISTS(SELECT 1 FROM compute_currency_network n WHERE n.network_code=c.network)");
    }

    private static String definition(String table) {
        String sql = TABLES.get(table);
        if (sql == null) throw new IllegalArgumentException("当前结构不存在表：" + table);
        return sql;
    }
    private static boolean exists(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name)=?", Integer.class, table) > 0;
    }
    private static void assertIsolated(JdbcTemplate jdbc) {
        try (var connection = jdbc.getDataSource().getConnection()) {
            if (!connection.getMetaData().getURL().startsWith("jdbc:h2:mem:"))
                throw new IllegalStateException("测试结构只允许 H2 内存数据库");
        } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
    }
    private static Map<String, String> definitions() {
        try (var source = TokenTestSchema.class.getResourceAsStream("/token-schema.sql")) {
            if (source == null) throw new IllegalStateException("缺少当前结构快照 token-schema.sql");
            String sql = new String(source.readAllBytes(), StandardCharsets.UTF_8);
            var tables = Pattern.compile("(?ims)^CREATE TABLE(?: IF NOT EXISTS)?\\s+`?(\\w+)`?\\s*\\(.*?^\\).*?;").matcher(sql);
            Map<String, String> result = new LinkedHashMap<>();
            while (tables.find()) result.put(tables.group(1), tables.group());
            if (result.isEmpty()) throw new IllegalStateException("当前结构快照没有建表定义");
            return result;
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }
    private static String normalize(String sql) {
        var tableName = Pattern.compile("(?i)CREATE TABLE(?: IF NOT EXISTS)?\\s+`?(\\w+)`?").matcher(sql);
        if (!tableName.find()) throw new IllegalArgumentException("不是建表语句");
        String table = tableName.group(1);
        // MySQL 索引名只需在表内唯一，H2 的具名约束需要在整个 schema 内唯一。
        sql = Pattern.compile("(?i)((?:UNIQUE\\s+)?KEY|INDEX|CONSTRAINT)\\s+`(\\w+)`").matcher(sql)
                .replaceAll(match -> match.group(1) + " `" + table + "_" + match.group(2) + "`");
        return sql.replaceAll("(?is)\\)\\s*ENGINE=.*$", ");")
                .replaceAll("(?i)\\s+COMMENT\\s+'(?:[^'\\\\]|\\\\.|'')*'", "")
                .replaceAll("(?i)\\s+(?:CHARACTER SET|CHARSET|COLLATE)\\s+\\w+", "")
                .replaceAll("(?i)\\b(tinyint|smallint|mediumint|int|bigint)\\(\\d+\\)", "$1")
                .replaceAll("(?i)\\s+USING\\s+BTREE", "")
                .replaceAll("(?i)\\s+STORED\\b", "")
                .replaceAll("(?i)\\bJSON\\b", "VARCHAR(1000000)")
                .replaceAll("(?i)_utf8mb4(?=')", "");
    }
    private static List<String> parts(String text) {
        List<String> result = new ArrayList<>();
        int start = 0, depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && quoted) { escaped = true; continue; }
            if (c == '\'') { quoted = !quoted; continue; }
            if (quoted) continue;
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (c == ',' && depth == 0) { result.add(text.substring(start, i)); start = i + 1; }
        }
        result.add(text.substring(start));
        return result;
    }
}
