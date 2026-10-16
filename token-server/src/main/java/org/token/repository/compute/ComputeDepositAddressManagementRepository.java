package org.token.repository.compute;

import java.util.*;
import org.token.model.query.MemberScope;
import org.token.exception.BusinessException;
import static org.token.repository.compute.ComputeData.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeDepositAddressManagementMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeDepositAddressManagementService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeDepositAddressManagementRepository {
    private final ComputeDepositAddressManagementMapper mapper;

    @Autowired
    public ComputeDepositAddressManagementRepository(ComputeDepositAddressManagementMapper mapper) { this.mapper = mapper; }

    public ComputeDepositAddressManagementRepository(ComputeData data) {
        this(data.mapper(ComputeDepositAddressManagementMapper.class));
    }

    public Map<String, Object> generateMemberRow(Object... args) {
        return RepositoryResults.required(mapper.generateMemberRow(args));
    }

    public Map<String, Object> generateMemberRow2(Object... args) {
        return RepositoryResults.required(mapper.generateMemberRow2(args));
    }

    public List<Map<String, Object>> generateMcagAddressRows(Object... args) {
        return ComputeData.normalizeRows(mapper.generateMcagAddressRows(args));
    }

    public Map<String, Object> disableMcagAddressRow(Object... args) {
        return RepositoryResults.required(mapper.disableMcagAddressRow(args));
    }

    public Map<String, Object> disableMemberRow(Object... args) {
        return RepositoryResults.required(mapper.disableMemberRow(args));
    }

    public List<Map<String, Object>> disableMcagAddressRows(Object... args) {
        return ComputeData.normalizeRows(mapper.disableMcagAddressRows(args));
    }

    public int disableNetworkMcagAddressGenerationWrite(Object... args) {
        return mapper.disableNetworkMcagAddressGenerationWrite(args);
    }

    public Map<String, Object> disableNetworkMcagAddressGenerationRow(Object... args) {
        return RepositoryResults.required(mapper.disableNetworkMcagAddressGenerationRow(args));
    }

    public Long disableNetworkMcagAddressCurrentCount(Object... args) {
        return RepositoryResults.scalar(mapper.disableNetworkMcagAddressCurrentCount(args), Long.class);
    }

    public Long disableNetworkMcagAddressCurrentCount2(Object... args) {
        return RepositoryResults.scalar(mapper.disableNetworkMcagAddressCurrentCount2(args), Long.class);
    }

    public int disableNetworkMcagAddressCurrentWrite(Object... args) {
        return mapper.disableNetworkMcagAddressCurrentWrite(args);
    }

    public int disableNetworkMcagAddressGenerationWrite2(Object... args) {
        return mapper.disableNetworkMcagAddressGenerationWrite2(args);
    }

    public List<Map<String, Object>> evmNetworksCurrencyNetworkRows(Object... args) {
        return ComputeData.normalizeRows(mapper.evmNetworksCurrencyNetworkRows(args));
    }

    public Map<String,Object> page(String merchant,String environment,MemberScope scope,int page,int size,String username,String network,String status,String address,String currency) {
        var rows=fields("merchant",merchant,"environment",environment,"scope",scope,"page",page,"size",size,"username",username,"network",network,"status",status,"address",address,"currency",currency,"count",false);
        rows.put("statement",Queries.preparePage(rows));
        var count=fields("merchant",merchant,"environment",environment,"scope",scope,"page",page,"size",size,"username",username,"network",network,"status",status,"address",address,"currency",currency,"count",true);
        count.put("statement",Queries.preparePage(count));
        long total=RepositoryResults.scalar(mapper.page(count),Long.class);
        return fields("records",ComputeData.normalizeRows(mapper.page(rows)),"total",total);
    }

    public List<Map<String,Object>> filterOptions(String merchant,String environment,MemberScope scope) {
        var parameters=fields("merchant",merchant,"environment",environment,"scope",scope);
        parameters.put("statement",Queries.prepareFilterOptions(parameters));
        var pairs=new TreeMap<String,Map<String,Object>>();
        for(var row:ComputeData.normalizeRows(mapper.filterOptions(parameters))) {
            String currency=text(row.get("currency")),network=text(row.get("network")),display=text(row.get("displayNetwork"));
            if(!currency.isEmpty()&&!network.isEmpty())pairs.put(currency+"\u0000"+network,fields("currency",currency,"network",network));
            if(!currency.isEmpty()&&!display.isEmpty())pairs.put(currency+"\u0000"+display,fields("currency",currency,"network",display));
        }
        return new ArrayList<>(pairs.values());
    }
    public static final class Queries {
        private Queries() { }
    private static final String FROM = " FROM compute_mcag_address a JOIN compute_member m ON m.id=a.member_id LEFT JOIN compute_mcag_address_current c ON c.binding_id=a.id AND c.merchant_id=a.merchant_id AND c.environment=a.environment AND c.asset_id=a.asset_id";
    private static final String STATUS = "CASE WHEN a.disabled_at IS NOT NULL THEN 'DISABLED' WHEN c.id IS NULL THEN 'HISTORY' ELSE c.address_status END";
    private static final String EVM_ADDRESS = "LOWER(a.address) REGEXP '^0x[0-9a-f]{40}$'";
    private static final String DISPLAY_NETWORK = "CASE WHEN "+EVM_ADDRESS+" THEN 'EVM' ELSE a.network END";
    // EVM同地址跨BEP/ETH/POL展示一条；资金入账与永久归属继续保留各真实链。
    private static final String ADDRESS_IDENTITY = "a.merchant_id,a.environment,a.member_id,a.wallet_type,CASE WHEN "+EVM_ADDRESS+" THEN 'EVM' ELSE a.network_id END,a.customer_id,"
            + "CAST(CASE WHEN LOWER(a.address) REGEXP '^0x[0-9a-f]{40}$' THEN LOWER(a.address) ELSE a.address END AS BINARY(200)),"
            + "CAST(COALESCE(a.memo,'') AS BINARY(480))";


        public static String page(Map<String,Object> parameters) { return (String)parameters.get("statement"); }
        public static String filterOptions(Map<String,Object> parameters) { return (String)parameters.get("statement"); }
        private static String prepareFilterOptions(Map<String,Object> parameters) {
            var args=new ArrayList<Object>(List.of(parameters.get("merchant"),parameters.get("environment")));
            String where=" WHERE a.merchant_id=? AND a.environment=?"+MemberScopeQuery.memberFilter("m.id",(MemberScope)parameters.get("scope"),args);
            return org.token.repository.support.SqlParameters.bind("SELECT DISTINCT a.currency,a.network,"+DISPLAY_NETWORK
                    +" display_network FROM compute_mcag_address a JOIN compute_member m ON m.id=a.member_id"+where,args,parameters);
        }
        private static String preparePage(Map<String,Object> parameters) {
            String merchant=(String)parameters.get("merchant"),environment=(String)parameters.get("environment"),username=(String)parameters.get("username"),network=(String)parameters.get("network"),status=(String)parameters.get("status");
            int page=(int)parameters.get("page"),size=(int)parameters.get("size");
            MemberScope scope=(MemberScope)parameters.get("scope");
        int limit=Math.max(1,Math.min(100,size));
        var args=new ArrayList<Object>(List.of(merchant,environment));
        String where=" WHERE a.merchant_id=? AND a.environment=?"+MemberScopeQuery.memberFilter("m.id",scope,args);
        if(!text(username).isEmpty()) { where+=" AND m.username LIKE ?";args.add("%"+limited(username,64)+"%"); }
        String filterMatch="",selected=" WHERE r.address_rank=1";
        var conditions=new ArrayList<String>();
        var filterArgs=new ArrayList<Object>();
        if(!text(parameters.get("address")).isEmpty()) {
            conditions.add("a.address LIKE ? ESCAPE '!'");
            String address=limited((String)parameters.get("address"),200);
            filterArgs.add("%"+address.replace("!","!!").replace("%","!%").replace("_","!_")+"%");
        }
        if(!text(parameters.get("currency")).isEmpty()) {
            conditions.add("a.currency=?");filterArgs.add(limited((String)parameters.get("currency"),32));
        }
        if(!text(network).isEmpty()) {
            String normalized=limited(network,32);
            conditions.add("EVM".equalsIgnoreCase(normalized)?EVM_ADDRESS:"a.network=?");
            if(!"EVM".equalsIgnoreCase(normalized))filterArgs.add(normalized);
        }
        if(!conditions.isEmpty()) {
            // 同一绑定同时匹配币种与链；匹配历史别名后仍展示原当前指针，不改变地址归并及状态。
            filterMatch=",MAX(CASE WHEN "+String.join(" AND ",conditions)+" THEN 1 ELSE 0 END) OVER (PARTITION BY "+ADDRESS_IDENTITY+") filter_match";
            args.addAll(0,filterArgs);selected+=" AND r.filter_match=1";
        }
        String grouped=" FROM (SELECT a.id,m.username,a.wallet_type,"+DISPLAY_NETWORK+" network,a.address,a.memo,a.created_at,a.disabled_at,c.verified_at,c.monitoring_ready,"
                +STATUS+" status,ROW_NUMBER() OVER (PARTITION BY "+ADDRESS_IDENTITY
                +" ORDER BY CASE WHEN a.disabled_at IS NULL AND c.id IS NOT NULL THEN 0 WHEN a.disabled_at IS NULL THEN 1 ELSE 2 END,c.verified_at DESC,a.id DESC,c.id DESC) address_rank"
                +filterMatch+FROM+where+") r";
        if(!text(status).isEmpty()) {
            if(!Set.of("ACTIVE","UNAVAILABLE","UNKNOWN","DISABLED","HISTORY").contains(status))invalid();
            selected+=" AND r.status=?";args.add(status);
        }

            if(Boolean.TRUE.equals(parameters.get("count")))return org.token.repository.support.SqlParameters.bind("SELECT COUNT(*)"+grouped+selected,args,parameters);
            args.add(limit);args.add((long)(Math.max(1,page)-1)*limit);
            return org.token.repository.support.SqlParameters.bind("SELECT r.id,r.username,r.wallet_type,r.network,r.address,r.memo,r.created_at,r.disabled_at,r.verified_at,r.monitoring_ready,r.status"+grouped+selected+" ORDER BY r.id DESC LIMIT ? OFFSET ?",args,parameters);
        }
        private static String limited(String value,int length) { String normalized=text(value);if(normalized.length()>length)invalid();return normalized; }
        private static void invalid() { throw new BusinessException("server.compute.invalidInput"); }
    }
}
