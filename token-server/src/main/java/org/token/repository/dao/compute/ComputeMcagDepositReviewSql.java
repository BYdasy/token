package org.token.repository.dao.compute;

/** 充值异常计数与分页共用同一范围；归集只提供状态核对，不能作为充值入账凭据。 */
public final class ComputeMcagDepositReviewSql {
    private ComputeMcagDepositReviewSql() { }

    private static String jsonText(String column, String key) {
        return "JSON_UNQUOTE(JSON_EXTRACT(CASE WHEN JSON_VALID(" + column + ") THEN " + column
                + " ELSE '{}' END,'$." + key + "'))";
    }

    private static final String ORDER_REVERSED = "(d.local_deposit_id IS NOT NULL AND (d.remote_status='REVIEW' OR "
            + jsonText("d.snapshot_json", "chain_status") + "='ORPHANED'))";
    private static final String BATCH_PURPOSE = jsonText("d.collection_json", "purpose");
    private static final String SAFE_RECORD = "(f.id IS NULL OR f.record_source='MCAG')";
    private static final String SAME_RECORD = "(d.record_id IS NULL OR d.local_deposit_id IS NULL OR d.record_id=d.local_deposit_id)";
    private static final String RECOVERY_REASONS = "('AMOUNT_PRECISION','UNBOUND_ADDRESS','CHAIN_REVERSED','RISK_HELD','NOT_CREDITED','RATE_UNAVAILABLE','ACCOUNTING_PRECISION')";
    private static final String NORMAL_SCOPE = "(COALESCE(m.is_test,0)=0 AND COALESCE(fm.is_test,0)=0"
            + " AND d.reason NOT IN ('TEST_MEMBER_NOT_ALLOWED','CHAIN_PENDING'))";

    private static String branch(String table, String reference, String type, String model,
                                 String review, String extra, String reversed, String reconcilable, boolean binding) {
        String source = binding ? "COALESCE(f.currency,a.currency) currency,COALESCE(f.network,a.network) network,"
                + "COALESCE(f.wallet_type,a.wallet_type) wallet_type," : "f.currency,f.network,f.wallet_type,";
        return "SELECT d." + reference + " gateway_reference,COALESCE(m.id,fm.id) uid,COALESCE(m.username,fm.username) username,"
                + "d.asset_id,d.amount_text,d.state,CASE WHEN " + reversed + " THEN 'CHAIN_REVERSED' ELSE d.reason END reason,"
                + "f.order_no local_order_no," + source + "d.updated_at,'" + type + "' resource_type," + model + " accounting_model,"
                + "CASE WHEN " + SAFE_RECORD + " AND " + SAME_RECORD + " AND " + reconcilable + " THEN 1 ELSE 0 END can_reconcile,"
                + "CASE WHEN '" + type + "'='DEPOSIT_ORDER' AND " + SAFE_RECORD
                + " AND d.record_id IS NULL AND f.id IS NULL AND d.local_deposit_id IS NULL"
                + ("DEPOSIT_ORDER".equals(type) ? " AND d.reason IN " + RECOVERY_REASONS : "")
                + " THEN 1 ELSE 0 END can_recover,"
                + "CASE WHEN f.id IS NULL THEN 1 ELSE 0 END missing_local_order,"
                + "CASE WHEN d.local_deposit_id IS NOT NULL THEN 1 ELSE 0 END credited"
                + " FROM " + table + " d LEFT JOIN compute_deposit f ON f.id=COALESCE(d.record_id,d.local_deposit_id)"
                + " LEFT JOIN compute_member m ON m.id=d.member_id LEFT JOIN compute_member fm ON fm.id=f.member_id"
                + (binding ? " LEFT JOIN compute_mcag_address a ON a.merchant_id=d.merchant_id AND a.environment=d.environment"
                    + " AND a.customer_id=d.customer_id AND a.address_id=d.address_id AND a.asset_id=d.asset_id AND a.network_id=d.network_id" : "")
                + " WHERE d.merchant_id=#{merchant} AND d.environment=#{environment} AND (" + review + ")"
                + " AND (" + NORMAL_SCOPE + " OR " + reversed + ")" + extra;
    }

    private static final String BATCH_REVIEWS = "SELECT d.batch_id gateway_reference,m.id uid,m.username,"
            + "d.asset_id,d.amount_text,d.state,d.reason,NULL local_order_no,a.currency,a.network,a.wallet_type,"
            + "d.updated_at,'COLLECTION_BATCH' resource_type,d.accounting_model,"
            + "1 can_reconcile,0 can_recover,1 missing_local_order,0 credited"
            + " FROM compute_mcag_collection_batch d LEFT JOIN compute_member m ON m.id=d.member_id"
            + " LEFT JOIN compute_mcag_address a ON a.merchant_id=d.merchant_id AND a.environment=d.environment"
            + " AND a.customer_id=d.customer_id AND a.address_id=d.address_id AND a.asset_id=d.asset_id AND a.network_id=d.network_id"
            + " WHERE d.merchant_id=#{merchant} AND d.environment=#{environment}"
            + " AND (d.state='REVIEW' OR d.sweep_status IN ('FAILED','REVIEW'))"
            + " AND " + BATCH_PURPOSE + "='CUSTOMER_DEPOSIT' AND d.accounting_model='RECEIPT_SWEEP'"
            + " AND COALESCE(m.is_test,0)=0 AND d.reason<>'TEST_MEMBER_NOT_ALLOWED'";

    private static final String REVIEWS = BATCH_REVIEWS
            + " UNION ALL " + branch("compute_mcag_deposit_order", "external_order_id", "DEPOSIT_ORDER", "'SINGLE_RECEIPT'",
            "d.state='REVIEW'", "", ORDER_REVERSED, "1=1", true);

    public static String count() { return "SELECT COUNT(*) FROM (" + REVIEWS + ") reviews"; }

    public static String page() {
        return "SELECT * FROM (" + REVIEWS + ") reviews ORDER BY updated_at DESC,resource_type ASC,gateway_reference DESC"
                + " LIMIT #{pageSize} OFFSET #{offset}";
    }
}
