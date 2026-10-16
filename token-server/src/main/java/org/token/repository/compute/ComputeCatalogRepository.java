package org.token.repository.compute;

import java.util.*;
import org.token.exception.BusinessException;
import static org.token.repository.compute.ComputeData.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeCatalogMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeCatalogService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeCatalogRepository {
    private final ComputeCatalogMapper mapper;

    @Autowired
    public ComputeCatalogRepository(ComputeCatalogMapper mapper) { this.mapper = mapper; }

    public ComputeCatalogRepository(ComputeData data) {
        this(data.mapper(ComputeCatalogMapper.class));
    }

    public List<Map<String, Object>> catalogLevelRows(Object... args) {
        return ComputeData.normalizeRows(mapper.catalogLevelRows(args));
    }
    public List<Map<String,Object>> vipSettings() { return ComputeData.normalizeRows(mapper.vipSettings()); }

    public List<Map<String, Object>> catalogProductRows(Object... args) {
        return ComputeData.normalizeRows(mapper.catalogProductRows(args));
    }

    public List<Map<String, Object>> homeCatalogProductRows(Object... args) {
        return ComputeData.normalizeRows(mapper.homeCatalogProductRows(args));
    }

    public List<Map<String, Object>> publicModelsModelRows(Object... args) {
        return ComputeData.normalizeRows(mapper.publicModelsModelRows(args));
    }

    public List<Map<String, Object>> saveProviderRows(Object... args) {
        return ComputeData.normalizeRows(mapper.saveProviderRows(args));
    }

    public Map<String, Object> saveMemberRow(Object... args) {
        return RepositoryResults.required(mapper.saveMemberRow(args));
    }

    public Integer saveLevelCount(Object... args) {
        return RepositoryResults.scalar(mapper.saveLevelCount(args), Integer.class);
    }

    public Map<String, Object> saveCurrencyRow(Object... args) {
        return RepositoryResults.required(mapper.saveCurrencyRow(args));
    }

    public List<Map<String, Object>> requireProviderProviderRows(Object... args) {
        return ComputeData.normalizeRows(mapper.requireProviderProviderRows(args));
    }

    public List<Map<String, Object>> settingsSettingRows(Object... args) {
        return ComputeData.normalizeRows(mapper.settingsSettingRows(args));
    }

    public Long settingsRewardConfigurationCount(Object... args) {
        var configuration=mapper.settingsRewardConfigurationCount(args);
        if(configuration==null||configuration.isEmpty())return 0L;
        var row=org.token.repository.support.DomainSettingDocument.unpack(
                RepositoryResults.required(configuration),
                "configurationJson","activatedAt","activatedAt");
        return row.get("activatedAt")==null?0L:1L;
    }

    public List<Map<String, Object>> saveSettingsSettingRows(Object... args) {
        return ComputeData.normalizeRows(mapper.saveSettingsSettingRows(args));
    }

    public List<Map<String, Object>> saveSettingsLevelRows(Object... args) {
        return ComputeData.normalizeRows(mapper.saveSettingsLevelRows(args));
    }

    public int saveSettingsSettingWrite(Object... args) {
        return mapper.saveSettingsSettingWrite(args);
    }

    public Map<String, Object> overviewMemberCount(Object... args) {
        return RepositoryResults.required(mapper.overviewMemberCount(args));
    }

    public Map<String, Object> overviewMemberCount2(Object... args) {
        return RepositoryResults.required(mapper.overviewMemberCount2(args));
    }

    public Map<String, Object> overviewMemberCount3(Object... args) {
        return RepositoryResults.required(mapper.overviewMemberCount3(args));
    }

    public Map<String, Object> overviewMemberCount4(Object... args) {
        return RepositoryResults.required(mapper.overviewMemberCount4(args));
    }

    public List<Map<String, Object>> overviewCurrencyRows(Object... args) {
        return ComputeData.normalizeRows(mapper.overviewCurrencyRows(args));
    }

    public Map<String, Object> updateMemberMemberRow(Object... args) {
        return RepositoryResults.required(mapper.updateMemberMemberRow(args));
    }

    public Integer updateMemberLevelCount(Object... args) {
        return RepositoryResults.scalar(mapper.updateMemberLevelCount(args), Integer.class);
    }

    public Map<String, Object> updateMemberMemberRow2(Object... args) {
        return RepositoryResults.required(mapper.updateMemberMemberRow2(args));
    }

    public List<Map<String,Object>> list(String resource) {
        Queries.list(fields("resource",resource));
        return ComputeData.normalizeRows(mapper.list(resource));
    }
    public Map<String,Object> page(String resource,Long memberId,Map<String,String> query) {
        var rows=fields("resource",resource,"memberId",memberId,"query",query,"count",false);
        rows.put("statement",Queries.preparePage(rows));
        var count=fields("resource",resource,"memberId",memberId,"query",query,"count",true);
        count.put("statement",Queries.preparePage(count));
        long total=RepositoryResults.scalar(mapper.page(count),Long.class);
        return fields("records",ComputeData.normalizeRows(mapper.page(rows)),"total",total);
    }
    public static final class Queries {
        private Queries() { }
    private static final Map<String, String> TABLES = Map.of("products", "compute_product", "models", "compute_model",
            "levels", "compute_level", "providers", "compute_provider", "fee-rules", "compute_fee_rule", "transfer-rules", "compute_transfer_rule");
        public static String list(Map<String,Object> parameters) {
            String resource=(String)parameters.get("resource");
        String table = TABLES.get(resource);
        if (table == null) throw new BusinessException("server.compute.notFound");
        String sql = switch(resource) {
            case "products" -> "SELECT * FROM compute_product WHERE type='GPU' ORDER BY id";
            case "models" -> "SELECT m.*,p.name AS provider,'USD' AS price_currency FROM compute_model m JOIN compute_provider p ON p.id=m.provider_id ORDER BY m.id";
            case "fee-rules" -> "SELECT r.*,m.username AS username,l.name AS level_name,c.id AS channel_id FROM compute_fee_rule r LEFT JOIN compute_member m ON m.id=r.member_id LEFT JOIN compute_level l ON l.id=r.level LEFT JOIN compute_currency c ON c.currency=r.currency AND c.network=r.network ORDER BY r.id";
            default -> "SELECT * FROM " + table + " ORDER BY id";
        };

            return sql;
        }
        public static String page(Map<String,Object> parameters) { return (String)parameters.get("statement"); }
        @SuppressWarnings("unchecked")
        private static String preparePage(Map<String,Object> parameters) {
            String resource=(String)parameters.get("resource");Long memberId=(Long)parameters.get("memberId");
            Map<String,String> query=(Map<String,String>)parameters.get("query");
        String table = switch (resource) {
            case "members" -> "compute_member"; case "orders" -> "compute_order"; case "deposits" -> "compute_deposit";
            case "withdrawals" -> "compute_withdrawal"; case "ledger" -> ComputeLedgerDetailsRepository.table()+" ledger_details"; case "job-runs" -> "compute_job_run";
            default -> throw new BusinessException("server.compute.notFound");
        };
        List<String> conditions = new ArrayList<>(); List<Object> values = new ArrayList<>();
        if (memberId != null) { conditions.add("member_id=?"); values.add(memberId); }
        else if (!text(query.get("memberId")).isEmpty() && !"job-runs".equals(resource)) { conditions.add("members".equals(resource) ? "id=?" : "member_id=?"); values.add(number(query.get("memberId"))); }
        if (!text(query.get("status")).isEmpty() && Set.of("orders", "deposits", "withdrawals", "job-runs").contains(resource)) { conditions.add("status=?"); values.add(query.get("status")); }
        // 用户列表的合并筛选只影响读取；保留每条原始状态及管理员既有 status 语义。
        if (memberId != null && !text(query.get("statusGroup")).isEmpty()) {
            String group = query.get("statusGroup");
            if (!Set.of("deposits", "withdrawals").contains(resource) || !Set.of("SUCCESS", "NOT_SUCCESS").contains(group))
                throw new BusinessException("server.compute.invalidInput");
            conditions.add(paymentSuccessCase() + ("SUCCESS".equals(group) ? "=1" : "=0"));
        }
        if (!text(query.get("status")).isEmpty() && "members".equals(resource)) {
            if (!Set.of("ENABLED", "DISABLED").contains(query.get("status"))) throw new BusinessException("server.compute.invalidInput");
            conditions.add("enabled=?"); values.add("ENABLED".equals(query.get("status")));
        }
        if (!text(query.get("jobId")).isEmpty() && "job-runs".equals(resource)) { conditions.add("job_id=?"); values.add(number(query.get("jobId"))); }
        if (!text(query.get("currency")).isEmpty() && Set.of("orders", "deposits", "withdrawals", "ledger").contains(resource)) { conditions.add("currency=?"); values.add(query.get("currency")); }
        if (Set.of("deposits", "withdrawals").contains(resource)) {
            if (!text(query.get("network")).isEmpty()) { conditions.add("network=?"); values.add(limited(query.get("network"), 32)); }
            if (!text(query.get("orderNo")).isEmpty()) { conditions.add("order_no=?"); values.add(limited(query.get("orderNo"), 48)); }
            if (!text(query.get("walletType")).isEmpty()) {
                String wallet = query.get("walletType");
                if (!("deposits".equals(resource) ? Set.of("COMPUTE", "PROJECT") : Set.of("EARNINGS")).contains(wallet))
                    throw new BusinessException("server.compute.invalidWallet");
                if ("deposits".equals(resource)) { conditions.add("wallet_type=?"); values.add(wallet); }
            }
        }
        if ("ledger".equals(resource) && "INCOME".equals(query.get("category"))) conditions.add("kind IN ('ORDER_INCOME','AUTO_INCOME','TEAM_REWARD','TEAM_REWARD_TEST','ACTIVITY_REWARD','CHECKIN_REWARD')");
        if ("ledger".equals(resource) && "AUTO_INCOME".equals(query.get("category"))) conditions.add("kind='AUTO_INCOME'");
        if ("ledger".equals(resource)) {
            String filter=ComputeLedgerDetailsRepository.filters(query,"",values);
            if(!filter.isEmpty()) conditions.add(filter.substring(5));
            if(!text(query.get("walletType")).isEmpty()) {
                if(!Set.of("COMPUTE","PROJECT","EARNINGS").contains(query.get("walletType"))) throw new BusinessException("server.compute.invalidWallet");
                conditions.add("wallet_type=?");values.add(query.get("walletType"));
            }
        }
        for (String field : List.of("startTime", "endTime")) if (!text(query.get(field)).isEmpty()) {
            try { conditions.add("created_at" + (field.equals("startTime") ? ">=?" : "<=?")); values.add(LocalDateTime.ofInstant(Instant.parse(query.get(field)), ZoneOffset.UTC)); }
            catch (Exception exception) { throw new BusinessException("server.compute.invalidInput"); }
        }
        if (!text(query.get("keyword")).isEmpty()) {
            String keyword = "%" + limited(text(query.get("keyword")), 100) + "%";
            if ("members".equals(resource)) { conditions.add("(username LIKE ? OR email LIKE ? OR phone LIKE ?)"); values.addAll(List.of(keyword, keyword, keyword)); }
            else if ("orders".equals(resource)) { conditions.add("product_name LIKE ?"); values.add(keyword); }
            else { conditions.add("CAST(id AS CHAR) LIKE ?"); values.add(keyword); }
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);

            if(Boolean.TRUE.equals(parameters.get("count")))
                return org.token.repository.support.SqlParameters.bind("SELECT COUNT(*) FROM "+table+where,values,parameters);
        int page = integer(new HashMap<>(query), "page", 1, 1000000, 1), size = integer(new HashMap<>(query), "size", 1, 100, 20);
        values.add(size); values.add((long) (page - 1) * size);
        String columns = "members".equals(resource) ? "id,username,email,phone,level,enabled,invite_code,inviter_id,created_at,updated_at" : "*";

            return org.token.repository.support.SqlParameters.bind("SELECT "+columns+" FROM "+table+where+" ORDER BY id DESC LIMIT ? OFFSET ?",values,parameters);
        }
        private static int integer(Map<String,Object> request,String key,int min,int max,int fallback) {
            long value=request.get(key)==null?fallback:number(request.get(key));
            if(value<min||value>max)throw new BusinessException("server.compute.invalidInput");return (int)value;
        }
        /** 与用户 paymentState 的终态优先级一致；未成功包含处理中，不等同失败。 */
        private static String paymentSuccessCase() {
            return "(CASE WHEN payment_status='MCAG_DISABLED_ADDRESS' OR payment_status='MCAG_REVERSED' OR chain_status='ORPHANED' "
                    + "OR payment_status IN ('MCAG_HELD','MCAG_REVIEW','MCAG_PENDING','MCAG_FAILED') THEN 0 "
                    + "WHEN payment_status IN ('MCAG_VERIFIED','MCAG_SUCCEEDED') THEN 1 "
                    + "WHEN status IN ('APPROVED','CREDITED','PAID') THEN 1 ELSE 0 END)";
        }
        private static String limited(String value,int maximum) {
            if(value.length()>maximum)throw new BusinessException("server.compute.invalidInput");return value;
        }
    }
}
