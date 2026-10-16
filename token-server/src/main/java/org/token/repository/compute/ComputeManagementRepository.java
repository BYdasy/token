package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeManagementMapper;
import org.token.repository.dao.compute.ComputeMemberGroupMapper;
import org.token.model.entity.ComputeMemberGroup;
import org.token.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.token.model.query.MemberScope;
import org.token.model.query.ManagementMemberQuery;
import org.token.model.query.ManagementTeamQuery;
import static org.token.repository.compute.ComputeData.fields;

/** ComputeManagementService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeManagementRepository {
    private final ComputeManagementMapper mapper;
    private final ComputeMemberGroupMapper groups;

    @Autowired
    public ComputeManagementRepository(ComputeManagementMapper mapper,ComputeMemberGroupMapper groups) { this.mapper = mapper;this.groups=groups; }

    public ComputeManagementRepository(ComputeData data) {
        this(data.mapper(ComputeManagementMapper.class),data.mapper(ComputeMemberGroupMapper.class));
    }

    public List<Map<String, Object>> dictionariesLevelRows(Object... args) {
        return ComputeData.normalizeRows(mapper.dictionariesLevelRows(args));
    }

    public List<Map<String, Object>> dictionariesCurrencyRows(Object... args) {
        return ComputeData.normalizeRows(mapper.dictionariesCurrencyRows(args));
    }

    public List<Map<String, Object>> dictionariesProductRows(Object... args) {
        return ComputeData.normalizeRows(mapper.dictionariesProductRows(args));
    }

    public List<Map<String, Object>> dictionariesModelRows(Object... args) {
        return ComputeData.normalizeRows(mapper.dictionariesModelRows(args));
    }

    public List<Map<String, Object>> dictionariesAdMasterRows(Object... args) {
        return ComputeData.normalizeRows(mapper.dictionariesAdMasterRows(args));
    }

    public ComputeMemberGroup lockedGroup(long id) {
        ComputeMemberGroup group=groups.selectOne(new LambdaQueryWrapper<ComputeMemberGroup>()
                .eq(ComputeMemberGroup::getId,id).last("FOR UPDATE"));
        if(group==null)throw new BusinessException("server.compute.notFound");
        return group;
    }

    public Map<String,Object> saveGroup(ComputeMemberGroup group) {
        int changed=group.getId()==null?groups.insert(group):groups.updateById(group);
        if(changed!=1)throw new BusinessException("server.compute.saveFailed");
        ComputeMemberGroup stored=groups.selectById(group.getId());
        if(stored==null)throw new BusinessException("server.compute.notFound");
        return ComputeData.normalizeRow(fields("id",stored.getId(),"name",stored.getName(),
                "owner_member_id",stored.getOwnerMemberId(),
                "note",stored.getNote(),"sort_order",stored.getSortOrder(),"enabled",stored.getEnabled(),
                "created_at",stored.getCreatedAt(),"updated_at",stored.getUpdatedAt()));
    }

    public List<Map<String, Object>> bindingsAdminMemberScopeRows(Object... args) {
        return ComputeData.normalizeRows(mapper.bindingsAdminMemberScopeRows(args));
    }

    public Map<String, Object> saveBindingAdMasterRow(Object... args) {
        return RepositoryResults.required(mapper.saveBindingAdMasterRow(args));
    }

    public List<Map<String, Object>> saveBindingAdMasterRows(Object... args) {
        return ComputeData.normalizeRows(mapper.saveBindingAdMasterRows(args));
    }

    public int saveBindingAdminMemberScopeWrite(Object... args) {
        return mapper.saveBindingAdminMemberScopeWrite(args);
    }

    public int saveBindingAdminMemberScopeWrite2(Object... args) {
        return mapper.saveBindingAdminMemberScopeWrite2(args);
    }

    public int saveBindingScopeConfiguredAt(Object... args) {
        return mapper.saveBindingScopeConfiguredAt(args);
    }

    public Integer membersCurrencyCount(Object... args) {
        return RepositoryResults.scalar(mapper.membersCurrencyCount(args), Integer.class);
    }

    public Integer updateMemberLevelCount(Object... args) {
        return RepositoryResults.scalar(mapper.updateMemberLevelCount(args), Integer.class);
    }

    public int updateMemberMemberSessionWrite(Object... args) {
        return mapper.updateMemberMemberSessionWrite(args);
    }

    public int updateMemberMemberLoginTicketWrite(Object... args) {
        return mapper.updateMemberMemberLoginTicketWrite(args);
    }

    public Map<String, Object> updateMemberMemberRow(Object... args) {
        return RepositoryResults.required(mapper.updateMemberMemberRow(args));
    }

    public int assignGroupMemberWrite(Object... args) {
        return mapper.assignGroupMemberWrite(args);
    }

    public Map<String, Object> groupMembersMemberGroupRow(Object... args) {
        return RepositoryResults.required(mapper.groupMembersMemberGroupRow(args));
    }

    public List<Map<String, Object>> memberWalletsWalletRows(Object... args) {
        return ComputeData.normalizeRows(mapper.memberWalletsWalletRows(args));
    }

    public Map<String, Object> memberWalletsMemberRow(Object... args) {
        return RepositoryResults.required(mapper.memberWalletsMemberRow(args));
    }

    public Map<String, Object> adjustMoneyMemberOperationRow(Object... args) {
        return RepositoryResults.required(mapper.adjustMoneyMemberOperationRow(args));
    }

    public int adjustMoneyLedgerWrite(Object... args) {
        return mapper.adjustMoneyLedgerWrite(args);
    }

    public Map<String, Object> adjustMoneyLedgerRow(Object... args) {
        return RepositoryResults.required(mapper.adjustMoneyLedgerRow(args));
    }

    public Map<String, Object> previewInviterMemberRow(Object... args) {
        return RepositoryResults.required(mapper.previewInviterMemberRow(args));
    }

    public Map<String, Object> previewInviterMemberRow2(Object... args) {
        return RepositoryResults.required(mapper.previewInviterMemberRow2(args));
    }

    public int transferInviterMemberWrite(Object... args) {
        return mapper.transferInviterMemberWrite(args);
    }

    public Map<String, Object> migrationTargetMemberRow(Object... args) {
        return RepositoryResults.required(mapper.migrationTargetMemberRow(args));
    }

    public List<Map<String, Object>> operationResultMemberOperationRows(Object... args) {
        return ComputeData.normalizeRows(mapper.operationResultMemberOperationRows(args));
    }

    public int finishOperationMemberOperationWrite(Object... args) {
        return mapper.finishOperationMemberOperationWrite(args);
    }

    public Map<String, Object> prepareManualWithdrawalMemberRow(Object... args) {
        return RepositoryResults.required(mapper.prepareManualWithdrawalMemberRow(args));
    }

    public int setWithdrawalAttentionWithdrawalWrite(Object... args) {
        return mapper.setWithdrawalAttentionWithdrawalWrite(args);
    }

    public Map<String, Object> saveAddressAddressRow(Object... args) {
        return RepositoryResults.required(mapper.saveAddressAddressRow(args));
    }

    public int saveAddressAddressWrite(Object... args) {
        return mapper.saveAddressAddressWrite(args);
    }

    public Map<String, Object> saveAddressAddressRow2(Object... args) {
        return RepositoryResults.required(mapper.saveAddressAddressRow2(args));
    }

    public List<Map<String, Object>> overviewAtCurrencyRows(Object... args) {
        return ComputeData.normalizeRows(mapper.overviewAtCurrencyRows(args));
    }

    public Long personalReportMemberCount(Object... args) {
        return RepositoryResults.scalar(mapper.personalReportMemberCount(args), Long.class);
    }

    public Map<String, Object> lockedMemberMemberRow(Object... args) {
        return RepositoryResults.required(mapper.lockedMemberMemberRow(args));
    }

    public List<Map<String, Object>> validateGroupMemberGroupRows(Object... args) {
        return ComputeData.normalizeRows(mapper.validateGroupMemberGroupRows(args));
    }
    public Map<String,Object> groups(ManagementMemberQuery search){
        var p=fields("search",search);ComputeManagementQueries.preparePage("groups",p);long count=mapper.groupsCount(p);
        return fields("records",ComputeData.normalizeRows(mapper.groupsPage(p)),"total",count);
    }
    public Map<String,Object> agents(ManagementMemberQuery search){
        var p=fields("search",search);ComputeManagementQueries.preparePage("agents",p);long count=mapper.agentsCount(p);
        return fields("records",ComputeData.normalizeRows(mapper.agentsPage(p)),"total",count);
    }
    public Map<String,Object> bindings(ManagementMemberQuery search){
        var p=fields("search",search);ComputeManagementQueries.preparePage("bindings",p);long count=mapper.bindingsCount(p);
        return fields("records",ComputeData.normalizeRows(mapper.bindingsPage(p)),"total",count);
    }
    public Map<String,Object> addresses(ManagementMemberQuery search){
        var p=fields("search",search);ComputeManagementQueries.preparePage("addresses",p);long count=mapper.addressesCount(p);
        return fields("records",ComputeData.normalizeRows(mapper.addressesPage(p)),"total",count);
    }
    public Map<String,Object> members(ManagementMemberQuery search,String currency,LocalDateTime observedAt,LocalDateTime onlineSince,LocalDateTime idleSince){
        var p=fields("search",search,"currency",currency,"observedAt",observedAt,"onlineSince",onlineSince,"idleSince",idleSince);
        ComputeManagementQueries.preparePage("members",p);
        long count=mapper.membersCount(p);return fields("records",ComputeData.normalizeRows(mapper.membersPage(p)),"total",count);
    }
    public Map<String,Object> teamReportMembers(ManagementMemberQuery search) {
        var p=fields("search",search);ComputeManagementQueries.preparePage("teamMembers",p);
        long count=mapper.membersCount(p);return fields("records",ComputeData.normalizeRows(mapper.membersPage(p)),"total",count);
    }
    public Map<String,Object> finances(String resource,ManagementMemberQuery search){
        var p=fields("search",search,"resource",resource);ComputeManagementQueries.preparePage("finances",p);long count=mapper.financesCount(p);
        return fields("records",ComputeData.normalizeRows(mapper.financesPage(p)),"total",count);
    }
    public List<Map<String,Object>> memberPaymentHistory(Collection<Long> memberIds,MemberScope scope) {
        List<Long> ids=memberIds.stream().distinct().toList();
        return ids.isEmpty()?List.of():ComputeData.normalizeRows(mapper.memberPaymentHistory(fields("ids",ids,"scope",scope)));
    }
    public List<Map<String,Object>> groupOptions(MemberScope scope){return ComputeData.normalizeRows(mapper.groupOptions(fields("scope",scope)));}
    public long groupMemberCount(long id,MemberScope scope){return mapper.groupMemberCount(fields("id",id,"scope",scope));}
    public List<Map<String,Object>> bindingRoots(List<Long> ids){return ComputeData.normalizeRows(mapper.bindingRoots(fields("ids",ids)));}
    public List<Map<String,Object>> languages(Collection<String> ids){return ids.isEmpty()?List.of():ComputeData.normalizeRows(mapper.languages(fields("ids",new ArrayList<>(ids))));}
    public Set<Long> visibleMembers(Collection<Long> ids,MemberScope scope){return ids.isEmpty()?Set.of():new HashSet<>(mapper.visibleMembers(fields("ids",new ArrayList<>(ids),"scope",scope)));}
    public List<Long> financialOwner(String table,long id){return mapper.financialOwner(fields("table",table,"id",id));}
    public List<String> systemTimeZones(){return mapper.systemTimeZones();}
    public Map<String,Object> overviewMembers(ManagementMemberQuery search,LocalDateTime start,LocalDateTime end){return RepositoryResults.required(mapper.overviewMembers(fields("search",search,"todayStart",start,"tomorrowStart",end)));}
    public List<Map<String,Object>> overviewFunds(ManagementMemberQuery search,String resource){return ComputeData.normalizeRows(mapper.overviewFunds(fields("search",search,"resource",resource)));}
    public List<Map<String,Object>> walletDistribution(ManagementMemberQuery search){return ComputeData.normalizeRows(mapper.walletDistribution(fields("search",search)));}
    public List<Map<String,Object>> overviewTrend(ManagementMemberQuery search,String kind,ZoneId zone,LocalDate today){return ComputeData.normalizeRows(mapper.overviewTrend(fields("search",search,"kind",kind,"zone",zone,"today",today)));}
    private static Map<String,Object> teamParameters(ManagementTeamQuery query){ComputeManagementQueries.validateReportDates(query.filters());return fields("root",query.root(),"query",query.filters(),"exact",query.exact(),"maximum",query.maximum(),"includeSelf",query.includeSelf());}
    public long teamMemberCount(ManagementTeamQuery query,boolean dated){var p=teamParameters(query);p.put("dated",dated);return mapper.teamMemberCount(p);}
    public List<Map<String,Object>> teamFunds(ManagementTeamQuery query,String resource){var p=teamParameters(query);p.put("resource",resource);return ComputeData.normalizeRows(mapper.teamFunds(p));}
    public Map<String,Object> teamPaymentCounts(ManagementTeamQuery query,String resource){var p=teamParameters(query);p.put("resource",resource);return RepositoryResults.required(mapper.teamPaymentCounts(p));}
    public List<Map<String,Object>> teamProducts(ManagementTeamQuery query){return ComputeData.normalizeRows(mapper.teamProducts(teamParameters(query)));}
    public Map<String,Object> teamProductCounts(ManagementTeamQuery query){return RepositoryResults.required(mapper.teamProductCounts(teamParameters(query)));}
    public List<Map<String,Object>> teamAuto(ManagementTeamQuery query){return ComputeData.normalizeRows(mapper.teamAuto(teamParameters(query)));}
    public List<Map<String,Object>> personalTrend(ManagementMemberQuery search,String metric,ZoneId zone,LocalDate start,LocalDate end){return ComputeData.normalizeRows(mapper.personalTrend(fields("search",search,"metric",metric,"zone",zone,"start",start,"end",end)));}
}
