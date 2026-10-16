package org.token.service.compute;

import org.token.repository.compute.ComputeManagementRepository;
import org.token.model.query.ManagementMemberQuery;
import org.token.model.query.ManagementTeamQuery;
import org.token.model.entity.ComputeMemberGroup;

import org.token.service.compute.ComputeAgentScope;
import org.token.service.compute.ComputeCredentials;
import org.token.service.compute.ComputeVipPolicy;
import org.token.service.compute.ComputeDisplayNames;
import org.token.util.PasswordUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.transaction.annotation.Isolation;

import org.token.service.compute.ComputeBusinessService;
import org.token.service.compute.ComputeCatalogService;
import org.token.repository.compute.ComputeData;
import org.token.exception.BusinessException;
import org.token.service.SystemSettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.*;

import static org.token.repository.compute.ComputeData.*;

/** 管理端分页、代理归属和报表统一应用服务端数据范围，会员端继续使用独立身份。 */
@Service
public class ComputeManagementService {
    private final ComputeData data;
    private final ComputeManagementRepository repository;
    private final ComputeAgentScope scope;
    private final ComputeCatalogService catalog;
    private final ComputeBusinessService business;
    private final SystemSettingService systemSettings;
    private final ComputeVipPeriodService vipPeriods;
    private final ComputeOnlinePolicy onlinePolicy;

    public ComputeManagementService(ComputeData data, JdbcTemplate jdbc, ComputeAgentScope scope, ComputeCatalogService catalog,
                                    ComputeBusinessService business, SystemSettingService systemSettings, ComputeVipPeriodService vipPeriods,ComputeOnlinePolicy onlinePolicy) {
        this(data,new ComputeManagementRepository(data),scope,catalog,business,systemSettings,vipPeriods,onlinePolicy);
    }

    @Autowired
    public ComputeManagementService(ComputeData data,ComputeManagementRepository repository,ComputeAgentScope scope,ComputeCatalogService catalog,
                                    ComputeBusinessService business,SystemSettingService systemSettings,ComputeVipPeriodService vipPeriods,ComputeOnlinePolicy onlinePolicy) {
        this.data=data;this.repository=repository;this.scope=scope;this.catalog=catalog;this.business=business;
        this.systemSettings=systemSettings;this.vipPeriods=vipPeriods;this.onlinePolicy=onlinePolicy;
    }

    public ComputeManagementService(ComputeData data,JdbcTemplate jdbc,ComputeAgentScope scope,ComputeCatalogService catalog,
                                    ComputeBusinessService business,SystemSettingService systemSettings,ComputeVipPeriodService vipPeriods) {
        this(data,jdbc,scope,catalog,business,systemSettings,vipPeriods,new ComputeOnlinePolicy(data,new org.token.service.auth.LoginSessionPolicy(jdbc)));
    }

    public ComputeManagementService(ComputeData data, JdbcTemplate jdbc, ComputeAgentScope scope, ComputeCatalogService catalog, ComputeBusinessService business, SystemSettingService systemSettings) {
        this(data, jdbc, scope, catalog, business, systemSettings, new ComputeVipPeriodService(data, systemSettings));
    }

    /** 保留隔离测试和已有独立调用的构造方式；生产使用上面的完整构造器。 */
    public ComputeManagementService(ComputeData data, JdbcTemplate jdbc, ComputeAgentScope scope, ComputeCatalogService catalog, ComputeBusinessService business) {
        this(data, jdbc, scope, catalog, business, null);
    }

    public Map<String, Object> dictionaries() {
        long supportedDepth=Math.min(200,Math.max(1,number(data.setting("teamSupportedDepth","3"))));
        Map<String, Object> result = fields("scope", scope.description(), "summaryCurrency", data.setting("summaryCurrency", "USDT"),"defaultCurrency",business.defaultCurrency(),"teamDepth",supportedDepth,"teamSupportedDepth",supportedDepth);
        result.put("gatewayEnabled", catalog.gatewayEnabled());
        if(allowed("20100","20101","20102","20103","20104","20105","20106"))
            result.put("countries",ComputeUserFeaturesService.countryOptions());
        if (allowed("20101")) {
            Map<String, Object> rules = new LinkedHashMap<>();
            for (String field : List.of("username", "email", "phone")) {
                rules.put(field + "Enabled", enabled(data.setting(field + "Enabled", "true")));
                rules.put(field + "Required", enabled(data.setting(field + "Required", "username".equals(field) ? "true" : "false")));
            }
            rules.put("inviteCodeRequired", !scope.isGlobalScope()); result.put("creationRules", rules);
            rules.put(ComputeMemberUidAllocator.USERNAME_LIMIT, !"false".equalsIgnoreCase(data.setting(ComputeMemberUidAllocator.USERNAME_LIMIT, "true")));
        }
        if (allowed("10100","20100","20101","20102","20103","20104","20105","20106","20300","20301","20302","30100","30101","30200","30201","30300","30400","40100","40200","50100","50101","50200","50201","50300","50301","50400","50401","70100","70101","70200","70201","70300","70301","61100","61101","61200","61201","61300","61301","61400","61401","61500","61501")) {
            result.put("levels", repository.dictionariesLevelRows());
            result.put("currencies", repository.dictionariesCurrencyRows());
        }
        if (allowed("10100","30100","30101","30200","30201","30300","30400","40100","40200","70100","70101","70200","70201","70300","70301")) {
            result.put("products", repository.dictionariesProductRows());
            result.put("models", repository.dictionariesModelRows());
        }
        if (allowed("10100","20100","20101","20102","20103","20104","20105","20106","20107","20200","20201","20300","20301","20302","30100","30101","30200","30201","30300","30400","40100","40200","70500","70501","70600","70601","70800","70801","70900","70901","71000","71001")) {
            List<Map<String,Object>> groupOptions=repository.groupOptions(scope.queryScope());
            groupOptions.add(0,ungrouped());result.put("groups",groupOptions);
            result.put("agents", agents(Map.of("size", "100")).get("records"));
        }
        if(!result.containsKey("groups")&&allowed("50200","50201","50400","50401")) {
            List<Map<String,Object>> options=repository.groupOptions(scope.queryScope());
            options.add(0,ungrouped());result.put("groups",options);
        }
        if (allowed("60206") && scope.isGlobalScope())
            result.put("adminAccounts", repository.dictionariesAdMasterRows());
        result.put("optionLimit", 500); return result;
    }

    public Map<String,Object> groups(Map<String,String> query) {
        var range=scope.queryScope();
        Map<String,Object> result=repository.groups(new ManagementMemberQuery(range,query));
        for(var row:records(result)){
            long id=number(row.get("id"));
            row.put("memberCount",repository.groupMemberCount(id,range));
            row.put("builtIn",id==0);if(id==0)row.put("nameKey","admin.compute.management.ungrouped");
        }
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> saveGroup(Map<String, Object> request) {
        scope.relations().lock();
        if ("0".equals(text(request.get("id")))) throw new BusinessException("server.compute.management.builtInGroup");
        Long id = nullableId(request.get("id")), owner;
        if (id != null) {
            ComputeMemberGroup old = repository.lockedGroup(id);
            owner = old.getOwnerMemberId(); scope.assertOwner(owner);
            if (request.containsKey("ownerMemberId") && !Objects.equals(owner, nullableId(request.get("ownerMemberId"))))
                throw new BusinessException("server.compute.management.groupOwnershipImmutable");
        } else {
            owner = request.containsKey("ownerMemberId") ? nullableId(request.get("ownerMemberId")) : scope.currentRootMemberId();
            if (owner == null) scope.requireGlobalScope(); else scope.assertActiveMember(owner);
        }
        ComputeMemberGroup group=new ComputeMemberGroup();group.setId(id);
        group.setName(required(request,"name",100));group.setOwnerMemberId(owner);
        group.setNote(limited(request.get("note"),1000));group.setSortOrder(integer(request.get("sortOrder"),-100000,100000,0));
        group.setEnabled(enabled(request.getOrDefault("enabled",true)));group.setUpdatedAt(now());
        if(id==null)group.setCreatedAt(now());
        return repository.saveGroup(group);
    }

    /** 候选代理就是当前真实用户，不创建另一套代理账号或邀请码。 */
    public Map<String, Object> agents(Map<String, String> query) {
        if (allowed("60206") && !allowed("10100","20100","20101","20102","20103","20104","20107","20200","20201","20300","20301","20302","30100","30101","30200","30201","30300","30400","40100","40200","70500","70501","70600","70601","70800","70801","70900","70901","71000","71001")) scope.requireGlobalScope();
        if(!text(query.get("parentMemberId")).isEmpty())scope.assertMember(number(query.get("parentMemberId")));
        Map<String,Object> result=repository.agents(new ManagementMemberQuery(scope.queryScope(),query));
        enrichRelations(result); hideOutsideInviters(result);
        result.put("ancestors",text(query.get("parentMemberId")).isEmpty()?List.of():visibleChain(scope.relations().ancestors(number(query.get("parentMemberId")))));return result;
    }

    public Map<String,Object> bindingContext() { return scope.description(); }

    public Map<String,Object> bindings(Map<String,String> query) {
        scope.requireGlobalScope();
        Map<String,Object> result=repository.bindings(new ManagementMemberQuery(scope.queryScope(),query));
        for (Map<String,Object> row : records(result)) {
            long uid=number(row.get("adminUid"));
            List<Map<String,Object>> roots=repository.bindingsAdminMemberScopeRows(uid);
            enrichRelations(fields("records",roots)); row.put("roots",roots);
            row.put("memberIds",roots.stream().map(r->number(r.get("id"))).toList());
            row.put("scopeRestricted",row.get("scopeConfiguredAt")!=null || !roots.isEmpty());
        }
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> saveBinding(Map<String,Object> request) {
        scope.relations().lock(); scope.requireGlobalScope();
        long uid=number(request.get("adminUid"));
        if (!(request.get("memberIds") instanceof List<?> input) || input.size()>100) throw new BusinessException("server.compute.invalidInput");
        List<Long> roots=input.stream().map(ComputeData::number).distinct().sorted().toList();
        Map<String,Object> account=repository.saveBindingAdMasterRow(uid);
        var roles=repository.saveBindingAdMasterRows(uid);
        boolean protectedAccount=uid==1||"admin".equalsIgnoreCase(text(account.get("username")))||(!roles.isEmpty()&&enabled(roles.get(0).get("isSuper")));
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(protectedAccount&&(authentication==null||!authentication.isAuthenticated()||authentication.getAuthorities().stream().noneMatch(a->"super".equals(a.getAuthority()))))
            throw new org.springframework.security.access.AccessDeniedException("只有超级管理员可以修改超级账号代理范围");
        if (!roots.isEmpty() && (uid==1 || "admin".equalsIgnoreCase(text(account.get("username"))))) throw new BusinessException("server.compute.management.defaultAdminProtected");
        for (long root:roots) scope.assertActiveMember(root);
        repository.saveBindingAdminMemberScopeWrite(uid);
        for (long root:roots) repository.saveBindingAdminMemberScopeWrite2(uid, root, now());
        repository.saveBindingScopeConfiguredAt(roots.isEmpty()?null:now(), uid);
        scope.relations().changed();
        return fields("adminUid",uid,"memberIds",roots,"roots",repository.bindingRoots(roots),"scopeRestricted",!roots.isEmpty());
    }

    public Map<String, Object> members(Map<String, String> query) {
        String currency = selectedCurrency(query);
        if (repository.membersCurrencyCount(currency) == 0) throw new BusinessException("server.compute.unsupportedCurrency");
        ManagementMemberQuery search=authorizedQuery(query);
        ComputeOnlinePolicy.Window onlineWindow=onlinePolicy.window();
        Map<String,Object> result=repository.members(search,currency,onlineWindow.observedAt(),onlineWindow.onlineSince(),onlineWindow.idleSince());
        enrichRelations(result);
        hideOutsideInviters(result);
        @SuppressWarnings("unchecked") List<Map<String, Object>> records = (List<Map<String, Object>>) result.get("records");
        Set<String> chosenLanguages = new HashSet<>();
        records.forEach(row -> { if (!text(row.get("userLanguage")).isBlank()) chosenLanguages.add(languageCode(row.get("userLanguage"))); });
        Map<String, String> languageNames = new HashMap<>();
        if (!chosenLanguages.isEmpty()) repository.languages(chosenLanguages)
                .forEach(row -> languageNames.put(languageCode(row.get("lang")), chineseLanguageName(row.get("lang"),row.get("chineseName"),row.get("name"))));
        records.forEach(row -> row.put("userLanguageName", languageNames.getOrDefault(languageCode(row.get("userLanguage")),chineseLanguageName(row.get("userLanguage"),null))));
        Set<Long> online=onlinePolicy.onlineMembers(records.stream().map(row->number(row.get("id"))).toList(),onlineWindow);
        for (Map<String, Object> row : records) {row.put("currency", currency);row.put("isTest",enabled(row.get("isTest")));row.put("online",online.contains(number(row.get("id"))));}
        result.put("summaryCurrency", data.setting("summaryCurrency", "USDT"));
        result.put("currency", currency);
        result.put("defaultCurrency",business.defaultCurrency());
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> updateMember(long id, Map<String, Object> request) {
        scope.relations().lock(); Map<String,Object> member=lockedMember(id);
        if (request.isEmpty() || !Set.of("level","enabled","isTest","invitationEnabled","password","payPassword","email","phone","securityScore","tokenIncomePercent","remark","groupId","addressLimit").containsAll(request.keySet()))
            throw new BusinessException("server.compute.invalidInput");
        Map<String,Object> values=new LinkedHashMap<>();
        for (String key:List.of("password","payPassword")) if (request.containsKey(key) && !text(request.get(key)).isEmpty()) {
            String raw=Objects.toString(request.get(key),"");
            if("payPassword".equals(key))ComputeCredentials.validatePayPassword(raw);else ComputeCredentials.validatePassword(raw);
            values.put("password".equals(key)?"password_hash":"pay_password_hash",new PasswordUtils().hashPassword(raw));
        }
        for (String key:List.of("email","phone")) if(request.containsKey(key)) {
            String value=limited(request.get(key),"email".equals(key)?160:30);
            if(!value.isEmpty() && ("email".equals(key)?!value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"):!value.matches("\\+?[0-9]{6,20}")))
                throw new BusinessException("server.compute.invalidInput");
            values.put(key,value.isEmpty()?null:value);
        }
        if (request.containsKey("securityScore")) {
            BigDecimal score=decimal(request.get("securityScore"));
            if(score.signum()<0 || score.compareTo(new BigDecimal("100"))>0 || score.scale()>2) throw new BusinessException("server.compute.management.invalidSecurityScore");
            values.put("security_score",score);
        }
        if (request.containsKey("tokenIncomePercent"))
            values.put("token_income_percent", ComputeVipPolicy.tokenIncomePercent(request.get("tokenIncomePercent")));
        if(request.containsKey("remark")) values.put("remark",limited(request.get("remark"),1000));
        if(request.containsKey("enabled")) values.put("enabled",enabled(request.get("enabled")));
        if(request.containsKey("isTest"))values.put("is_test",booleanInput(request.get("isTest")));
        if(request.containsKey("invitationEnabled"))values.put("invitation_enabled",booleanInput(request.get("invitationEnabled")));
        if(request.containsKey("addressLimit"))values.put("address_limit",text(request.get("addressLimit")).isEmpty()?null:integer(request.get("addressLimit"),0,1000,10));
        if(request.containsKey("level")) {
            long level=number(request.get("level"));
            if(level!=number(member.get("level"))&&repository.updateMemberLevelCount(level)==0) throw new BusinessException("server.compute.levelRequired");
            vipPeriods.changeLevel(id,level);
        }
        if(request.containsKey("groupId")) {
            Long group=groupId(request.get("groupId"));
            if(group!=null && !Objects.equals(group,nullableId(member.get("groupId")))) validateGroup(group,id);
            values.put("group_id",group);
        }
        String username=text(member.get("username")),email=text(values.getOrDefault("email",member.get("email"))),phone=text(values.getOrDefault("phone",member.get("phone")));
        if(username.isEmpty() && email.isEmpty() && phone.isEmpty()) throw new BusinessException("server.compute.account.fields");
        values.put("updated_at",now());
        try { update("compute_member",id,values); }
        catch(DuplicateKeyException exception) { throw new BusinessException("server.compute.account.exists"); }
        if(values.containsKey("password_hash") || (values.containsKey("enabled") && !enabled(values.get("enabled")))) {
            repository.updateMemberMemberSessionWrite(id);
            repository.updateMemberMemberLoginTicketWrite(id);
        }
        Map<String,Object> result=repository.updateMemberMemberRow(id);
        result.put("isTest",enabled(result.get("isTest")));
        Map<String,Object> responsePage=fields("records",List.of(result));enrichRelations(responsePage);hideOutsideInviters(responsePage);return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> assignGroup(Map<String,Object> request) {
        scope.relations().lock();
        if(!(request.get("memberIds") instanceof List<?> input)||input.isEmpty()||input.size()>100) throw new BusinessException("server.compute.invalidInput");
        List<Long> ids=input.stream().map(ComputeData::number).distinct().sorted().toList(); Long group=groupId(request.get("groupId"));
        for(long id:ids) { lockedMember(id); if(group!=null)validateGroup(group,id); repository.assignGroupMemberWrite(group, now(), id); }
        return Map.of("updated",ids.size());
    }

    /** 分组页直接查询普通成员；不要求先把成员选成代理根，也不递归改动其下级。 */
    public Map<String,Object> groupMembers(Long groupId,Map<String,String> query) {
        Map<String,String> criteria=new HashMap<>(query);
        if(groupId!=null) {
            if(groupId<0)throw new BusinessException("server.compute.invalidInput");
            if(groupId>0){var group=repository.groupMembersMemberGroupRow(groupId);if(group.get("ownerMemberId")!=null)scope.assertMember(number(group.get("ownerMemberId")));}
            criteria.put("groupId",String.valueOf(groupId));
        }
        Map<String,Object> result=members(criteria);
        Set<String> fields=Set.of("id","username","level","levelName","enabled","isTest","groupId","groupName","createdAt","updatedAt","depth","inviterId","inviterName","topMemberId","topName","hasChildren","currency","available","totalDeposits","depositCount","totalWithdrawals","withdrawalCount");
        records(result).forEach(row->row.keySet().retainAll(fields));
        return result;
    }

    public Map<String,Object> memberWallets(long memberId) {
        scope.assertMember(memberId);
        List<Map<String,Object>> wallets=repository.memberWalletsWalletRows(memberId);
        return fields("memberId",memberId,"username",repository.memberWalletsMemberRow(memberId).get("username"),"wallets",wallets);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> adjustMoney(long memberId,Map<String,Object> request) {
        scope.relations().lock(); lockedMember(memberId);
        Map<String,Object> previous=beginOperation(memberId,"ADJUSTMENT",request); if(previous!=null)return previous;
        String currency=selectedCurrency(request),wallet=required(request,"walletType",16),direction=required(request,"direction",16),reason=required(request,"reason",16);
        if(!Set.of("COMPUTE","PROJECT","EARNINGS").contains(wallet)||!Set.of("ADD","SUBTRACT").contains(direction)||!Set.of("SYSTEM","SALARY").contains(reason))throw new BusinessException("server.compute.invalidInput");
        if(repository.membersCurrencyCount(currency)==0)throw new BusinessException("server.compute.unsupportedCurrency");
        BigDecimal delta=amount(request.get("amount"));if("SUBTRACT".equals(direction))delta=delta.negate();
        long operation=number(repository.adjustMoneyMemberOperationRow(scope.currentAdminUid(), request.get("requestId")).get("id"));
        business.changeWallet(memberId,wallet,currency,delta,BigDecimal.ZERO,"ADMIN_"+reason,"ADMIN:"+operation,String.valueOf(operation));
        if (delta.signum() > 0 && "COMPUTE".equals(wallet) && ComputeUsdAccounting.WALLET_CURRENCY.equals(currency))
            vipPeriods.upgradeEligible(memberId);
        String remark=limited(request.get("remark"),1000);
        repository.adjustMoneyLedgerWrite(scope.currentAdminUid(), remark, memberId, "ADMIN:"+operation);
        Map<String,Object> ledger=repository.adjustMoneyLedgerRow(memberId, "ADMIN:"+operation);
        displayFunds(ledger);
        return finishOperation(request,fields("operationId",operation,"ledger",ledger,"wallets",memberWallets(memberId).get("wallets")));
    }

    @Transactional(readOnly=true)
    public Map<String,Object> previewInviter(long memberId,Map<String,Object> request) {
        scope.assertMember(memberId); Long target=migrationTarget(request);
        Map<String,Long> tree=scope.relations().stats(memberId);
        List<Map<String,Object>> chain=validateTarget(memberId,target,tree);
        Map<String,Object> member=repository.previewInviterMemberRow(memberId);
        Object old=null; if(member.get("inviterId")!=null) {
            long parent=number(member.get("inviterId"));
            try {scope.assertMember(parent);old=repository.previewInviterMemberRow2(parent);}catch(BusinessException denied){old=null;}
        }
        return fields("oldInviter",old,"targetMember",target==null?null:chain.get(chain.size()-1),"parentChain",visibleChain(chain),
                "subtreeCount",tree.get("count"),"version",scope.relations().version());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> transferInviter(long memberId,Map<String,Object> request) {
        long version=scope.relations().lock(); lockedMember(memberId);
        Map<String,Object> previous=beginOperation(memberId,"INVITER_TRANSFER",request);if(previous!=null)return previous;
        if(number(request.get("expectedVersion"))!=version)throw new BusinessException("server.compute.relation.previewExpired");
        Long target=migrationTarget(request);Map<String,Long> tree=scope.relations().stats(memberId);
        List<Map<String,Object>> chain=validateTarget(memberId,target,tree);
        Map<String,Object> old=repository.previewInviterMemberRow(memberId);
        hideOutsideInviters(fields("records",List.of(old)));
        repository.transferInviterMemberWrite(target, now(), memberId);scope.relations().changed();
        return finishOperation(request,fields("memberId",memberId,"oldInviterId",old.get("inviterId"),"inviterId",target,"depth",chain.size(),"subtreeCount",tree.get("count"),"version",version+1));
    }

    private List<Map<String,Object>> validateTarget(long memberId,Long target,Map<String,Long> subtree) {
        if(target==null){scope.requireGlobalScope();return List.of();}
        scope.assertActiveMember(target);
        if(scope.relations().contains(memberId,target))throw new BusinessException("server.compute.relation.cycle");
        List<Map<String,Object>> chain=scope.relations().ancestors(target);
        long childDepth=subtree.get("height");
        if(chain.size()+childDepth>ComputeMemberRelations.MAX_DEPTH)throw new BusinessException("server.compute.relation.depthLimit");
        return chain;
    }

    private Long migrationTarget(Map<String,Object> request) {
        if(!request.containsKey("targetUsername"))return nullableId(request.get("targetMemberId"));
        String username=text(request.get("targetUsername"));
        if(username.isEmpty())return null;
        Long target=number(repository.migrationTargetMemberRow(limited(username,40)).get("id"));
        if(request.containsKey("targetMemberId")&&!Objects.equals(target,nullableId(request.get("targetMemberId"))))throw new BusinessException("server.compute.invalidInput");
        return target;
    }

    private List<Map<String,Object>> visibleChain(List<Map<String,Object>> chain) {
        if(scope.isGlobalScope())return chain;
        List<Map<String,Object>> visible=new ArrayList<>();
        for(var row:chain)try{scope.assertMember(number(row.get("id")));visible.add(row);}catch(BusinessException denied){/* 上级不在当前账号范围时不泄漏身份。 */}
        hideOutsideInviters(fields("records",visible));
        return visible;
    }

    private Map<String,Object> beginOperation(long memberId,String type,Map<String,Object> request) {
        return operationResult(memberId,type,request,true);
    }

    private Map<String,Object> operationResult(long memberId,String type,Map<String,Object> request,boolean create) {
        String requestId=text(request.get("requestId"));if(!requestId.matches("[A-Za-z0-9:_-]{8,80}"))throw new BusinessException("server.compute.requestIdRequired");
        String hash;
        try {hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.json(fields("memberId",memberId,"type",type,"request",new TreeMap<>(request))).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception exception){throw new IllegalStateException("操作摘要生成失败",exception);}
        List<Map<String,Object>> old=repository.operationResultMemberOperationRows(scope.currentAdminUid(), requestId);
        if(!old.isEmpty()){
            if(!hash.equals(old.get(0).get("payloadHash")))throw new BusinessException("server.compute.requestConflict");
            if(old.get(0).get("resultJson")==null)throw new BusinessException("server.compute.requestInProgress");
            Map<String,Object> result=data.jsonMap(old.get(0).get("resultJson"));
            if("INVITER_TRANSFER".equals(type)) {
                Map<String,Object> oldParent=fields("inviterId",result.get("oldInviterId"));
                Map<String,Object> currentParent=fields("inviterId",result.get("inviterId"));
                hideOutsideInviters(fields("records",List.of(oldParent,currentParent)));
                result.put("oldInviterId",oldParent.get("inviterId"));result.put("inviterId",currentParent.get("inviterId"));
            }
            return result;
        }
        if(create)data.insert("compute_member_operation",fields("member_id",memberId,"operator_uid",scope.currentAdminUid(),"operation_type",type,"request_id",requestId,"payload_hash",hash,"remark",limited(request.get("remark"),1000),"created_at",now()));
        return null;
    }

    private Map<String,Object> finishOperation(Map<String,Object> request,Map<String,Object> result) {
        repository.finishOperationMemberOperationWrite(data.json(result), scope.currentAdminUid(), request.get("requestId"));return result;
    }

    public Map<String, Object> finances(String resource, Map<String, String> query) {
        String currency=text(query.get("currency"));
        ManagementMemberQuery authorized=authorizedQuery(query);
        Map<String,Object> result=repository.finances(resource,authorized);
        if("withdrawals".equals(resource))enrichWithdrawalHistory(records(result),authorized);
        enrichRelations(records(result),"memberId");
        for(var row:records(result)){
            row.put("isTest",enabled(row.get("isTest")));
            if ("withdrawals".equals(resource)) {
                row.put("walletType", "EARNINGS");
                row.put("attentionIgnored", enabled(row.get("attentionIgnored")));
                if (text(row.get("receiverAddress")).isEmpty()) row.put("receiverAddress", row.get("address"));
            }
        }
        hideOutsideInviters(result);
        if("ledger".equals(resource)) ComputeLedgerDetails.enrich(data,records(result),scope,null);
        ComputePaymentRecords.enrich(data, resource, records(result), catalog.gatewayEnabled());
        new ComputeUsdAccounting(data).presentRecords(resource, records(result));
        ComputeDisplayNames.administrators(data, records(result), Map.of("operatorUid","operatorUsername","createdBy","createdByUsername","reviewedBy","reviewedByUsername"));
        result.put("currency",currency);result.put("defaultCurrency",business.defaultCurrency());
        return result;
    }

    private void enrichWithdrawalHistory(List<Map<String,Object>> rows,ManagementMemberQuery authorized) {
        if(rows.isEmpty())return;
        List<Long> members=rows.stream().map(row->number(row.get("memberId"))).distinct().toList();
        Map<Long,Map<String,Object>> totals=new HashMap<>();
        for(long member:members)totals.put(member,fields("currency",ComputeUsdAccounting.UNIT,"totalDeposits","0","totalWithdrawals","0",
                "depositUnpricedCount",0L,"withdrawalUnpricedCount",0L));
        // 个人历史包括测试用户，且不继承提款列表的时间、状态、币网或钱包筛选。
        for(var history:repository.memberPaymentHistory(members,authorized.scope())) {
            Map<String,Object> total=totals.get(number(history.get("memberId")));
            if(total==null)continue;
            boolean deposit="DEPOSIT".equals(history.get("paymentType"));
            total.put(deposit?"totalDeposits":"totalWithdrawals",history.get("amount"));
            total.put(deposit?"depositUnpricedCount":"withdrawalUnpricedCount",number(history.get("unpricedCount")));
        }
        for(var row:rows)row.put("historyTotals",new LinkedHashMap<>(totals.get(number(row.get("memberId")))));
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> manualDeposit(Map<String,Object> request) {
        scope.relations().lock();long member=resolveMember(request);lockedMember(member);
        requireManualFields(request,false);
        Map<String,Object> previous=beginOperation(member,"MANUAL_DEPOSIT",request);if(previous!=null)return displayFunds(previous);
        Map<String,Object> values=new LinkedHashMap<>(request);
        if(text(values.get("network")).isEmpty()) {
            var channel=new ComputeCurrencyService(data).manualDepositChannel(required(request,"walletType",16),amount(request.get("amount")));
            if(!text(request.get("currency")).isEmpty()&&!text(request.get("currency")).equals(channel.get("currency")))throw new BusinessException("server.compute.quoteChanged");
            values.put("currency",channel.get("currency"));values.put("network",channel.get("network"));
        }
        return displayFunds(finishOperation(request,business.manualDeposit(member,values,scope.currentAdminUid())));
    }

    @Transactional(readOnly=true)
    public Map<String,Object> manualWithdrawalQuote(Map<String,Object> request) {
        long member=resolveMember(request);scope.assertMember(member);
        Map<String,Object> values=manualWithdrawalValues(member,request);
        Map<String,Object> quote=new LinkedHashMap<>(business.quote(member,values));quote.remove("rule");
        for(String key:List.of("currency","network","addressId","address","memo","fields"))quote.put(key,values.get(key));return quote;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> manualWithdrawal(Map<String,Object> request) {
        return manualWithdrawal(request,null);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> manualWithdrawal(Map<String,Object> request,ComputeWithdrawalAddressGuard.Proof proof) {
        scope.relations().lock();long member=resolveMember(request);lockedMember(member);
        requireManualFields(request,true);
        Map<String,Object> previous=beginOperation(member,"MANUAL_WITHDRAWAL",request);if(previous!=null)return displayFunds(previous);
        Map<String,Object> values=manualWithdrawalValues(member,request);
        if(text(values.get("expectedAccountingRate")).isEmpty() || text(values.get("expectedAccountingAmount")).isEmpty()) {
            var quote=business.quote(member,values);
            if(text(values.get("expectedAccountingRate")).isEmpty())values.put("expectedAccountingRate",quote.get("accountingRate"));
            if(text(values.get("expectedAccountingAmount")).isEmpty())values.put("expectedAccountingAmount",quote.get("accountingAmount"));
        }
        // 操作幂等摘要保持原请求；自动报价只进入业务副本，重放不会因新汇率变成另一笔操作。
        return displayFunds(finishOperation(request,business.manualWithdrawal(member,values,scope.currentAdminUid(),proof)));
    }

    ComputeWithdrawalAddressGuard.Preparation prepareManualWithdrawal(Map<String,Object> request) {
        ComputeWithdrawalAddressGuard.assertOutsideTransaction();
        long member=resolveMember(request);
        repository.prepareManualWithdrawalMemberRow(member);
        requireManualFields(request,true);
        var previous=operationResult(member,"MANUAL_WITHDRAWAL",request,false);
        if(previous!=null)return new ComputeWithdrawalAddressGuard.Preparation(displayFunds(previous),null);
        return new ComputeWithdrawalAddressGuard.Preparation(null,business.manualWithdrawalCheck(member,manualWithdrawalValues(member,request)));
    }

    private Map<String,Object> manualWithdrawalValues(long member,Map<String,Object> request) {
        Map<String,Object> values=new LinkedHashMap<>(request);
        if(text(values.get("network")).isEmpty()) values.putAll(new ComputeCurrencyService(data).manualWithdrawalRecipient(member,amount(request.get("amount"))));
        return values;
    }

    private long resolveMember(Map<String,?> request) {
        if(!text(request.get("username")).isEmpty()) {
            long id=number(repository.migrationTargetMemberRow(limited(request.get("username"),40)).get("id"));
            if(!text(request.get("memberId")).isEmpty()&&id!=number(request.get("memberId")))throw new BusinessException("server.compute.invalidInput");
            scope.assertMember(id);return id;
        }
        long id=number(request.get("memberId"));scope.assertMember(id);return id;
    }

    private static void requireManualFields(Map<String,Object> request,boolean withdrawal) {
        Set<String> permitted=new HashSet<>(List.of("memberId","username","currency","network","amount","requestId","remark"));
        permitted.addAll(withdrawal?List.of("addressId","address","memo","fields","expectedFee","expectedNetAmount","expectedAccountingRate","expectedAccountingAmount"):List.of("walletType"));
        if(!permitted.containsAll(request.keySet()))throw new BusinessException("server.compute.invalidInput");
        if(text(request.get("remark")).isEmpty())throw new BusinessException("server.compute.manualRemarkRequired");
        limited(request.get("remark"),1000);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> reviewDeposit(long id, boolean approved, String remark, int operator) {
        scope.relations().lock();
        long memberId = financialMember("compute_deposit", id); lockedMember(memberId);
        return displayFunds(business.reviewDeposit(id, approved, remark, operator));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> reviewWithdrawal(long id, boolean approved, String remark, int operator) {
        scope.relations().lock();
        long memberId = financialMember("compute_withdrawal", id); lockedMember(memberId);
        return displayFunds(business.reviewWithdrawal(id, approved, remark, operator));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> directApproveWithdrawal(long id, String remark, int operator) {
        scope.relations().lock();
        long memberId = financialMember("compute_withdrawal", id); lockedMember(memberId);
        return displayFunds(business.directApproveWithdrawal(id, remark, operator));
    }

    /** 仅调整管理端提醒；不触碰订单审核、资金、外部提交及自动同步。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> setWithdrawalAttention(long id, boolean ignored, int operator) {
        scope.relations().lock();
        long memberId = financialMember("compute_withdrawal", id); lockedMember(memberId);
        repository.setWithdrawalAttentionWithdrawalWrite(ignored ? 1 : 0, operator, now(), id);
        return fields("id",id,"attentionIgnored",ignored);
    }

    private Map<String,Object> displayFunds(Map<String,Object> row) {
        ComputeDisplayNames.members(data,List.of(row),Map.of("memberId","username"));
        ComputeDisplayNames.administrators(data,List.of(row),Map.of("operatorUid","operatorUsername","createdBy","createdByUsername","reviewedBy","reviewedByUsername"));
        return row;
    }

    public Map<String, Object> addresses(Map<String, String> query) {
        Map<String,Object> result=repository.addresses(authorizedQuery(query));
        for(var row:records(result)){decodeAddress(row);enrichRelation(row,number(row.get("memberId")));}return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> saveAddress(Long id, Map<String, Object> request) {
        return saveAddress(id,request,null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> saveAddress(Long id, Map<String, Object> request,ComputeWithdrawalAddressGuard.Proof proof) {
        scope.relations().lock();
        if (id == null) {
            long memberId = resolveMember(request); lockedMember(memberId);
            Map<String, Object> saved = business.saveAddress(memberId, request,proof); id = number(saved.get("id"));
        } else {
            List<Long> ids = repository.financialOwner("compute_address",id);
            if (ids.isEmpty()) throw ComputeAgentScope.denied(); lockedMember(ids.get(0));
        }
        Map<String, Object> old = repository.saveAddressAddressRow(id);
        boolean active = enabled(request.getOrDefault("enabled", old.get("enabled")));
        // 重新启用会重新占用用户名额；仅编辑已启用地址不额外占位，用户已移除的历史记录不占位。
        if (active && !enabled(old.get("removed")))
            new ComputeCurrencyService(data).assertAddressCapacity(number(old.get("memberId")), id);
        var check=managedAddressCheck(old,request);
        var normalized=check==null?null:check.recipient();
        var channel=check==null?null:check.channel();
        String address=normalized==null?text(old.get("address")):normalized.address();
        String memo=normalized==null?text(old.get("memo")):normalized.memo();
        Object addressFields=normalized==null?old.get("fieldsJson"):data.json(normalized.fields());
        String label = request.containsKey("label") ? required(request, "label", 80) : text(old.get("label"));
        Object addressKey=normalized==null?old.get("addressKey"):business.validateManagedAddress(number(old.get("memberId")),channel,normalized,proof);
        try { repository.saveAddressAddressWrite(label, address, memo, addressFields, addressKey, active, now(), id); }
        catch (DuplicateKeyException exception) { throw new BusinessException("server.compute.duplicateConfiguration"); }
        Map<String,Object> result=repository.saveAddressAddressRow2(id);decodeAddress(result);return result;
    }

    ComputeWithdrawalAddressGuard.Check prepareManagedAddress(Long id,Map<String,Object> request) {
        ComputeWithdrawalAddressGuard.assertOutsideTransaction();
        if(id==null)return business.addressCheck(resolveMember(request),request);
        var old=repository.saveAddressAddressRow2(id);
        scope.assertMember(number(old.get("memberId")));
        return managedAddressCheck(old,request);
    }

    private ComputeWithdrawalAddressGuard.Check managedAddressCheck(Map<String,Object> old,Map<String,Object> request) {
        boolean changing=request.containsKey("address")||request.containsKey("memo")||request.containsKey("fields");
        if(!changing && !enabled(request.getOrDefault("enabled",old.get("enabled"))))return null;
        var input=new LinkedHashMap<>(old);
        if(old.get("fieldsJson")!=null)input.put("fields",data.jsonMap(old.get("fieldsJson")));
        input.putAll(request);
        return business.managedAddressCheck(number(old.get("memberId")),text(old.get("currency")),text(old.get("network")),input);
    }

    public Map<String, Object> overview(Map<String, String> query) {
        return overviewAt(query, Instant.now());
    }

    Map<String, Object> overviewAt(Map<String, String> query, Instant currentInstant) {
        String configuredZone = systemSettings == null ? repository.systemTimeZones().stream().findFirst().orElse("Asia/Shanghai")
                : systemSettings.resolveSystemTimeZone();
        ZoneId zone = ZoneId.of(configuredZone); LocalDate today = currentInstant.atZone(zone).toLocalDate();
        LocalDateTime todayStart = utcDayStart(today, zone), tomorrowStart = utcDayStart(today.plusDays(1), zone);
        String currency = selectedCurrency(query);
        ManagementMemberQuery search=authorizedQuery(query);
        Map<String,Object> result=repository.overviewMembers(search,todayStart,tomorrowStart);
        result.put("scope", scope.description()); result.put("summaryCurrency", currency);result.put("defaultCurrency",business.defaultCurrency()); result.put("timeZone", zone.getId());
        Map<String, Map<String, Object>> summaries = new TreeMap<>();
        for (Map<String, Object> row : repository.overviewAtCurrencyRows()) summaries.put(text(row.get("currency")), emptyTotals(text(row.get("currency"))));
        for (String resource : List.of("orders", "auto", "deposits", "withdrawals", "wallets", "rewards")) {
            for(Map<String,Object> row:repository.overviewFunds(search,resource)) {
                var summary=summaries.computeIfAbsent(text(row.get("currency")), ComputeManagementService::emptyTotals);
                if("auto".equals(resource)) {
                    for(String field:List.of("activePrincipal","totalIncome"))summary.put(field,decimal(summary.get(field)).add(decimal(row.get(field))).toPlainString());
                    for(String field:List.of("autoIncome","autoAmount","autoCount","activeAuto"))summary.put(field,row.get(field));
                } else summary.putAll(row);
            }
        }
        for (String counter : List.of("activeOrders", "pendingDeposits", "pendingWithdrawals", "autoCount", "activeAuto")) result.put(counter, summaries.values().stream().mapToLong(row -> number(row.getOrDefault(counter, 0))).sum());
        result.put("currencySummaries", new ArrayList<>(summaries.values()));
        result.put("walletDistribution",repository.walletDistribution(search));
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int day = 6; day >= 0; day--) trend.add(fields("day", today.minusDays(day).toString(), "deposits", "0", "withdrawals", "0"));
        Map<String,List<Map<String,Object>>> trends=new TreeMap<>();
        for (String kind : List.of("deposits", "withdrawals")) {
            for(var sums:repository.overviewTrend(search,kind,zone,today)) {
                var currencyTrend=trends.computeIfAbsent(text(sums.get("currency")),ignored->trend.stream().map(day->new LinkedHashMap<>(day)).map(day->(Map<String,Object>)day).toList());
                for (int index = 0; index < trend.size(); index++) currencyTrend.get(index).put(kind, decimal(sums.get("day" + index)).toPlainString());
            }
        }
        result.put("trendByCurrency",trends.entrySet().stream().map(entry->fields("currency",entry.getKey(),"trend",entry.getValue())).toList());
        return result;
    }

    /** 团队人数包含测试节点，资金仅统计正式用户；新资金按固定USD，旧订单按原币保留。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> teamReport(Map<String, String> query) {
        String requestedDepth=text(query.get("relationDepth"));
        if(requestedDepth.isEmpty())requestedDepth="ALL";
        int configuredDepth=(int)Math.min(ComputeMemberRelations.MAX_DEPTH,Math.max(1,number(data.setting("teamSupportedDepth","3"))));
        int max="ALL".equalsIgnoreCase(requestedDepth)||"0".equals(requestedDepth)?ComputeMemberRelations.MAX_DEPTH:integer(requestedDepth,1,configuredDepth,configuredDepth);
        boolean includeSelf=!"false".equalsIgnoreCase(query.get("includeSelf"));
        Map<String,String> rootsQuery=new HashMap<>(query);for(String field:List.of("startTime","endTime","depth","relationDepth","currency"))rootsQuery.remove(field);
        boolean explicitEntry=List.of("memberId","teamMemberId","teamUsername","teamName","agentId","username","keyword","parentMemberId").stream().anyMatch(key->!text(query.get(key)).isEmpty());
        if(!explicitEntry)rootsQuery.put("reportRoots","true");
        Map<String,Object> result=repository.teamReportMembers(authorizedQuery(rootsQuery));
        enrichRelations(result);hideOutsideInviters(result);
        List<Map<String,Object>> roots=records(result);
        for(var root:roots) {
            long id=number(root.get("id"));scope.relations().stats(id);
            Map<String,Object> totals=teamFinancials(id,query,0,max,includeSelf,false);
            clearReportMoney(root);
            root.put("memberId",id);root.put("complete",true);root.putAll(totals);
        }
        Long parent=nullableId(query.get("parentMemberId"));
        result.put("ancestors",parent==null?List.of():visibleChain(scope.relations().ancestors(parent)));
        result.remove("currency");result.remove("summaryCurrency");result.remove("defaultCurrency");
        result.put("parentMemberId",parent);result.put("relationDepth",max==ComputeMemberRelations.MAX_DEPTH?"ALL":max);result.put("depth",max);
        result.put("includeSelf",includeSelf);result.put("truncated",false);
        return result;
    }

    private Map<String,Object> teamFinancials(long root,Map<String,String> query,int exact,int maximum,boolean includeSelf,boolean personal) {
        Map<String,Object> result=new LinkedHashMap<>();for(String key:COUNT_KEYS)result.put(key,0L);
        Map<String,Map<String,Object>> byCurrency=new TreeMap<>();
        var search=new ManagementTeamQuery(root,query,exact,maximum,includeSelf);
        result.put("memberCount",repository.teamMemberCount(search,false));
        result.put("newMembers",repository.teamMemberCount(search,true));
        for(String resource:personal?List.of("orders","auto","deposits","withdrawals","wallets","ledger"):List.of("deposits","withdrawals","wallets")){
            for(var sums:repository.teamFunds(search,resource)) {
                var summary=byCurrency.computeIfAbsent(text(sums.get("currency")),ComputeManagementService::emptyTotals);
                sums.forEach((key,value)->{
                    if("auto".equals(resource) && "activePrincipal".equals(key))summary.put(key,decimal(summary.get(key)).add(decimal(value)).toPlainString());
                    else summary.put(key,value==null?(COUNT_KEYS.contains(key)?0L:"0"):value);
                });
            }
            // 人数跨币去重，不能将各币种 DISTINCT 人数相加。
            if(Set.of("deposits","withdrawals").contains(resource))result.putAll(repository.teamPaymentCounts(search,resource));
        }
        if (personal) {
            for(var sums:repository.teamProducts(search))byCurrency.computeIfAbsent(text(sums.get("currency")),ComputeManagementService::emptyTotals).putAll(sums);
            result.putAll(repository.teamProductCounts(search));
            for(var sums:repository.teamAuto(search))byCurrency.computeIfAbsent(text(sums.get("currency")),ComputeManagementService::emptyTotals).putAll(sums);
            result.put("autoCount",byCurrency.values().stream().mapToLong(row->number(row.get("autoCount"))).sum());
        }
        for(var summary:byCurrency.values()) {
            BigDecimal deposit=decimal(summary.get("totalDeposits")),withdraw=decimal(summary.get("withdrawalNetAmount"));
            summary.put("difference",deposit.subtract(withdraw).toPlainString());
            if (personal) {
                // 金额均值也只取正式资金分母，测试业务笔数不改变金额统计。
                summary.put("averageDeposit",average(deposit,decimal(summary.remove("formalDepositCount")).longValueExact()));
                summary.put("averageWithdrawal",average(withdraw,decimal(summary.remove("formalWithdrawalCount")).longValueExact()));
                summary.put("averageFirstDeposit",average(decimal(summary.get("firstDepositAmount")),decimal(summary.remove("formalFirstDepositMembers")).longValueExact()));
            }
            if (!personal) {
                for (String key : TEAM_OMITTED_FIELDS) summary.remove(key);
                for (String key : List.of("formalDepositCount","formalWithdrawalCount","formalFirstDepositMembers")) summary.remove(key);
            }
        }
        if (!personal) TEAM_OMITTED_FIELDS.forEach(result::remove);
        result.put("currencySummaries",new ArrayList<>(byCurrency.values()));
        return result;
    }

    /** 个人报表只汇总本人，趋势按系统自然日和实际记账单位分别分桶。 */
    public Map<String,Object> personalReport(Map<String,String> query) {
        Map<String,String> identities=new HashMap<>(query);
        identities.remove("startTime");identities.remove("endTime");identities.remove("currency");
        Map<String,Object> result=members(identities);
        for(var row:records(result)) {
            clearReportMoney(row);row.putAll(teamFinancials(number(row.get("id")),query,0,0,true,true));
            long memberId=number(row.get("id"));
            row.put("directCount",repository.personalReportMemberCount(memberId));
            row.put("teamCount",scope.relations().stats(memberId).get("count"));
        }
        ZoneId zone=reportZone();LocalDate today=LocalDate.now(zone);
        LocalDate start,end;
        try {
            start=text(query.get("startTime")).isEmpty()?today.minusDays(6):Instant.parse(query.get("startTime")).atZone(zone).toLocalDate();
            end=text(query.get("endTime")).isEmpty()?today:Instant.parse(query.get("endTime")).atZone(zone).toLocalDate();
        } catch(java.time.DateTimeException invalid) {throw new BusinessException("server.compute.v6.reportDateRange");}
        if(end.isBefore(start)||java.time.temporal.ChronoUnit.DAYS.between(start,end)>30)throw new BusinessException("server.compute.v6.reportDateRange");
        List<LocalDate> days=new ArrayList<>();for(LocalDate day=start;!day.isAfter(end);day=day.plusDays(1))days.add(day);
        Map<String,List<Map<String,Object>>> trends=new TreeMap<>();
        for(String metric:List.of("totalDeposits","totalWithdrawals","totalIncome","autoIncome")) {
            for(var sums:repository.personalTrend(authorizedQuery(identities),metric,zone,start,end)) {
                var trend=trends.computeIfAbsent(text(sums.get("currency")),ignored->days.stream().map(day->fields("day",day.toString(),"totalDeposits","0","totalWithdrawals","0","totalIncome","0","autoIncome","0")).toList());
                for(int index=0;index<trend.size();index++)trend.get(index).put(metric,sums.get("d"+index));
            }
        }
        result.remove("currency");result.remove("summaryCurrency");result.remove("defaultCurrency");
        result.put("trendByCurrency",trends.entrySet().stream().map(entry->fields("currency",entry.getKey(),"trend",entry.getValue())).toList());
        result.put("timeZone",zone.getId());return result;
    }

    private static void clearReportMoney(Map<String,Object> row) {
        MONEY_KEYS.forEach(row::remove);
        for(String field:List.of("currency","computeAvailable","projectAvailable","earningsAvailable","totalBalance","depositWithdrawalDifference","difference","averageDeposit","averageWithdrawal","averageFirstDeposit","orderCount","activeOrders","lastDepositAt","lastWithdrawalAt"))row.remove(field);
    }

    private ZoneId reportZone() {
        if(systemSettings!=null)return ZoneId.of(systemSettings.resolveSystemTimeZone());
        List<String> zones=repository.systemTimeZones();
        return ZoneId.of(zones.isEmpty()?"UTC":zones.get(0));
    }
    private static String average(BigDecimal sum,long count){return count==0?"0":sum.divide(BigDecimal.valueOf(count),8,RoundingMode.HALF_UP).toPlainString();}
    private static final List<String> TEAM_OMITTED_FIELDS=List.of("activePrincipal","totalIncome","autoIncome","autoAmount","teamRewards","activityRewards","productCount","productMembers","productAmount","autoCount","averageDeposit","averageWithdrawal","averageFirstDeposit");
    private static final List<String> MONEY_KEYS=List.of("activePrincipal","totalIncome","autoIncome","autoAmount","totalDeposits","totalWithdrawals","available","frozen","teamRewards","activityRewards","firstDepositAmount","withdrawalAmount","withdrawalFee","withdrawalNetAmount","productAmount");
    private static final List<String> COUNT_KEYS=List.of("firstDepositMembers","depositCount","depositMembers","withdrawalCount","withdrawalMembers","newMembers","productCount","productMembers","autoCount");
    private static Map<String,Object> emptyTotals(String currency){Map<String,Object> row=fields("currency",currency);for(String key:MONEY_KEYS)row.put(key,"0");for(String key:COUNT_KEYS)row.put(key,0L);return row;}

    private static LocalDateTime utcDayStart(LocalDate day, ZoneId zone) { return LocalDateTime.ofInstant(day.atStartOfDay(zone).toInstant(), ZoneOffset.UTC); }
    private static String placeholders(int size) { return String.join(",", Collections.nCopies(size, "?")); }

    private Map<String, Object> lockedMember(long id) {
        scope.assertMember(id);
        Map<String, Object> result = repository.lockedMemberMemberRow(id);
        scope.assertMember(id);
        return result;
    }

    private void hideOutsideInviters(Map<String, Object> page) {
        if (scope.isGlobalScope()) return;
        @SuppressWarnings("unchecked") List<Map<String, Object>> records = (List<Map<String, Object>>) page.get("records");
        List<Long> inviterIds = records.stream().map(row -> nullableId(row.get("inviterId"))).filter(Objects::nonNull).distinct().toList();
        if (inviterIds.isEmpty()) return;
        Set<Long> visible=repository.visibleMembers(inviterIds,scope.queryScope());
        for (Map<String, Object> row : records) if (row.get("inviterId") != null && !visible.contains(number(row.get("inviterId")))) {
            row.put("inviterId", null); row.put("inviterName", null);
        }
    }

    private long financialMember(String table, long id) {
        List<Long> ids = repository.financialOwner(table,id);
        if (ids.isEmpty()) throw ComputeAgentScope.denied(); return ids.get(0);
    }

    private void validateGroup(long groupId, long memberId) {
        List<Map<String,Object>> groups=repository.validateGroupMemberGroupRows(groupId);
        if(groups.isEmpty()) throw new BusinessException("server.compute.management.groupUnavailable");
        Long owner=nullableId(groups.get(0).get("ownerMemberId"));
        if(owner==null)return;
        scope.assertMember(owner);
        // 组所有者只控制配置的可见性；普通成员可分配到管理员可见的任一组，邀请子树不是分组边界。
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> records(Map<String,Object> page) { return (List<Map<String,Object>>)page.get("records"); }

    private void enrichRelations(Map<String,Object> page) {
        enrichRelations(records(page),"id");
    }

    private void enrichRelation(Map<String,Object> row,long memberId) {
        Map<String,Object> one = fields("id",memberId);
        enrichRelations(List.of(one),"id");
        for(String key:List.of("depth","topMemberId","topName","hasChildren","directCount","agentId","agentName")) row.put(key,one.get(key));
    }

    private void enrichRelations(List<Map<String,Object>> rows,String memberField) {
        if(rows.isEmpty())return;
        Map<Long,ComputeMemberRelations.Summary> summaries = scope.relations().summaries(rows.stream().map(row->number(row.get(memberField))).toList(),scope.queryScope());
        Set<Long> visibleRoots = new HashSet<>();
        if(scope.isGlobalScope()) summaries.values().forEach(item->visibleRoots.add(item.topMemberId()));
        else {
            List<Long> roots = summaries.values().stream().map(ComputeMemberRelations.Summary::topMemberId).distinct().toList();
            visibleRoots.addAll(repository.visibleMembers(roots,scope.queryScope()));
        }
        for(Map<String,Object> row:rows){
            var summary=summaries.get(number(row.get(memberField)));
            boolean visible=visibleRoots.contains(summary.topMemberId());
            row.put("depth",summary.depth());row.put("hasChildren",summary.hasChildren());
            row.put("directCount",summary.directCount());
            row.put("topMemberId",visible?summary.topMemberId():null);row.put("topName",visible?summary.topName():null);
            row.put("agentId",row.get("topMemberId"));row.put("agentName",row.get("topName"));
        }
    }

    private static String languageCode(Object value) { return text(value).replace('_','-').toLowerCase(Locale.ROOT); }

    /** 历史中文列可能存放英文，只有真正的中文配置才覆盖完整语言地区码的中文名称。 */
    static String chineseLanguageName(Object code,Object configuredChineseName) {
        return chineseLanguageName(code,configuredChineseName,null);
    }

    static String chineseLanguageName(Object code,Object configuredChineseName,Object legacyName) {
        return org.token.util.LanguageDisplayNames.chinese(code,configuredChineseName,legacyName);
    }

    /** 解析并复核需要独立授权的团队入口，再把只含值的条件交给Repository。 */
    private ManagementMemberQuery authorizedQuery(Map<String,String> input) {
        Map<String,String> query=new LinkedHashMap<>(input);
        String root=text(query.getOrDefault("teamMemberId",query.get("agentId")));
        String name=text(query.getOrDefault("teamUsername",query.get("teamName")));
        if(!name.isEmpty())root=String.valueOf(resolveMember(Map.of("username",name)));
        if(!root.isEmpty()){scope.assertMember(number(root));query.put("teamMemberId",root);}
        if(!text(query.get("parentMemberId")).isEmpty())scope.assertMember(number(query.get("parentMemberId")));
        return new ManagementMemberQuery(scope.queryScope(),query);
    }

    private void update(String table, long id, Map<String, Object> values) {
        data.update(table, id, values);
    }

    private static boolean allowed(String... codes) {
        var authentication = SecurityContextHolder.getContext().getAuthentication(); if (authentication == null) return false;
        Set<String> expected = new HashSet<>(List.of(codes)); expected.add("super");
        return authentication.getAuthorities().stream().anyMatch(value -> expected.contains(value.getAuthority()));
    }

    private static String required(Map<String, Object> request, String key, int max) {
        String value = limited(request.get(key), max); if (value.isEmpty()) throw new BusinessException("server.compute.invalidInput"); return value;
    }
    private static String limited(Object value, int max) { String text = text(value); if (text.length() > max) throw new BusinessException("server.compute.invalidInput"); return text; }
    private void decodeAddress(Map<String,Object> row) { Object raw=row.remove("fieldsJson");row.put("fields",raw==null?Map.of():data.jsonMap(raw)); }
    private Map<String,Object> ungrouped() {
        return fields("id",0,"name","未分组","nameKey","admin.compute.management.ungrouped","builtIn",true,"enabled",true,
                "memberCount",repository.groupMemberCount(0,scope.queryScope()));
    }

    private static Long groupId(Object value) { return "0".equals(text(value))?null:nullableId(value); }
    private String selectedCurrency(Map<String,?> request) {String value=text(request.get("currency"));return value.isEmpty()?business.defaultCurrency():limited(value,16);}
    private static boolean booleanInput(Object value) {String text=text(value);if(!Set.of("true","false","1","0").contains(text))throw new BusinessException("server.compute.invalidInput");return "true".equals(text)||"1".equals(text);}
    private static Long nullableId(Object value) { if (text(value).isEmpty()) return null; long id = number(value); if (id <= 0) throw new BusinessException("server.compute.invalidInput"); return id; }
    private static int integer(Object value, int min, int max, int fallback) { long number = value == null ? fallback : number(value); if (number < min || number > max) throw new BusinessException("server.compute.invalidInput"); return (int) number; }
}
