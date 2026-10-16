package org.token.repository.dao.compute;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeMcagConfigSyncService 的参数化数据库语句。 */
@Mapper
public interface ComputeMcagConfigSyncMapper {

    @Select("SELECT last_attempt_at,last_success_at,error_key FROM compute_merchant_snapshot WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND resource='assets'")
    List<Map<String, Object>> statusMerchantSnapshotRows(@Param("args") Object[] args);

    @Select("SELECT currency,network,asset_id,network_id,decimals,active,deposit_enabled,payout_enabled,mapping_version,synced_at FROM compute_mcag_channel WHERE merchant_id=#{args[0]} AND environment=#{args[1]} ORDER BY currency,network")
    List<Map<String, Object>> statusMcagChannelRows(@Param("args") Object[] args);

    @Insert("INSERT INTO compute_merchant_snapshot(merchant_id,environment,resource,last_attempt_at) VALUES(#{args[0]},#{args[1]},'assets',UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE last_attempt_at=GREATEST(UTC_TIMESTAMP(6),TIMESTAMPADD(MICROSECOND,1,last_attempt_at))")
    int synchronizeMerchantSnapshotWrite(@Param("args") Object[] args);

    @Select("SELECT last_attempt_at FROM compute_merchant_snapshot WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND resource='assets' FOR UPDATE")
    List<Map<String, Object>> synchronizeMerchantSnapshotRow(@Param("args") Object[] args);

    @Select("SELECT setting_key FROM system_setting WHERE setting_key='summaryCurrency' FOR UPDATE")
    List<Map<String, Object>> synchronizeSettingRows(@Param("args") Object[] args);

    @Select("SELECT last_attempt_at,snapshot_json FROM compute_merchant_snapshot WHERE merchant_id=#{args[0]} AND environment=#{args[1]} AND resource='assets' FOR UPDATE")
    List<Map<String, Object>> synchronizeMerchantSnapshotRow2(@Param("args") Object[] args);

    @Update("UPDATE compute_merchant_snapshot SET snapshot_json=#{args[0]},last_success_at=UTC_TIMESTAMP(6),error_key=NULL WHERE merchant_id=#{args[1]} AND environment=#{args[2]} AND resource='assets'")
    int synchronizeMerchantSnapshotWrite2(@Param("args") Object[] args);

    @Update("UPDATE compute_merchant_snapshot SET error_key=#{args[0]} WHERE merchant_id=#{args[1]} AND environment=#{args[2]} AND resource='assets' AND last_attempt_at=#{args[3]}")
    int synchronizeMerchantSnapshotWrite3(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_mcag_channel WHERE merchant_id=#{args[0]} AND environment=#{args[1]} FOR UPDATE")
    List<Map<String, Object>> applyMcagChannelRows(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_currency_asset FOR UPDATE")
    List<Map<String, Object>> applyCurrencyAssetRows(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_currency_network FOR UPDATE")
    List<Map<String, Object>> applyCurrencyNetworkRows(@Param("args") Object[] args);

    @Select("SELECT * FROM compute_currency FOR UPDATE")
    List<Map<String, Object>> applyCurrencyRows(@Param("args") Object[] args);

    @Update("UPDATE compute_currency_network SET chain_type=#{args[0]},native_asset_code=#{args[1]},confirm_blocks=#{args[2]},updated_at=#{args[3]} WHERE network_code=#{args[4]}")
    int applyCurrencyNetworkWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_currency SET token_standard=#{args[0]},token_contract=#{args[1]},amount_scale=#{args[2]},updated_at=#{args[3]} WHERE id=#{args[4]}")
    int applyCurrencyWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_channel SET decimals=#{args[0]},deposit_enabled=#{args[1]},payout_enabled=#{args[2]},active=1,mapping_version=mapping_version+#{args[3]},remote_version=#{args[4]},metadata_json=#{args[5]},synced_at=#{args[6]},updated_at=#{args[7]} WHERE id=#{args[8]}")
    int applyMcagChannelWrite(@Param("args") Object[] args);

    @Update("UPDATE compute_mcag_channel SET active=0,deposit_enabled=0,payout_enabled=0,mapping_version=mapping_version+#{args[0]},synced_at=#{args[1]},updated_at=#{args[2]} WHERE id=#{args[3]}")
    int applyMcagChannelWrite2(@Param("args") Object[] args);
}
