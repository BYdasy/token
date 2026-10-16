package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;
import org.token.repository.compute.ComputeManagementQueries;

/** ComputeManagementService 的参数化数据库语句。 */
@Mapper
public interface ComputeManagementMapper {

    @Select("SELECT id,name,threshold,enabled,vip_validity_days,vip_cycle_policy FROM compute_level ORDER BY threshold,id")
    List<Map<String, Object>> dictionariesLevelRows(@Param("args") Object[] args);

    @Select("SELECT id,currency,network,enabled FROM compute_currency ORDER BY currency,network")
    List<Map<String, Object>> dictionariesCurrencyRows(@Param("args") Object[] args);

    @Select("SELECT id,name,type,currency,enabled FROM compute_product ORDER BY id LIMIT 500")
    List<Map<String, Object>> dictionariesProductRows(@Param("args") Object[] args);

    @Select("SELECT m.id,m.name,p.name AS provider,m.enabled FROM compute_model m JOIN compute_provider p ON p.id=m.provider_id ORDER BY m.id LIMIT 500")
    List<Map<String, Object>> dictionariesModelRows(@Param("args") Object[] args);

    @Select("SELECT uid,username,state FROM ad_master ORDER BY uid LIMIT 500")
    List<Map<String, Object>> dictionariesAdMasterRows(@Param("args") Object[] args);

    @Select("SELECT m.id,m.username,m.invite_code,m.enabled,m.inviter_id FROM compute_admin_member_scope b JOIN compute_member m ON m.id=b.root_member_id WHERE b.admin_uid=#{args[0]} ORDER BY m.id")
    List<Map<String, Object>> bindingsAdminMemberScopeRows(@Param("args") Object[] args);

    @Select("SELECT uid,username FROM ad_master WHERE uid=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> saveBindingAdMasterRow(@Param("args") Object[] args);

    @Select("SELECT r.isSelf is_super FROM ad_master a JOIN user_role r ON r.id=a.role WHERE a.uid=#{args[0]}")
    List<Map<String, Object>> saveBindingAdMasterRows(@Param("args") Object[] args);

    @Delete("DELETE FROM compute_admin_member_scope WHERE admin_uid=#{args[0]}")
    int saveBindingAdminMemberScopeWrite(@Param("args") Object[] args);

    @Insert("INSERT INTO compute_admin_member_scope(admin_uid,root_member_id,created_at) VALUES(#{args[0]},#{args[1]},#{args[2]})")
    int saveBindingAdminMemberScopeWrite2(@Param("args") Object[] args);

    @Update("UPDATE ad_master SET scope_configured_at=#{args[0]} WHERE uid=#{args[1]}")
    int saveBindingScopeConfiguredAt(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_currency WHERE currency=#{args[0]}")
    List<Map<String, Object>> membersCurrencyCount(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_level WHERE id=#{args[0]} AND enabled=1")
    List<Map<String, Object>> updateMemberLevelCount(@Param("args") Object[] args);

    @Delete("DELETE FROM compute_member_session WHERE member_id=#{args[0]}")
    int updateMemberMemberSessionWrite(@Param("args") Object[] args);

    @Delete("DELETE FROM compute_member_login_ticket WHERE member_id=#{args[0]}")
    int updateMemberMemberLoginTicketWrite(@Param("args") Object[] args);

    @Select("SELECT id,username,email,phone,level,vip_started_at,vip_expires_at,enabled,is_test,invitation_enabled,invite_code,inviter_id,group_id,security_score,token_income_percent,remark,address_limit,(pay_password_hash IS NOT NULL) has_pay_password,created_at,updated_at FROM compute_member WHERE id=#{args[0]}")
    List<Map<String, Object>> updateMemberMemberRow(@Param("args") Object[] args);

    @Update("UPDATE compute_member SET group_id=#{args[0]},updated_at=#{args[1]} WHERE id=#{args[2]}")
    int assignGroupMemberWrite(@Param("args") Object[] args);

    @Select("SELECT owner_member_id FROM compute_member_group WHERE id=#{args[0]}")
    List<Map<String, Object>> groupMembersMemberGroupRow(@Param("args") Object[] args);

    @Select("SELECT id,wallet_type,currency,available,frozen,(available+frozen) total FROM compute_wallet WHERE member_id=#{args[0]} ORDER BY currency,wallet_type")
    List<Map<String, Object>> memberWalletsWalletRows(@Param("args") Object[] args);

    @Select("SELECT username FROM compute_member WHERE id=#{args[0]}")
    List<Map<String, Object>> memberWalletsMemberRow(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_member_operation WHERE operator_uid=#{args[0]} AND request_id=#{args[1]}")
    List<Map<String, Object>> adjustMoneyMemberOperationRow(@Param("args") Object[] args);

    @Update("UPDATE compute_ledger SET operator_uid=#{args[0]},remark=#{args[1]},available_before=available_after-amount,frozen_before=frozen_after-frozen_change WHERE member_id=#{args[2]} AND event_key=#{args[3]}")
    int adjustMoneyLedgerWrite(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_ledger WHERE member_id=#{args[0]} AND event_key=#{args[1]}")
    List<Map<String, Object>> adjustMoneyLedgerRow(@Param("args") Object[] args);

    @Select("SELECT inviter_id FROM compute_member WHERE id=#{args[0]}")
    List<Map<String, Object>> previewInviterMemberRow(@Param("args") Object[] args);

    @Select("SELECT id,username,invite_code FROM compute_member WHERE id=#{args[0]}")
    List<Map<String, Object>> previewInviterMemberRow2(@Param("args") Object[] args);

    @Update("UPDATE compute_member SET inviter_id=#{args[0]},updated_at=#{args[1]} WHERE id=#{args[2]}")
    int transferInviterMemberWrite(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_member WHERE username=#{args[0]}")
    List<Map<String, Object>> migrationTargetMemberRow(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_member_operation WHERE operator_uid=#{args[0]} AND request_id=#{args[1]}")
    List<Map<String, Object>> operationResultMemberOperationRows(@Param("args") Object[] args);

    @Update("UPDATE compute_member_operation SET result_json=#{args[0]} WHERE operator_uid=#{args[1]} AND request_id=#{args[2]}")
    int finishOperationMemberOperationWrite(@Param("args") Object[] args);

    @Select("SELECT id FROM compute_member WHERE id=#{args[0]} AND enabled=1")
    List<Map<String, Object>> prepareManualWithdrawalMemberRow(@Param("args") Object[] args);

    @Update("UPDATE compute_withdrawal SET attention_ignored=#{args[0]},attention_updated_by=#{args[1]},attention_updated_at=#{args[2]} WHERE id=#{args[3]}")
    int setWithdrawalAttentionWithdrawalWrite(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_address WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> saveAddressAddressRow(@Param("args") Object[] args);

    @Update("UPDATE compute_address SET label=#{args[0]},address=#{args[1]},memo=#{args[2]},fields_json=#{args[3]},address_key=#{args[4]},enabled=#{args[5]},updated_at=#{args[6]} WHERE id=#{args[7]}")
    int saveAddressAddressWrite(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_address WHERE id=#{args[0]}")
    List<Map<String, Object>> saveAddressAddressRow2(@Param("args") Object[] args);

    @Select("SELECT DISTINCT currency FROM compute_currency")
    List<Map<String, Object>> overviewAtCurrencyRows(@Param("args") Object[] args);

    @Select("SELECT COUNT(*) AS repository_value FROM compute_member WHERE inviter_id=#{args[0]}")
    List<Map<String, Object>> personalReportMemberCount(@Param("args") Object[] args);

    @Select("SELECT id,username,email,phone,level,group_id FROM compute_member WHERE id=#{args[0]} FOR UPDATE")
    List<Map<String, Object>> lockedMemberMemberRow(@Param("args") Object[] args);

    @Select("SELECT owner_member_id FROM compute_member_group WHERE id=#{args[0]} AND enabled=1")
    List<Map<String, Object>> validateGroupMemberGroupRows(@Param("args") Object[] args);

    @SelectProvider(type=ComputeManagementQueries.class,method="groupsCount")
    long groupsCount(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="groupsPage")
    List<Map<String,Object>> groupsPage(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="agentsCount")
    long agentsCount(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="agentsPage")
    List<Map<String,Object>> agentsPage(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="bindingsCount")
    long bindingsCount(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="bindingsPage")
    List<Map<String,Object>> bindingsPage(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="membersCount")
    long membersCount(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="membersPage")
    List<Map<String,Object>> membersPage(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="financesCount")
    long financesCount(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="financesPage")
    List<Map<String,Object>> financesPage(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="memberPaymentHistory")
    List<Map<String,Object>> memberPaymentHistory(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="addressesCount")
    long addressesCount(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="addressesPage")
    List<Map<String,Object>> addressesPage(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="groupOptions")
    List<Map<String,Object>> groupOptions(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="bindingRoots")
    List<Map<String,Object>> bindingRoots(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="languages")
    List<Map<String,Object>> languages(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="overviewMembers")
    List<Map<String,Object>> overviewMembers(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="overviewFunds")
    List<Map<String,Object>> overviewFunds(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="walletDistribution")
    List<Map<String,Object>> walletDistribution(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="overviewTrend")
    List<Map<String,Object>> overviewTrend(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="teamFunds")
    List<Map<String,Object>> teamFunds(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="teamPaymentCounts")
    List<Map<String,Object>> teamPaymentCounts(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="teamProducts")
    List<Map<String,Object>> teamProducts(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="teamProductCounts")
    List<Map<String,Object>> teamProductCounts(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="teamAuto")
    List<Map<String,Object>> teamAuto(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="personalTrend")
    List<Map<String,Object>> personalTrend(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="groupMemberCount")
    long groupMemberCount(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="teamMemberCount")
    long teamMemberCount(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="visibleMembers")
    List<Long> visibleMembers(Map<String,Object> parameters);

    @SelectProvider(type=ComputeManagementQueries.class,method="financialOwner")
    List<Long> financialOwner(Map<String,Object> parameters);

    @Select("SELECT setting_value FROM system_setting WHERE setting_key='system.time-zone'")
    List<String> systemTimeZones();
}
