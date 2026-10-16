package org.token.service.compute;

import org.springframework.jdbc.core.JdbcTemplate;

/** 仅用于隔离 H2 测试补齐本次增量列；不会连接项目数据库。 */
final class ComputeMcagTestSchema {
    private ComputeMcagTestSchema() { }
    static void apply(JdbcTemplate jdbc) {
        TokenTestSchema.create(jdbc, "compute_mcag_address", "compute_mcag_address_current", "compute_mcag_address_generation",
                "compute_mcag_customer_identity", "compute_mcag_channel", "compute_mcag_deposit_order", "compute_mcag_collection_batch",
                "compute_mcag_cursor", "compute_mcag_event", "compute_mcag_payout");
        TokenTestSchema.complete(jdbc, "compute_deposit", "compute_withdrawal", "compute_address");
    }
    static void seedMapping(JdbcTemplate jdbc,String currency,String network,String asset,String remoteNetwork,int decimals) {
        jdbc.update("INSERT INTO compute_mcag_channel(merchant_id,environment,currency,network,asset_id,network_id,decimals,deposit_enabled,payout_enabled,active,mapping_version,remote_version,metadata_json,synced_at,created_at,updated_at) SELECT 'mch_token','LIVE',?,?,?,?,?,1,1,1,1,1,'{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM compute_mcag_channel WHERE merchant_id='mch_token' AND environment='LIVE' AND currency=? AND network=?)",currency,network,asset,remoteNetwork,decimals,currency,network);
    }
}
