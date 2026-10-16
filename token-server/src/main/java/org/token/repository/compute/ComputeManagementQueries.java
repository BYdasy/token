package org.token.repository.compute;

import org.token.exception.BusinessException;
import org.token.model.query.ManagementMemberQuery;
import org.token.model.query.MemberScope;
import org.token.repository.support.SqlParameters;
import java.time.*;
import java.math.*;
import java.util.*;
import static org.token.repository.compute.ComputeData.*;

/** 管理端命名查询的SQL构建；只接收服务已授权范围，不回调任何业务或认证服务。 */
public final class ComputeManagementQueries {
    private ComputeManagementQueries() { }
    private record PagePlan(String from,String columns,String where,List<Object> args,Map<String,String> query,String order) { }
    /** 在进入 MyBatis 前完成校验，避免业务参数错误被 Provider 的反射异常包装。 */
    public static void preparePage(String kind,Map<String,Object> parameters) {
        PagePlan plan=switch(kind) {
            case "groups" -> groupsPlan(parameters); case "agents" -> agentsPlan(parameters);
            case "bindings" -> bindingsPlan(parameters); case "members" -> membersPlan(parameters);
            case "teamMembers" -> teamMembersPlan(parameters);
            case "finances" -> financesPlan(parameters); case "addresses" -> addressesPlan(parameters);
            default -> throw new IllegalArgumentException("不支持的管理查询");
        };
        integer(plan.query().get("page"),1,1000000,1);
        integer(plan.query().get("size"),1,100,20);
        parameters.put("pagePlan",plan);
    }
    private static PagePlan prepared(Map<String,Object> parameters) {
        return (PagePlan)Objects.requireNonNull(parameters.get("pagePlan"),"分页查询必须先在 Repository 校验");
    }
    private static ManagementMemberQuery search(Map<String,Object> p){return (ManagementMemberQuery)p.get("search");}
    private static String page(PagePlan plan,boolean count,Map<String,Object> p){
        List<Object> args=new ArrayList<>(plan.args());
        if(count)return SqlParameters.bind("SELECT COUNT(*) FROM "+plan.from()+plan.where(),args,p);
        int page=integer(plan.query().get("page"),1,1000000,1),size=integer(plan.query().get("size"),1,100,20);
        args.add(size);args.add((long)(page-1)*size);
        return SqlParameters.bind("SELECT "+plan.columns()+" FROM "+plan.from()+plan.where()+" ORDER BY "+plan.order()+" LIMIT ? OFFSET ?",args,p);
    }
    private static PagePlan groupsPlan(Map<String,Object> p){
        ManagementMemberQuery search=search(p);Map<String,String> query=search.filters();MemberScope scope=search.scope();
        List<Object> args=new ArrayList<>();String where=" WHERE 1=1"+MemberScopeQuery.ownerFilter("g.owner_member_id",scope,args,true);
        where+=textFilter(query,"keyword","g.name",args)+statusFilter(query,"g.enabled",args);
        String from="(SELECT id,name,owner_member_id,note,sort_order,enabled,created_at,updated_at,0 built_in FROM compute_member_group UNION ALL SELECT 0,'未分组',NULL,'',-100001,1,NULL,NULL,1) g LEFT JOIN compute_member owner ON owner.id=g.owner_member_id";
        return new PagePlan(from,"g.*,owner.username owner_member_name",where,args,query,"g.sort_order,g.id DESC");

    }

    public static String groupsCount(Map<String,Object> p){return page(prepared(p),true,p);}
    public static String groupsPage(Map<String,Object> p){return page(prepared(p),false,p);}

    private static PagePlan agentsPlan(Map<String,Object> p){
        ManagementMemberQuery search=search(p);Map<String,String> query=search.filters();MemberScope scope=search.scope();
        List<Object> args=new ArrayList<>(); String where=" WHERE 1=1"+MemberScopeQuery.memberFilter("m.id",scope,args);
        if (!text(query.get("keyword")).isEmpty()) {
            where+=" AND (m.username LIKE ? OR m.invite_code LIKE ?)"; String value="%"+limited(query.get("keyword"),100)+"%"; args.add(value);args.add(value);
        }
        where+=statusFilter(query,"m.enabled",args)+textFilter(query,"username","m.username",args);
        if(!text(query.get("memberId")).isEmpty()){where+=" AND m.id=?";args.add(number(query.get("memberId")));}
        if(!text(query.get("inviteCode")).isEmpty()){where+=" AND m.invite_code=?";args.add(limited(query.get("inviteCode"),32));}
        if(!text(query.get("parentMemberId")).isEmpty()) {long parent=number(query.get("parentMemberId"));where+=" AND m.inviter_id=?";args.add(parent);}
        if("true".equalsIgnoreCase(query.get("roots")) && text(query.get("parentMemberId")).isEmpty()) {
            if(scope.global())where+=" AND m.inviter_id IS NULL";
            else {var roots=scope.roots();where+=" AND m.id IN ("+(roots.isEmpty()?"NULL":placeholders(roots.size()))+")";args.addAll(roots);}
        }
        return new PagePlan("compute_member m","m.id,m.username,m.username name,m.invite_code,m.inviter_id,(SELECT p.username FROM compute_member p WHERE p.id=m.inviter_id) inviter_name,m.enabled",where,args,query,"m.id DESC");

    }

    public static String agentsCount(Map<String,Object> p){return page(prepared(p),true,p);}
    public static String agentsPage(Map<String,Object> p){return page(prepared(p),false,p);}

    private static PagePlan bindingsPlan(Map<String,Object> p){
        ManagementMemberQuery search=search(p);Map<String,String> query=search.filters();MemberScope scope=search.scope();
        List<Object> args=new ArrayList<>();
        String where=" WHERE 1=1"+textFilter(query,"keyword","a.username",args);
        if (!text(query.get("adminUid")).isEmpty()) { where+=" AND a.uid=?";args.add(number(query.get("adminUid"))); }
        return new PagePlan("ad_master a","a.uid admin_uid,a.username,a.state,a.scope_configured_at",where,args,query,"a.uid");

    }

    public static String bindingsCount(Map<String,Object> p){return page(prepared(p),true,p);}
    public static String bindingsPage(Map<String,Object> p){return page(prepared(p),false,p);}

    /** 团队入口仅分页身份，不为随后会丢弃的个人资金/产品字段执行聚合。 */
    private static PagePlan teamMembersPlan(Map<String,Object> p) {
        var search=search(p);var query=search.filters();List<Object> args=new ArrayList<>();
        String where=memberWhere(query,args,true,search.scope());
        String sort=switch(text(query.getOrDefault("sortBy","id"))) {
            case "id" -> "m.id";case "username" -> "m.username";case "createdAt" -> "m.created_at";case "level" -> "m.level";
            default -> throw new BusinessException("server.compute.management.invalidFilter");
        };
        return new PagePlan("compute_member m LEFT JOIN compute_member_group g ON g.id=m.group_id",
                "m.id,m.username,m.inviter_id,m.group_id,g.name group_name",where,args,query,sort+" "+sortDirection(query));
    }

    private static PagePlan membersPlan(Map<String,Object> p){
        ManagementMemberQuery search=search(p);Map<String,String> query=search.filters();MemberScope scope=search.scope();
        String currency=(String)p.get("currency");
        List<Object> args = new ArrayList<>(List.of(currency, currency, currency, currency));
        String from = """
                compute_member m
                LEFT JOIN compute_member_group g ON g.id=m.group_id
                LEFT JOIN compute_member i ON i.id=m.inviter_id
                LEFT JOIN compute_level l ON l.id=m.level
                LEFT JOIN (SELECT member_id,SUM(available) available,SUM(frozen) frozen,
                    SUM(CASE WHEN wallet_type='COMPUTE' THEN available ELSE 0 END) compute_available,
                    SUM(CASE WHEN wallet_type='PROJECT' THEN available ELSE 0 END) project_available,
                    SUM(CASE WHEN wallet_type='EARNINGS' THEN available ELSE 0 END) earnings_available
                    FROM compute_wallet WHERE currency=? GROUP BY member_id) w ON w.member_id=m.id
                LEFT JOIN (SELECT member_id,SUM(CASE WHEN accounting_version=2 THEN accounting_amount ELSE amount END) total_deposits,COUNT(*) deposit_count,MAX(reviewed_at) last_deposit_at
                    FROM compute_deposit WHERE CASE WHEN accounting_version=2 THEN 'USDT' ELSE currency END=? AND status='APPROVED' GROUP BY member_id) d ON d.member_id=m.id
                LEFT JOIN (SELECT member_id,SUM(CASE WHEN accounting_version=2 THEN accounting_net_amount ELSE net_amount END) total_withdrawals,COUNT(*) withdrawal_count,MAX(reviewed_at) last_withdrawal_at
                    FROM compute_withdrawal WHERE CASE WHEN accounting_version=2 THEN 'USDT' ELSE currency END=? AND status='APPROVED' GROUP BY member_id) v ON v.member_id=m.id
                LEFT JOIN (SELECT member_id,SUM(CASE WHEN status IN ('ACTIVE','RUNNING') AND contributes_principal=1 THEN amount ELSE 0 END) active_principal,
                    SUM(paid_income) total_income,COUNT(CASE WHEN is_product=1 THEN 1 END) order_count,COUNT(CASE WHEN status='ACTIVE' AND is_product=1 THEN 1 END) active_orders
                    FROM (SELECT member_id,status,amount,paid_income,currency,1 is_product,1 contributes_principal FROM compute_order UNION ALL
                        SELECT member_id,status,amount,paid_income,currency,0 is_product,0 contributes_principal FROM compute_auto_contract) investments
                    WHERE currency=? GROUP BY member_id) o ON o.member_id=m.id
                """;
        String where = memberWhere(query,args,true,scope);
        if(!text(query.get("online")).isEmpty()) {
            boolean online=booleanInput(query.get("online"));
            where+=" AND "+(online?"":"NOT ")+"EXISTS (SELECT 1 FROM compute_member_session s WHERE s.member_id=m.id AND "+onlinePredicate(p,args)+")";
        }
        Map<String, String> money = Map.of("Available", "COALESCE(w.available,0)", "Frozen", "COALESCE(w.frozen,0)",
                "Deposits", "COALESCE(d.total_deposits,0)", "Withdrawals", "COALESCE(v.total_withdrawals,0)",
                "Principal", "COALESCE(o.active_principal,0)", "Income", "COALESCE(o.total_income,0)");
        for (var entry : money.entrySet()) where += amountRange(query, entry.getKey(), entry.getValue(), args, false);
        String columns = """
                m.id,m.username,m.email,m.phone,m.level,m.enabled,m.is_test,m.invitation_enabled,m.invite_code,m.inviter_id,m.group_id,m.created_at,m.updated_at,m.security_score,m.token_income_percent,m.remark,m.address_limit,(m.pay_password_hash IS NOT NULL) has_pay_password,
                g.name group_name,i.username inviter_name,l.name level_name,
                m.vip_started_at,m.vip_expires_at,l.vip_validity_days,
                m.registration_ip,m.registration_country,m.last_login_at,m.last_login_ip,m.last_login_country,
                m.user_language,
                COALESCE(w.available,0) available,COALESCE(w.frozen,0) frozen,COALESCE(w.compute_available,0) compute_available,
                COALESCE(w.available,0)+COALESCE(w.frozen,0) total_balance,
                COALESCE(w.project_available,0) project_available,COALESCE(w.earnings_available,0) earnings_available,
                COALESCE(d.total_deposits,0) total_deposits,COALESCE(d.deposit_count,0) deposit_count,d.last_deposit_at,
                COALESCE(d.total_deposits,0)-COALESCE(v.total_withdrawals,0) deposit_withdrawal_difference,
                COALESCE(v.total_withdrawals,0) total_withdrawals,COALESCE(v.withdrawal_count,0) withdrawal_count,v.last_withdrawal_at,
                COALESCE(o.active_principal,0) active_principal,COALESCE(o.total_income,0) total_income,
                COALESCE(o.order_count,0) order_count,COALESCE(o.active_orders,0) active_orders
                """;
        String sort = switch (text(query.getOrDefault("sortBy", "id"))) {
            case "id" -> "m.id"; case "createdAt" -> "m.created_at"; case "username" -> "m.username"; case "level" -> "m.level";
            case "available" -> "COALESCE(w.available,0)"; case "frozen" -> "COALESCE(w.frozen,0)";
            case "totalBalance" -> "COALESCE(w.available,0)+COALESCE(w.frozen,0)";
            case "totalDeposits" -> "COALESCE(d.total_deposits,0)"; case "totalWithdrawals" -> "COALESCE(v.total_withdrawals,0)";
            case "activePrincipal" -> "COALESCE(o.active_principal,0)"; case "totalIncome" -> "COALESCE(o.total_income,0)";
            default -> throw new BusinessException("server.compute.management.invalidFilter");
        };
        String direction = sortDirection(query);
        return new PagePlan(from, columns, where, args, query, sort + " " + direction + ("m.id".equals(sort) ? "" : ",m.id " + direction));

    }

    public static String membersCount(Map<String,Object> p){return page(prepared(p),true,p);}
    public static String membersPage(Map<String,Object> p){return page(prepared(p),false,p);}

    private static PagePlan financesPlan(Map<String,Object> p){
        ManagementMemberQuery search=search(p);Map<String,String> query=search.filters();MemberScope scope=search.scope();
        String resource=(String)p.get("resource");
        String table = switch (resource) {
            case "orders" -> "compute_order"; case "deposits" -> "compute_deposit";
            case "withdrawals" -> "compute_withdrawal"; case "ledger" -> ComputeLedgerDetailsRepository.table();
            default -> throw new BusinessException("server.compute.notFound");
        };
        Map<String, String> memberQuery = new HashMap<>(query); memberQuery.remove("keyword");
        List<Object> args = new ArrayList<>(); String where = memberWhere(memberQuery,args,false,scope) + dates(query, "f.created_at", args);
        if (!text(query.get("keyword")).isEmpty()) {
            String keyword = "%" + limited(query.get("keyword"), 100) + "%";
            where += " AND (CAST(f.id AS CHAR) LIKE ? OR CAST(m.id AS CHAR) LIKE ? OR m.username LIKE ? OR m.email LIKE ? OR m.phone LIKE ?";
            args.addAll(List.of(keyword, keyword, keyword, keyword, keyword));
            if ("orders".equals(resource)) { where += " OR f.product_name LIKE ?"; args.add(keyword); }
            where += ")";
        }
        if (!text(query.get("status")).isEmpty() && !"ledger".equals(resource)) { where += " AND f.status=?"; args.add(query.get("status")); }
        if (!text(query.get("collectionState")).isEmpty()) {
            String collectionState=text(query.get("collectionState"));
            if (!"deposits".equals(resource) || !Set.of("COLLECTED","NOT_COLLECTED").contains(collectionState))
                throw new BusinessException("server.compute.management.invalidFilter");
            where += depositCollectionFilter("COLLECTED".equals(collectionState),args);
        }
        if (!text(query.get("attentionIgnored")).isEmpty()) {
            if (!"withdrawals".equals(resource)) throw new BusinessException("server.compute.management.invalidFilter");
            where += " AND f.attention_ignored=?"; args.add(booleanInput(query.get("attentionIgnored")) ? 1 : 0);
        }
        String currency=text(query.get("currency"));if(!currency.isEmpty()){where+=" AND f.currency=?";args.add(currency);}
        if(!text(query.get("orderNo")).isEmpty()){where+=" AND f.order_no=?";args.add(limited(query.get("orderNo"),48));}
        if(!text(query.get("gatewayReference")).isEmpty()) {
            if(!"withdrawals".equals(resource))throw new BusinessException("server.compute.management.invalidFilter");
            where+=" AND EXISTS(SELECT 1 FROM compute_mcag_payout p WHERE p.withdrawal_id=f.id AND p.payout_id=?)";
            args.add(limited(query.get("gatewayReference"),128));
        }
        if(!text(query.get("receiverAddress")).isEmpty()) {
            if(!"withdrawals".equals(resource))throw new BusinessException("server.compute.management.invalidFilter");
            String recipient=limited(query.get("receiverAddress"),200);
            where+=recipient.matches("(?i)0x[0-9a-f]{40}")?" AND LOWER(f.address)=LOWER(?)":" AND f.address=?";
            args.add(recipient);
        }
        if("orders".equals(resource)&&!text(query.get("productId")).isEmpty()){where+=" AND f.product_id=?";args.add(number(query.get("productId")));}
        String firstDeposit="f.status='APPROVED' AND NOT EXISTS(SELECT 1 FROM compute_deposit earlier WHERE earlier.member_id=f.member_id AND earlier.status='APPROVED' AND (earlier.reviewed_at<f.reviewed_at OR (earlier.reviewed_at=f.reviewed_at AND earlier.id<f.id)))";
        if("deposits".equals(resource)&&!text(query.get("firstDeposit")).isEmpty())where+=" AND "+(booleanInput(query.get("firstDeposit"))?"":"NOT ")+"("+firstDeposit+")";
        if (!text(query.get("network")).isEmpty()) {
            if (!Set.of("deposits", "withdrawals").contains(resource)) throw new BusinessException("server.compute.management.invalidFilter");
            where += " AND f.network=?"; args.add(query.get("network"));
        }
        if (!text(query.get("txHash")).isEmpty()) {
            if (!Set.of("deposits", "withdrawals").contains(resource)) throw new BusinessException("server.compute.management.invalidFilter");
            where += " AND f.tx_hash=?"; args.add(limited(query.get("txHash"), 200));
        }
        if (!text(query.get("walletType")).isEmpty()) {
            if ("withdrawals".equals(resource)) throw new BusinessException("server.compute.management.invalidFilter");
            String wallet = query.get("walletType"); if (!Set.of("COMPUTE", "PROJECT", "EARNINGS").contains(wallet)) throw new BusinessException("server.compute.invalidWallet");
            where += " AND f." + ("orders".equals(resource) ? "source_wallet" : "wallet_type") + "=?"; args.add(wallet);
        }
        where += amountRange(query, "Amount", "ledger".equals(resource)?"CASE WHEN f.kind='WITHDRAW_APPROVED' THEN f.frozen_change ELSE f.amount END":"f.amount", args, "ledger".equals(resource));
        if("ledger".equals(resource)) where+=ComputeLedgerDetailsRepository.filters(query,"f.",args);
        if ("ledger".equals(resource) && "INCOME".equals(query.get("category"))) where += " AND f.kind IN ('ORDER_INCOME','AUTO_INCOME','TEAM_REWARD','TEAM_REWARD_TEST','ACTIVITY_REWARD','CHECKIN_REWARD')";
        if("ledger".equals(resource)&&!text(query.get("kind")).isEmpty()){
            String kind=limited(query.get("kind"),40);
            if("TEAM_REWARD".equals(kind))where+=" AND f.kind IN ('TEAM_REWARD','TEAM_REWARD_TEST')";
            else {where+=" AND f.kind=?";args.add(kind);}
        }
        return new PagePlan(table + " f JOIN compute_member m ON m.id=f.member_id LEFT JOIN compute_member_group g ON g.id=m.group_id LEFT JOIN compute_member i ON i.id=m.inviter_id",
                "f.*,m.id uid,m.username,m.is_test,m.group_id,m.inviter_id,i.username inviter_name,g.name group_name"
                        +(Set.of("deposits","withdrawals").contains(resource)?",m.last_login_country":"")
                        +("deposits".equals(resource)?",CASE WHEN "+firstDeposit+" THEN 1 ELSE 0 END first_deposit":""), where, args, query, "f.id DESC");

    }

    public static String financesCount(Map<String,Object> p){return page(prepared(p),true,p);}
    public static String financesPage(Map<String,Object> p){return page(prepared(p),false,p);}

    /** 与充值页投影同义：从逐笔订单定位归集批次；未知、关联冲突及手工充值不推断为未归集。 */
    private static String depositCollectionFilter(boolean collected,List<Object> args) {
        String snapshot="CASE WHEN JSON_VALID(co.snapshot_json) THEN co.snapshot_json ELSE '{}' END";
        String batch="JSON_EXTRACT("+snapshot+",'$.batch_id')";
        String batchId="JSON_UNQUOTE("+batch+")";
        String order="SELECT 1 FROM compute_mcag_deposit_order co WHERE co.record_id=f.id";
        String status=collected?"=CAST('SUCCEEDED' AS BINARY)"
                :" IN (CAST('WAITING' AS BINARY),CAST('QUEUED' AS BINARY),CAST('CONFIRMING' AS BINARY),CAST('FAILED' AS BINARY))";
        String same="cb.merchant_id=co.merchant_id AND cb.environment=co.environment AND cb.batch_id="+batchId
                +" AND CAST(cb.merchant_id AS BINARY)=CAST(co.merchant_id AS BINARY)"
                +" AND CAST(cb.environment AS BINARY)=CAST(co.environment AS BINARY)"
                +" AND CAST(cb.batch_id AS BINARY)=CAST("+batchId+" AS BINARY)";
        for(String field:List.of("customer_id","address_id","asset_id","network_id"))
            same+=" AND TRIM(co."+field+")<>'' AND CAST(cb."+field+" AS BINARY)=CAST(co."+field+" AS BINARY)";
        // 批次键在既有表中为ASCII；类型、空白、控制字符和长度须先合法，不能把数字/对象截断后关联。
        String validId="JSON_TYPE("+batch+")='STRING' AND TRIM("+batchId+")<>''"
                +" AND CHAR_LENGTH("+batchId+")<=128 AND NOT REGEXP_LIKE("+batchId+",?)";
        args.add("\\p{Cc}");
        String matched=validId+" AND EXISTS(SELECT 1 FROM compute_mcag_collection_batch cb WHERE "+same
                +" AND CAST(cb.accounting_model AS BINARY)=CAST('RECEIPT_SWEEP' AS BINARY)"
                +" AND CAST(JSON_UNQUOTE(JSON_EXTRACT(CASE WHEN JSON_VALID(cb.collection_json) THEN cb.collection_json ELSE '{}' END,'$.purpose')) AS BINARY)=CAST('CUSTOMER_DEPOSIT' AS BINARY)"
                +" AND CAST(TRIM(cb.sweep_status) AS BINARY)"+status+")";
        String noBatch="JSON_TYPE("+batch+")='NULL' OR (JSON_TYPE("+batch+")='STRING' AND CAST("+batchId+" AS BINARY)=CAST('' AS BINARY))";
        return " AND EXISTS("+order+" AND ("+(collected?matched:"("+noBatch+") OR ("+matched+")")+"))";
    }

    /** 只统计当前页已授权用户的成功历史；旧单使用固定快照，不能拿原币或当前汇率补入USD总额。 */
    public static String memberPaymentHistory(Map<String,Object> p) {
        @SuppressWarnings("unchecked") List<Long> ids=(List<Long>)p.get("ids");
        MemberScope scope=(MemberScope)p.get("scope");
        List<Object> args=new ArrayList<>();List<String> queries=new ArrayList<>();
        for(String kind:List.of("DEPOSIT","WITHDRAWAL")) {
            boolean deposit="DEPOSIT".equals(kind);
            String table=deposit?"compute_deposit":"compute_withdrawal";
            String amount="CASE WHEN f.accounting_version=2 THEN CASE WHEN f.accounting_currency='USD' THEN f."+(deposit?"accounting_amount":"accounting_net_amount")+" END"
                    +" ELSE s."+(deposit?"usd_amount":"usd_net_amount")+" END";
            String where=" WHERE f.status='APPROVED' AND f.member_id IN ("+placeholders(ids.size())+")";
            args.addAll(ids);where+=MemberScopeQuery.memberFilter("m.id",scope,args);
            queries.add("SELECT f.member_id,'"+kind+"' payment_type,COALESCE(SUM("+amount+"),0) amount,"
                    +"SUM(CASE WHEN ("+amount+") IS NULL THEN 1 ELSE 0 END) unpriced_count FROM "+table+" f"
                    +" JOIN compute_member m ON m.id=f.member_id LEFT JOIN compute_finance_snapshot s"
                    +" ON s.business_type='"+kind+"' AND s.business_id=f.id"+where+" GROUP BY f.member_id");
        }
        return SqlParameters.bind(String.join(" UNION ALL ",queries),args,p);
    }

    private static PagePlan addressesPlan(Map<String,Object> p){
        ManagementMemberQuery search=search(p);Map<String,String> query=search.filters();MemberScope scope=search.scope();
        List<Object> args = new ArrayList<>(); String where = memberWhere(query,args,false,scope) + dates(query, "d.created_at", args) + statusFilter(query, "d.enabled", args);
        for (String field : List.of("currency", "network")) if (!text(query.get(field)).isEmpty()) { where += " AND d." + field + "=?"; args.add(query.get(field)); }
        if (!text(query.get("address")).isEmpty()) { where += " AND d.address LIKE ?"; args.add("%" + limited(query.get("address"), 200) + "%"); }
        return new PagePlan("compute_address d JOIN compute_member m ON m.id=d.member_id LEFT JOIN compute_member_group g ON g.id=m.group_id",
                "d.*,m.username,m.group_id,g.name group_name", where, args, query, "d.id DESC");

    }

    public static String addressesCount(Map<String,Object> p){return page(prepared(p),true,p);}
    public static String addressesPage(Map<String,Object> p){return page(prepared(p),false,p);}

    private static String onlinePredicate(Map<String,Object> p,List<Object> args){
        return ComputeOnlineRepository.predicate((LocalDateTime)p.get("observedAt"),(LocalDateTime)p.get("onlineSince"),(LocalDateTime)p.get("idleSince"),args);
    }
    public static String groupOptions(Map<String,Object> p){
        List<Object> args=new ArrayList<>();String filter=MemberScopeQuery.ownerFilter("g.owner_member_id",(MemberScope)p.get("scope"),args,true);
        return SqlParameters.bind("SELECT g.id,g.name,g.owner_member_id,g.enabled FROM compute_member_group g WHERE 1=1"+filter+" ORDER BY g.sort_order,g.id LIMIT 500",args,p);
    }
    public static String groupMemberCount(Map<String,Object> p){
        long id=((Number)p.get("id")).longValue();List<Object> args=new ArrayList<>();
        String condition=id==0?"(m.group_id IS NULL OR m.group_id=0)":"m.group_id=?";
        if(id!=0)args.add(id);
        String filter=MemberScopeQuery.memberFilter("m.id",(MemberScope)p.get("scope"),args);
        return SqlParameters.bind("SELECT COUNT(*) FROM compute_member m WHERE "+condition+filter,args,p);
    }
    public static String bindingRoots(Map<String,Object> p){
        List<?> ids=(List<?>)p.get("ids");
        return SqlParameters.bind("SELECT id,username,invite_code,enabled FROM compute_member WHERE id IN ("+(ids.isEmpty()?"NULL":placeholders(ids.size()))+") ORDER BY id",ids,p);
    }
    public static String languages(Map<String,Object> p){
        List<?> ids=(List<?>)p.get("ids");
        return SqlParameters.bind("SELECT lang,name,chinese_name FROM translate_language WHERE LOWER(REPLACE(lang,'_','-')) IN ("+placeholders(ids.size())+")",ids,p);
    }
    public static String visibleMembers(Map<String,Object> p){
        List<?> ids=(List<?>)p.get("ids");List<Object> args=new ArrayList<>(ids);
        String filter=MemberScopeQuery.memberFilter("m.id",(MemberScope)p.get("scope"),args);
        return SqlParameters.bind("SELECT m.id FROM compute_member m WHERE m.id IN ("+placeholders(ids.size())+")"+filter,args,p);
    }
    public static String financialOwner(Map<String,Object> p){
        String table=(String)p.get("table");
        if(!Set.of("compute_deposit","compute_withdrawal","compute_address").contains(table))throw new IllegalArgumentException("不支持的资金记录类型");
        return "SELECT member_id FROM "+table+" WHERE id=#{id}";
    }
    private static String memberWhere(Map<String, String> query,List<Object> args,boolean memberDate,MemberScope scope) {
        String where = " WHERE 1=1" + MemberScopeQuery.memberFilter("m.id",scope,args);
        if(!text(query.get("isTest")).isEmpty()){where+=" AND m.is_test=?";args.add(booleanInput(query.get("isTest")));}
        for (String field : List.of("memberId", "groupId")) if (!text(query.get(field)).isEmpty()) {
            if("groupId".equals(field)&&"0".equals(query.get(field))){where+=" AND (m.group_id IS NULL OR m.group_id=0)";continue;}
            String column = "memberId".equals(field) ? "m.id" : "m.group_id";
            where += " AND " + column + "=?";
            args.add(number(query.get(field)));
        }
        String usernameMode=text(query.getOrDefault("usernameMatchMode","contains"));
        if(!Set.of("", "contains", "exact").contains(usernameMode))throw new BusinessException("server.compute.management.invalidFilter");
        if("exact".equals(usernameMode)&&!text(query.get("username")).isEmpty()) {
            where+=" AND m.username=?";args.add(text(query.get("username")));
        } else where+=textFilter(query,"username","m.username",args);
        for(String field:List.of("email","phone")) where+=textFilter(query,field,"m."+field,args);
        where+=countryFilter(query,"registrationCountry","m.registration_country",args);
        where+=countryFilter(query,"lastLoginCountry","m.last_login_country",args);
        where+=ipFilter(query,"registrationIp","m.registration_ip",args);
        where+=ipFilter(query,"lastLoginIp","m.last_login_ip",args);
        if(!text(query.get("usernames")).isEmpty()) {
            List<String> names=Arrays.stream(query.get("usernames").split("[,\\s]+")).filter(value->!value.isBlank()).distinct().toList();
            if(names.isEmpty()||names.size()>100||names.stream().anyMatch(value->value.length()>40))throw new BusinessException("server.compute.management.invalidFilter");
            where+=" AND m.username IN ("+placeholders(names.size())+")";args.addAll(names);
        }
        if(!text(query.get("topMemberId")).isEmpty()||!text(query.get("topName")).isEmpty()) {
            List<Object> rootsArgs=new ArrayList<>();String rootsWhere=" WHERE inviter_id IS NULL";
            if(!text(query.get("topMemberId")).isEmpty()){rootsWhere+=" AND id=?";rootsArgs.add(number(query.get("topMemberId")));}
            if(!text(query.get("topName")).isEmpty()){rootsWhere+=" AND username LIKE ?";rootsArgs.add("%"+limited(query.get("topName"),40)+"%");}
            where+=MemberScopeQuery.physicalRootCondition("m.id",rootsWhere,rootsArgs,args);
        }
        String teamRoot=text(query.getOrDefault("teamMemberId",query.get("agentId")));
        String teamName=text(query.getOrDefault("teamUsername",query.get("teamName")));
        if(!teamRoot.isEmpty()) { long root=number(teamRoot);where+=MemberScopeQuery.descendantCondition("m.id",List.of(root),args); }
        if("true".equalsIgnoreCase(query.get("roots"))) where+=" AND m.inviter_id IS NULL";
        if("true".equalsIgnoreCase(query.get("reportRoots"))) {
            if(scope.global())where+=" AND m.inviter_id IS NULL";
            else {var roots=scope.roots();where+=" AND m.id IN ("+(roots.isEmpty()?"NULL":placeholders(roots.size()))+")";args.addAll(roots);}
        }
        if(!text(query.get("parentMemberId")).isEmpty()) {long parent=number(query.get("parentMemberId"));where+=" AND m.inviter_id=?";args.add(parent);}
        for(String boundary:List.of("depth","minDepth","maxDepth")) if(!text(query.get(boundary)).isEmpty()) {
            int value=integer(query.get(boundary),0,200,0);
            where+=" AND m.id IN (WITH RECURSIVE member_levels(id,depth) AS (SELECT id,0 FROM compute_member WHERE inviter_id IS NULL UNION ALL SELECT c.id,p.depth+1 FROM compute_member c JOIN member_levels p ON c.inviter_id=p.id WHERE p.depth<200) SELECT id FROM member_levels WHERE depth"+("depth".equals(boundary)?"=?":"minDepth".equals(boundary)?">=?":"<=?")+")";args.add(value);
        }
        if (!text(query.get("level")).isEmpty()) { where += " AND m.level=?"; args.add(number(query.get("level"))); }
        if (!text(query.get("inviteCode")).isEmpty()) { where += " AND m.invite_code=?"; args.add(limited(query.get("inviteCode"), 32)); }
        if (!text(query.get("inviterId")).isEmpty()) {
            long inviterId = number(query.get("inviterId"));
            where += " AND m.inviter_id=?"; args.add(inviterId);
        }
        if (!text(query.get("inviterName")).isEmpty()) {
            args.add("%" + limited(query.get("inviterName"), 40) + "%");
            where += " AND EXISTS (SELECT 1 FROM compute_member inviter WHERE inviter.id=m.inviter_id AND inviter.username LIKE ?)";
        }
        if (!text(query.get("keyword")).isEmpty()) {
            String mode = query.getOrDefault("matchMode", "contains"); if (!Set.of("exact", "contains").contains(mode)) throw new BusinessException("server.compute.management.invalidFilter");
            String keyword = limited(query.get("keyword"), 120); String comparison = "exact".equals(mode) ? "=?" : " LIKE ?";
            if ("contains".equals(mode)) keyword = "%" + keyword + "%";
            String field = query.getOrDefault("searchField", "all");
            if ("all".equals(field)) {
                where += " AND (m.username" + comparison + " OR m.email" + comparison + " OR m.phone" + comparison + " OR CAST(m.id AS CHAR)" + comparison + ")";
                args.addAll(List.of(keyword, keyword, keyword, keyword));
            } else {
                String column = switch (field) { case "id" -> "CAST(m.id AS CHAR)"; case "username" -> "m.username"; case "email" -> "m.email"; case "phone" -> "m.phone"; default -> throw new BusinessException("server.compute.management.invalidFilter"); };
                where += " AND " + column + comparison; args.add(keyword);
            }
        }
        if (memberDate) where += dates(query, "m.created_at", args) + statusFilter(query, "m.enabled", args);
        return where;
    }

    private static String dates(Map<String, String> query, String column, List<Object> args) {
        String result = "";
        for (String field : List.of("startTime", "endTime")) if (!text(query.get(field)).isEmpty()) {
            try { args.add(LocalDateTime.ofInstant(Instant.parse(query.get(field)), ZoneOffset.UTC)); }
            catch (Exception exception) { throw new BusinessException("server.compute.invalidInput"); }
            result += " AND " + column + ("startTime".equals(field) ? ">=?" : "<=?");
        }
        return result;
    }

    public static void validateReportDates(Map<String,String> query) {
        dates(query,"f.created_at",new ArrayList<>());
    }

    private static String amountRange(Map<String, String> query, String suffix, String column, List<Object> args, boolean signed) {
        String where = ""; java.math.BigDecimal minimum = null, maximum = null;
        for (String boundary : List.of("min", "max")) {
            String value = text(query.get(boundary + suffix)); if (value.isEmpty()) continue;
            java.math.BigDecimal amount = decimal(value);
            if ((!signed && amount.signum() < 0) || amount.scale() > 8 || amount.precision() - amount.scale() > 18) throw new BusinessException("server.compute.invalidAmount");
            if ("min".equals(boundary)) minimum = amount; else maximum = amount;
            where += " AND " + column + ("min".equals(boundary) ? ">=?" : "<=?"); args.add(amount);
        }
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) throw new BusinessException("server.compute.invalidRange");
        return where;
    }

    private static String sortDirection(Map<String, String> query) {
        String direction = text(query.getOrDefault("sortOrder", "desc")).toUpperCase(Locale.ROOT);
        if (!Set.of("ASC", "DESC").contains(direction)) throw new BusinessException("server.compute.management.invalidFilter"); return direction;
    }

    private static String statusFilter(Map<String, String> query, String column, List<Object> args) {
        String status = text(query.get("status")); if (status.isEmpty()) return "";
        if (!Set.of("ENABLED", "DISABLED").contains(status)) throw new BusinessException("server.compute.invalidInput");
        args.add("ENABLED".equals(status)); return " AND " + column + "=?";
    }

    private static String textFilter(Map<String, String> query, String key, String column, List<Object> args) {
        if (text(query.get(key)).isEmpty()) return ""; args.add("%" + limited(query.get(key), 100) + "%"); return " AND " + column + " LIKE ?";
    }

    private static String countryFilter(Map<String,String> query,String key,String column,List<Object> args) {
        String value=text(query.get(key));
        if(value.isEmpty())return "";
        if(!value.matches("[A-Za-z]{2}"))throw new BusinessException("server.compute.management.invalidFilter");
        args.add(value.toUpperCase(Locale.ROOT));return " AND "+column+"=?";
    }

    /** IP 支持 IPv4/IPv6 片段；用户输入的通配符只作字面字符，不扩大匹配范围。 */
    private static String ipFilter(Map<String,String> query,String key,String column,List<Object> args) {
        String value=limited(query.get(key),64);
        if(value.isEmpty())return "";
        args.add("%"+value.replace("!","!!").replace("%","!%").replace("_","!_")+"%");
        return " AND "+column+" LIKE ? ESCAPE '!'";
    }

    private static String financialUnitSql(String table) {
        return Set.of("compute_deposit","compute_withdrawal").contains(table)
                ? "CASE WHEN f.accounting_version=2 THEN 'USDT' ELSE f.currency END" : "f.currency";
    }

    /** 仅转换源码内固定统计表达式，用户输入不参与SQL标识符或字段拼接。 */
    private static String financialAmountsSql(String table,String columns) {
        if (!Set.of("compute_deposit","compute_withdrawal").contains(table)) return columns;
        if ("compute_withdrawal".equals(table)) columns=columns
                .replace("f.net_amount","(CASE WHEN f.accounting_version=2 THEN f.accounting_net_amount ELSE f.net_amount END)")
                .replace("f.fee","(CASE WHEN f.accounting_version=2 THEN f.accounting_fee ELSE f.fee END)");
        return columns.replace("f.amount","(CASE WHEN f.accounting_version=2 THEN f.accounting_amount ELSE f.amount END)");
    }

    /** 只包装源码固定列中的金额引用；人数及笔数表达式保持原样。 */
    private static String formalReportAmountsSql(String columns) {
        return columns.replaceAll("\\bf\\.(amount|net_amount|fee|paid_income|available|frozen|accounting_amount|accounting_net_amount|accounting_fee)\\b",
                "(CASE WHEN m.is_test=0 THEN f.$1 ELSE 0 END)");
    }

    private static String limited(Object value,int max){String result=text(value);if(result.length()>max)throw new BusinessException("server.compute.invalidInput");return result;}
    private static boolean booleanInput(Object value){String raw=text(value);if(!Set.of("true","false","1","0").contains(raw))throw new BusinessException("server.compute.invalidInput");return "true".equals(raw)||"1".equals(raw);}
    private static int integer(Object value,int min,int max,int fallback){long result=value==null?fallback:number(value);if(result<min||result>max)throw new BusinessException("server.compute.invalidInput");return (int)result;}
    private static String placeholders(int size){return MemberScopeQuery.placeholders(size);}
    private static LocalDateTime utcDayStart(LocalDate day,ZoneId zone){return LocalDateTime.ofInstant(day.atStartOfDay(zone).toInstant(),ZoneOffset.UTC);}
    public static String overviewMembers(Map<String,Object> p){
        var search=search(p);List<Object> args=new ArrayList<>(List.of(p.get("todayStart"),p.get("tomorrowStart")));
        String where=memberWhere(search.filters(),args,true,search.scope());
        return SqlParameters.bind("SELECT COUNT(*) member_count,COUNT(CASE WHEN m.enabled=1 THEN 1 END) enabled_members,COUNT(CASE WHEN m.created_at>=? AND m.created_at<? THEN 1 END) today_members FROM compute_member m"+where,args,p);
    }
    public static String overviewFunds(Map<String,Object> p){
        var search=search(p);Map<String,String> query=search.filters();String resource=(String)p.get("resource");
        List<Object> args=new ArrayList<>();String where=memberWhere(query,args,false,search.scope());
            String table, columns;
            switch (resource) {
                case "orders" -> { table = "compute_order"; columns = "SUM(CASE WHEN f.status='ACTIVE' THEN f.amount ELSE 0 END) active_principal,SUM(f.paid_income) total_income,COUNT(CASE WHEN f.status='ACTIVE' THEN 1 END) active_orders"; }
                case "auto" -> { table = "compute_auto_contract"; columns = "0 active_principal,SUM(f.paid_income) total_income,SUM(f.paid_income) auto_income,0 auto_amount,COUNT(*) auto_count,0 active_auto"; }
                case "deposits" -> { table = "compute_deposit"; columns = "SUM(CASE WHEN f.status='APPROVED' THEN f.amount ELSE 0 END) total_deposits,COUNT(CASE WHEN f.status='PENDING' THEN 1 END) pending_deposits"; }
                case "withdrawals" -> { table = "compute_withdrawal"; columns = "SUM(CASE WHEN f.status='APPROVED' THEN f.net_amount ELSE 0 END) total_withdrawals,COUNT(CASE WHEN f.status='PENDING' THEN 1 END) pending_withdrawals"; }
                case "wallets" -> { table = "compute_wallet"; columns = "SUM(f.available) available,SUM(f.frozen) frozen"; }
                default -> { table = "compute_ledger"; columns = "SUM(CASE WHEN f.kind IN ('TEAM_REWARD','TEAM_REWARD_TEST') THEN f.amount ELSE 0 END) team_rewards,SUM(CASE WHEN f.kind='ACTIVITY_REWARD' THEN f.amount ELSE 0 END) activity_rewards"; }
            }
            if (!"wallets".equals(resource)) where += dates(query, Set.of("deposits","withdrawals").contains(resource)?"f.reviewed_at":"f.created_at", args);
            String unit=financialUnitSql(table);
            columns=formalReportAmountsSql(financialAmountsSql(table,columns));
        return SqlParameters.bind("SELECT "+unit+" currency,"+columns+" FROM "+table+" f JOIN compute_member m ON m.id=f.member_id"+where+" GROUP BY "+unit,args,p);
    }
    public static String walletDistribution(Map<String,Object> p){
        var search=search(p);List<Object> args=new ArrayList<>();String where=memberWhere(search.filters(),args,false,search.scope())+" AND m.is_test=0";
        return SqlParameters.bind("SELECT f.currency,f.wallet_type,SUM(f.available) available,SUM(f.frozen) frozen FROM compute_wallet f JOIN compute_member m ON m.id=f.member_id"+where+" GROUP BY f.currency,f.wallet_type",args,p);
    }
    public static String overviewTrend(Map<String,Object> p){
        var search=search(p);String kind=(String)p.get("kind");ZoneId zone=(ZoneId)p.get("zone");LocalDate today=(LocalDate)p.get("today");
        if(!Set.of("deposits","withdrawals").contains(kind))throw new IllegalArgumentException("不支持的概览趋势类型");
        String table="deposits".equals(kind)?"compute_deposit":"compute_withdrawal",amount=financialAmountsSql(table,"deposits".equals(kind)?"f.amount":"f.net_amount"),unit=financialUnitSql(table);
        List<Object> args=new ArrayList<>();List<String> columns=new ArrayList<>();
        for(int index=0;index<7;index++){
            LocalDate day=today.minusDays(6).plusDays(index);args.add(utcDayStart(day,zone));args.add(utcDayStart(day.plusDays(1),zone));
            columns.add("COALESCE(SUM(CASE WHEN f.reviewed_at>=? AND f.reviewed_at<? THEN "+amount+" ELSE 0 END),0) day_"+index);
        }
        String where=memberWhere(search.filters(),args,false,search.scope())+" AND m.is_test=0";
        args.add(utcDayStart(today.minusDays(6),zone));args.add(utcDayStart(today.plusDays(1),zone));
        return SqlParameters.bind("SELECT "+unit+" currency,"+String.join(",",columns)+" FROM "+table+" f JOIN compute_member m ON m.id=f.member_id"+where+" AND f.status='APPROVED' AND f.reviewed_at>=? AND f.reviewed_at<? GROUP BY "+unit,args,p);
    }
    private static String teamCondition(String column,Map<String,Object> p,List<Object> args){
        return MemberScopeQuery.descendantCondition(column,List.of(number(p.get("root"))),args,((Number)p.get("exact")).intValue(),((Number)p.get("maximum")).intValue(),(Boolean)p.get("includeSelf"));
    }
    @SuppressWarnings("unchecked") private static Map<String,String> teamQuery(Map<String,Object> p){return (Map<String,String>)p.get("query");}
    private static String teamTable(Map<String,Object> p){return switch((String)p.get("resource")){
        case "orders"->"compute_order";case "auto"->"compute_auto_contract";case "deposits"->"compute_deposit";case "withdrawals"->"compute_withdrawal";case "wallets"->"compute_wallet";case "ledger"->"compute_ledger";default->throw new IllegalArgumentException("不支持的团队统计类型");};}
    private static String firstDeposit(){return "NOT EXISTS(SELECT 1 FROM compute_deposit earlier WHERE earlier.member_id=f.member_id AND earlier.status='APPROVED' AND (earlier.reviewed_at<f.reviewed_at OR (earlier.reviewed_at=f.reviewed_at AND earlier.id<f.id)))";}
    public static String teamMemberCount(Map<String,Object> p){
        List<Object> args=new ArrayList<>();String where=teamCondition("m.id",p,args);
        if(Boolean.TRUE.equals(p.get("dated")))where+=dates(teamQuery(p),"m.created_at",args);
        return SqlParameters.bind("SELECT COUNT(*) FROM compute_member m WHERE 1=1"+where,args,p);
    }
    public static String teamFunds(Map<String,Object> p){
        String table=teamTable(p),first=firstDeposit();Map<String,String> query=teamQuery(p);
            String columns=switch(table){
                case "compute_order"->"SUM(CASE WHEN f.status='ACTIVE' THEN f.amount ELSE 0 END) active_principal";
                case "compute_auto_contract"->"0 active_principal";
                case "compute_deposit"->"COUNT(CASE WHEN m.is_test=0 AND f.status='APPROVED' THEN 1 END) formal_deposit_count,COUNT(DISTINCT CASE WHEN m.is_test=0 AND f.status='APPROVED' AND "+first+" THEN f.member_id END) formal_first_deposit_members,SUM(CASE WHEN f.status='APPROVED' THEN f.amount ELSE 0 END) total_deposits,COUNT(CASE WHEN f.status='APPROVED' THEN 1 END) deposit_count,COUNT(DISTINCT CASE WHEN f.status='APPROVED' THEN f.member_id END) deposit_members,"+
                        "SUM(CASE WHEN f.status='APPROVED' AND "+first+" THEN f.amount ELSE 0 END) first_deposit_amount,COUNT(DISTINCT CASE WHEN f.status='APPROVED' AND "+first+" THEN f.member_id END) first_deposit_members";
                case "compute_withdrawal"->"COUNT(CASE WHEN m.is_test=0 AND f.status='APPROVED' THEN 1 END) formal_withdrawal_count,SUM(CASE WHEN f.status='APPROVED' THEN f.net_amount ELSE 0 END) total_withdrawals,SUM(CASE WHEN f.status='APPROVED' THEN f.amount ELSE 0 END) withdrawal_amount,SUM(CASE WHEN f.status='APPROVED' THEN f.net_amount ELSE 0 END) withdrawal_net_amount,SUM(CASE WHEN f.status='APPROVED' THEN f.fee ELSE 0 END) withdrawal_fee,COUNT(CASE WHEN f.status='APPROVED' THEN 1 END) withdrawal_count,COUNT(DISTINCT CASE WHEN f.status='APPROVED' THEN f.member_id END) withdrawal_members";
                case "compute_wallet"->"SUM(f.available) available,SUM(f.frozen) frozen";
                default->"SUM(CASE WHEN f.kind='ORDER_INCOME' THEN f.amount ELSE 0 END) total_income,SUM(CASE WHEN f.kind='AUTO_INCOME' THEN f.amount ELSE 0 END) auto_income,SUM(CASE WHEN f.kind IN ('TEAM_REWARD','TEAM_REWARD_TEST') THEN f.amount ELSE 0 END) team_rewards,SUM(CASE WHEN f.kind='ACTIVITY_REWARD' THEN f.amount ELSE 0 END) activity_rewards";
            };
            List<Object> args=new ArrayList<>();
            String where=" WHERE 1=1"+teamCondition("f.member_id",p,args);
            if(Set.of("compute_deposit","compute_withdrawal").contains(table))where+=dates(query,"f.reviewed_at",args);
            else if("compute_ledger".equals(table))where+=dates(query,"f.created_at",args);
            String unit=financialUnitSql(table);
            columns=formalReportAmountsSql(financialAmountsSql(table,columns));
        return SqlParameters.bind("SELECT "+unit+" currency,"+columns+" FROM "+table+" f JOIN compute_member m ON m.id=f.member_id"+where+" GROUP BY "+unit,args,p);
    }
    public static String teamPaymentCounts(Map<String,Object> p){
        String table=teamTable(p),first=firstDeposit();if(!Set.of("compute_deposit","compute_withdrawal").contains(table))throw new IllegalArgumentException("不支持的充提统计类型");
        List<Object> args=new ArrayList<>();String where=" WHERE 1=1"+teamCondition("f.member_id",p,args)+dates(teamQuery(p),"f.reviewed_at",args);
        String counts=table.equals("compute_deposit")
                ? "COUNT(CASE WHEN f.status='APPROVED' THEN 1 END) deposit_count,COUNT(DISTINCT CASE WHEN f.status='APPROVED' THEN f.member_id END) deposit_members,COUNT(DISTINCT CASE WHEN f.status='APPROVED' AND "+first+" THEN f.member_id END) first_deposit_members"
                : "COUNT(CASE WHEN f.status='APPROVED' THEN 1 END) withdrawal_count,COUNT(DISTINCT CASE WHEN f.status='APPROVED' THEN f.member_id END) withdrawal_members";
        return SqlParameters.bind("SELECT "+counts+" FROM "+table+" f JOIN compute_member m ON m.id=f.member_id"+where,args,p);
    }
    private static String teamCreatedWhere(Map<String,Object> p,List<Object> args){return " WHERE 1=1"+teamCondition("f.member_id",p,args)+dates(teamQuery(p),"f.created_at",args);}
    public static String teamProducts(Map<String,Object> p){
        List<Object> args=new ArrayList<>();String where=teamCreatedWhere(p,args);
        return SqlParameters.bind("SELECT f.currency,COUNT(*) product_count,COUNT(DISTINCT f.member_id) product_members,COALESCE(SUM(CASE WHEN m.is_test=0 THEN f.amount ELSE 0 END),0) product_amount FROM compute_order f JOIN compute_member m ON m.id=f.member_id"+where+" GROUP BY f.currency",args,p);
    }
    public static String teamProductCounts(Map<String,Object> p){
        List<Object> args=new ArrayList<>();String where=teamCreatedWhere(p,args);
        return SqlParameters.bind("SELECT COUNT(*) product_count,COUNT(DISTINCT f.member_id) product_members FROM compute_order f JOIN compute_member m ON m.id=f.member_id"+where,args,p);
    }
    public static String teamAuto(Map<String,Object> p){
        List<Object> args=new ArrayList<>();String where=teamCreatedWhere(p,args);
        return SqlParameters.bind("SELECT f.currency,COUNT(*) auto_count,0 auto_amount FROM compute_auto_contract f JOIN compute_member m ON m.id=f.member_id"+where+" GROUP BY f.currency",args,p);
    }
    public static String personalTrend(Map<String,Object> p){
        var search=search(p);Map<String,String> identities=search.filters();MemberScope scope=search.scope();
        String metric=(String)p.get("metric");ZoneId zone=(ZoneId)p.get("zone");LocalDate start=(LocalDate)p.get("start"),end=(LocalDate)p.get("end");
        if(!Set.of("totalDeposits","totalWithdrawals","totalIncome","autoIncome").contains(metric))throw new IllegalArgumentException("不支持的个人趋势类型");
        List<LocalDate> days=new ArrayList<>();for(LocalDate day=start;!day.isAfter(end);day=day.plusDays(1))days.add(day);
            String table=switch(metric){case "totalDeposits"->"compute_deposit";case "totalWithdrawals"->"compute_withdrawal";default->"compute_ledger";};
            String time=table.equals("compute_ledger")?"f.created_at":"f.reviewed_at";
            String value=metric.equals("totalWithdrawals")?"f.net_amount":"f.amount";
            value=financialAmountsSql(table,value);
            String unit=financialUnitSql(table);
            String condition=switch(metric){case "totalIncome"->" AND f.kind='ORDER_INCOME'";case "autoIncome"->" AND f.kind='AUTO_INCOME'";default->" AND f.status='APPROVED'";};
            List<Object> args=new ArrayList<>();List<String> columns=new ArrayList<>();
            for(int index=0;index<days.size();index++) {
                LocalDate day=start.plusDays(index);columns.add("COALESCE(SUM(CASE WHEN "+time+">=? AND "+time+"<? THEN "+value+" ELSE 0 END),0) d"+index);
                args.add(utcDayStart(day,zone));args.add(utcDayStart(day.plusDays(1),zone));
            }
            String where=memberWhere(identities,args,false,scope)+" AND m.is_test=0"+condition;
            where+=" AND "+time+">=? AND "+time+"<?";args.add(utcDayStart(start,zone));args.add(utcDayStart(end.plusDays(1),zone));
        return SqlParameters.bind("SELECT "+unit+" currency,"+String.join(",",columns)+" FROM "+table+" f JOIN compute_member m ON m.id=f.member_id"+where+" GROUP BY "+unit,args,p);
    }
}
