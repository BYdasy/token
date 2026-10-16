package org.token.repository.dao.compute;

import java.util.*;
import static org.token.repository.compute.ComputeData.*;
import java.time.*;
import java.math.*;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeCurrencyService 的参数化数据库语句。 */
@Mapper
public interface ComputeCurrencyMapper {

    @Select("SELECT COUNT(*) AS repository_value FROM compute_address WHERE id=#{args[0]} AND member_id=#{args[1]} AND enabled=1 AND removed=0")
    List<Map<String, Object>> assertAddressCapacityAddressCount(@Param("args") Object[] args);

    @Select("SELECT address_limit AS repository_value FROM compute_member WHERE id=#{args[0]}")
    List<Map<String, Object>> assertAddressCapacityMemberValue(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_address WHERE member_id=#{args[0]} AND enabled=1 AND removed=0")
    List<Map<String, Object>> assertAddressCapacityAddressCount2(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_address WHERE member_id=#{args[0]} AND enabled=1 AND removed=0 ORDER BY CASE WHEN currency=#{args[1]} THEN 0 ELSE 1 END,updated_at DESC,id DESC")
    List<Map<String, Object>> manualWithdrawalRecipientAddressRows(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_currency_asset ORDER BY sort_no,id LIMIT 1000")
    List<Map<String, Object>> optionsCurrencyAssetRows(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_currency_network ORDER BY sort_no,id LIMIT 1000")
    List<Map<String, Object>> optionsCurrencyNetworkRows(@Param("args") Object[] args);

    @Select("SELECT setting_key FROM system_setting WHERE setting_key='summaryCurrency' FOR UPDATE")
    List<Map<String, Object>> saveSettingRows(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_currency_asset WHERE asset_code=#{args[0]}")
    List<Map<String, Object>> channelValuesCurrencyAssetRow(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_currency_network WHERE network_code=#{args[0]}")
    List<Map<String, Object>> channelValuesCurrencyNetworkRow(@Param("args") Object[] args);

    @Select("SELECT decimals FROM compute_mcag_channel WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND currency=#{args[2]} AND network=#{args[3]}")
    List<Map<String, Object>> syncedChannelMcagChannelRows(@Param("args") Object[] args);

    @Select("SELECT DISTINCT network FROM compute_mcag_channel WHERE merchant_id=#{args[0]} AND environment=#{args[1]}")
    List<Map<String, Object>> syncedNetworkCodesMcagChannelRows(@Param("args") Object[] args);

    @Select("SELECT c.*, a.asset_name,a.asset_type,a.icon_url,n.icon_url network_icon_url,a.amount_scale asset_amount_scale,a.enabled asset_enabled,n.network_name,n.network_type,n.chain_type,n.native_asset_code,n.required_fields_json,n.confirm_blocks,n.block_explorer_url,n.enabled network_enabled FROM compute_currency c JOIN compute_currency_asset a ON a.asset_code=c.currency JOIN compute_currency_network n ON n.network_code=c.network WHERE c.enabled=1 AND a.enabled=1 AND n.enabled=1 ORDER BY c.sort_no,c.id")
    List<Map<String, Object>> publicChannels();

    @Select("SELECT c.*, a.asset_name,a.asset_type,a.icon_url,n.icon_url network_icon_url,a.amount_scale asset_amount_scale,a.enabled asset_enabled,n.network_name,n.network_type,n.chain_type,n.native_asset_code,n.required_fields_json,n.confirm_blocks,n.block_explorer_url,n.enabled network_enabled FROM compute_currency c JOIN compute_currency_asset a ON a.asset_code=c.currency JOIN compute_currency_network n ON n.network_code=c.network WHERE c.currency=#{currency} AND c.enabled=1 AND a.enabled=1 AND n.enabled=1 AND c.deposit_enabled=1 ORDER BY c.sort_no,c.id")
    List<Map<String, Object>> manualDepositChannels(@Param("currency") String currency);

    @Select("SELECT c.*, a.asset_name,a.asset_type,a.icon_url,n.icon_url network_icon_url,a.amount_scale asset_amount_scale,a.enabled asset_enabled,n.network_name,n.network_type,n.chain_type,n.native_asset_code,n.required_fields_json,n.confirm_blocks,n.block_explorer_url,n.enabled network_enabled FROM compute_currency c JOIN compute_currency_asset a ON a.asset_code=c.currency JOIN compute_currency_network n ON n.network_code=c.network ORDER BY c.sort_no,c.id LIMIT 2000")
    List<Map<String, Object>> channelOptions();

    @SelectProvider(type=PageSql.class,method="visibleChannelOptions")
    List<Map<String,Object>> visibleChannelOptions(@Param("merchant") String merchant,@Param("environment") String environment);

    @Select("SELECT c.*, a.asset_name,a.asset_type,a.icon_url,n.icon_url network_icon_url,a.amount_scale asset_amount_scale,a.enabled asset_enabled,n.network_name,n.network_type,n.chain_type,n.native_asset_code,n.required_fields_json,n.confirm_blocks,n.block_explorer_url,n.enabled network_enabled FROM compute_currency c JOIN compute_currency_asset a ON a.asset_code=c.currency JOIN compute_currency_network n ON n.network_code=c.network WHERE c.id=#{id}")
    List<Map<String, Object>> channelById(@Param("id") long id);

    @Select("SELECT c.*, a.asset_name,a.asset_type,a.icon_url,n.icon_url network_icon_url,a.amount_scale asset_amount_scale,a.enabled asset_enabled,n.network_name,n.network_type,n.chain_type,n.native_asset_code,n.required_fields_json,n.confirm_blocks,n.block_explorer_url,n.enabled network_enabled FROM compute_currency c JOIN compute_currency_asset a ON a.asset_code=c.currency JOIN compute_currency_network n ON n.network_code=c.network WHERE c.currency=#{currency} AND c.network=#{network}")
    List<Map<String, Object>> channelByAssetNetwork(@Param("currency") String currency, @Param("network") String network);

    @Select("SELECT COUNT(*) FROM compute_currency c JOIN compute_currency_asset a ON a.asset_code=c.currency JOIN compute_currency_network n ON n.network_code=c.network WHERE c.currency=#{currency} AND c.enabled=1 AND a.enabled=1 AND n.enabled=1")
    Integer activeCurrencyCount(@Param("currency") String currency);

    @Select("<script>SELECT COUNT(*) FROM compute_currency WHERE <choose><when test='asset'>currency</when><otherwise>network</otherwise></choose>=#{code}</script>")
    Integer metadataUseCount(@Param("asset") boolean asset, @Param("code") String code);
    @SelectProvider(type=PageSql.class, method="page")
    List<Map<String,Object>> page(@Param("kind") String kind, @Param("query") Map<String,String> query, @Param("count") boolean count,
                                 @Param("merchant") String merchant,@Param("environment") String environment);

    final class PageSql {
        private static final String JOIN = " FROM compute_currency c JOIN compute_currency_asset a ON a.asset_code=c.currency JOIN compute_currency_network n ON n.network_code=c.network";
        private static final String SELECT = "SELECT c.*, a.asset_name,a.asset_type,a.icon_url,n.icon_url network_icon_url,a.amount_scale asset_amount_scale,a.enabled asset_enabled,n.network_name,n.network_type,n.chain_type,n.native_asset_code,n.required_fields_json,n.confirm_blocks,n.block_explorer_url,n.enabled network_enabled";
        // 只收起被精确 Crypto 映射取代且已停用的本地别名；映射、历史订单、地址及按身份读取均保留。
        private static final String VISIBLE_CHANNEL = " NOT (c.enabled=0 AND c.currency IN ('USDT','USDT0') AND EXISTS ("
                +"SELECT 1 FROM compute_mcag_channel canonical JOIN compute_currency canonical_channel ON canonical_channel.currency=canonical.currency AND canonical_channel.network=canonical.network"
                +" WHERE canonical.merchant_id=#{merchant} AND canonical.environment=#{environment}"
                +" AND canonical.asset_id='USDT_POLYGON' AND canonical.network_id='POLYGON_MAINNET'"
                +" AND canonical.network=c.network AND canonical.currency IN ('USDT','USDT0') AND canonical.currency<>c.currency"
                +" AND NOT EXISTS (SELECT 1 FROM compute_mcag_channel own_mapping WHERE own_mapping.merchant_id=#{merchant} AND own_mapping.environment=#{environment}"
                +" AND own_mapping.currency=c.currency AND own_mapping.network=c.network)))";
        public static String visibleChannelOptions(Map<String,Object> parameters) {
            return SELECT+JOIN+(text(parameters.get("merchant")).isEmpty()?"":" WHERE"+VISIBLE_CHANNEL)+" ORDER BY c.sort_no,c.id LIMIT 2000";
        }
        public static String page(Map<String, Object> parameters) {
            String kind = (String) parameters.get("kind");
            @SuppressWarnings("unchecked") Map<String,String> query = (Map<String,String>) parameters.get("query");
        String table = table(kind);
        boolean channel = "currencies".equals(kind);
        String alias = channel ? "c." : "";
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if(channel&&!text(parameters.get("merchant")).isEmpty())where.append(" AND").append(VISIBLE_CHANNEL);
        filterTime(where, args, query.get("startTime"), alias + "updated_at", ">=");
        filterTime(where, args, query.get("endTime"), alias + "updated_at", "<=");
        String keyword = text(query.get("keyword"));
        if (!keyword.isEmpty()) {
            if (keyword.length() > 100) invalid();
            where.append(channel ? " AND (c.currency LIKE ? OR c.network LIKE ? OR a.asset_name LIKE ? OR n.network_name LIKE ?)"
                    : " AND (" + ("currency-assets".equals(kind) ? "asset_code LIKE ? OR asset_name" : "network_code LIKE ? OR network_name") + " LIKE ?)");
            int count = channel ? 4 : 2;
            for (int i = 0; i < count; i++) args.add("%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
            // 关键词不参与 SQL 拼接；LIKE 特殊字符按普通输入匹配。
            int start = where.indexOf(" AND (");
            String tail = where.substring(start).replace(" LIKE ?", " LIKE ? ESCAPE '!'");
            where.replace(start, where.length(), tail);
        }
        if (channel) {
            eq(where, args, "c.currency", query.get("currency"));
            eq(where, args, "c.network", query.get("network"));
            eq(where, args, "a.asset_type", query.get("assetType"));
            flag(where, args, "c.deposit_enabled", query.get("depositEnabled"));
            flag(where, args, "c.withdraw_enabled", query.get("withdrawEnabled"));
            eq(where, args, "c.fee_mode", query.get("feeMode"));
        } else eq(where, args, "currency-assets".equals(kind) ? "asset_type" : "network_type", query.get("type"));
        flag(where, args, alias + "enabled", query.getOrDefault("enabled", query.get("status")));
        int size = integer(query.getOrDefault("size", "20"), 1, 100);
        int page = integer(query.getOrDefault("page", "1"), 1, 1000000);
        Map<String, String> sorts = channel ? Map.of("currency", "c.currency", "network", "c.network", "assetType", "a.asset_type",
                "depositMin", "c.deposit_min", "withdrawMin", "c.withdraw_min", "withdrawMax", "c.withdraw_max",
                "updatedAt", "c.updated_at", "sortNo", "c.sort_no", "id", "c.id")
                : Map.of("id", "id", "updatedAt", "updated_at", "sortNo", "sort_no");
        String order = sorts.getOrDefault(query.getOrDefault("sortField", ""), alias + "sort_no");
        String direction = "desc".equalsIgnoreCase(query.get("sortOrder")) || "descending".equals(query.get("sortOrder")) ? " DESC" : " ASC";
        String from = channel ? JOIN : " FROM " + table;

            if (Boolean.TRUE.equals(parameters.get("count")))
                return org.token.repository.support.SqlParameters.bind("SELECT COUNT(*)" + from + where, args, parameters);
            args.add(size); args.add((long) (page - 1) * size);
            return org.token.repository.support.SqlParameters.bind((channel ? SELECT : "SELECT *") + from + where + " ORDER BY " + order + direction
                    + "," + alias + "id DESC LIMIT ? OFFSET ?", args, parameters);
        }
        private static String table(String kind) {
            return switch (kind) {
                case "currencies" -> "compute_currency";
                case "currency-assets" -> "compute_currency_asset";
                case "currency-networks" -> "compute_currency_network";
                default -> throw new org.token.exception.BusinessException("server.compute.notFound");
            };
        }
        private static int integer(Object value, int minimum, int maximum) { long n=number(value); if(n<minimum || n>maximum) invalid(); return (int)n; }
        private static boolean bool(Object value) { if(!Set.of("true","false","1","0").contains(text(value).toLowerCase(Locale.ROOT))) invalid(); return enabled(value); }
        private static void invalid() { throw new org.token.exception.BusinessException("server.compute.invalidInput"); }
    private static void eq(StringBuilder where, List<Object> args, String column, Object value) { if (!text(value).isEmpty()) { where.append(" AND ").append(column).append("=?"); args.add(text(value)); } }
    private static void flag(StringBuilder where, List<Object> args, String column, Object value) { if (!text(value).isEmpty()) { where.append(" AND ").append(column).append("=?"); args.add(bool(value)); } }
    private static void filterTime(StringBuilder where, List<Object> args, Object value, String column, String operator) {
        if (text(value).isEmpty()) return;
        try { where.append(" AND ").append(column).append(operator).append("?"); args.add(LocalDateTime.ofInstant(Instant.parse(text(value)), ZoneOffset.UTC)); }
        catch (Exception exception) { throw new org.token.exception.BusinessException("server.compute.invalidInput"); }
    }
    }
}
