/** 商户协议字段直接对应已验签响应，页面只展示真实提供的数据。 */
export const tabs: Record<string, string[]> = {
  collections: ['batches', 'sweeps'],
  logs: ['bills', 'fee-ledger', 'notifications'],
  energy: ['resources'],
  configuration: ['connection', 'synchronization'],
};
export const tabPermissions: Record<string, string> = {
  batches: '80100', sweeps: '80100', 'fee-ledger': '80201', notifications: '80202', resources: '80203', rechecks: '80204',
  connection: '80300', synchronization: '80300', bills: '80200',
};
export const columns: Record<string, string[][]> = {
  bills: [['bill_id', 'occurred_at'], ['symbol', 'network_id'], ['direction', 'bill_type'], ['amount'], ['address', 'counterparty'], ['tx_hash', 'chain_status']],
  'fee-ledger': [['entry_id', 'entry_type'], ['available_delta', 'reserved_delta'], ['available_after', 'reserved_after'], ['asset_id', 'asset_amount'], ['usdt_price', 'price_observed_at'], ['reason', 'created_at']],
  batches: [['batch_id', 'username', 'purpose'], ['asset_id', 'network_id'], ['amount', 'address'], ['display_status', 'business_status', 'sweep_status', 'reason_code'], ['destination_address'], ['updated_at']],
  sweeps: [['operation_id', 'batch_id'], ['operation_type', 'asset_id', 'network_id'], ['amount', 'destination_address'], ['status', 'reason_code'], ['tx_hash', 'actual_fee_native'], ['created_at']],
  resources: [['request_id', 'status', 'reason_code'], ['network_id', 'address'], ['purpose', 'payout_id', 'batch_id', 'operation_id'], ['provider_order_id'], ['resource_items'], ['created_at']],
  notifications: [['event_type', 'resource_id'], ['channel', 'status'], ['attempt', 'last_http_status'], ['last_error'], ['created_at', 'updated_at']],
  rechecks: [['merchant_recheck_id', 'recheck_id', 'username'], ['asset_id', 'network_id'], ['tx_hash', 'address_id'], ['status', 'reason_code'], ['created_at', 'updated_at']],
};
export const filters: Record<string, string[]> = {
  bills: ['network_id', 'asset_id', 'tx_hash', 'address', 'direction', 'bill_type', 'status'],
  'fee-ledger': ['entry_type', 'asset_id', 'resource_id'],
  batches: ['batch_id', 'network_id', 'asset_id', 'status', 'sweep_status', 'reason_code', 'address', 'tx_hash'], sweeps: ['batch_id', 'network_id', 'asset_id', 'status', 'reason_code', 'address', 'tx_hash'],
  rechecks: ['merchant_recheck_id', 'network_id', 'asset_id'], resources: ['request_id', 'operation_id', 'provider_order_id', 'status', 'reason_code', 'network_id', 'address', 'resource_type', 'purpose'],
};
export const cursorResources = new Set(['rechecks']);
export const pagedResources = new Set(['batches', 'sweeps', 'bills', 'fee-ledger', 'resources', 'notifications']);
export const datedResources = new Set(['bills', 'fee-ledger', 'batches', 'sweeps', 'resources']);
export const filterOptions: Record<string, Record<string, string[]>> = {
  bills: { direction: ['IN', 'OUT'], bill_type: ['FUNDING', 'TRANSFER', 'PAYOUT', 'SWEEP', 'GAS_TOPUP', 'NETWORK_FEE', 'UTXO_SPEND'], status: ['OBSERVED', 'CONFIRMED', 'ORPHANED'] },
  'fee-ledger': { entry_type: ['TOPUP', 'COLLECTION_RESERVE', 'COLLECTION_SETTLE', 'COLLECTION_RELEASE', 'CUSTOM_DEBIT', 'RENEWAL'] },
  batches: { status: ['READY', 'CREDITED', 'REVIEW'], sweep_status: ['WAITING', 'QUEUED', 'CONFIRMING', 'SUCCEEDED', 'FAILED', 'REVIEW'] },
  sweeps: { status: ['AWAITING_APPROVAL', 'READY', 'SIGNING', 'SIGNED', 'UNKNOWN', 'CONFIRMING', 'SUCCEEDED', 'FAILED', 'REJECTED', 'REVIEW'] },
  resources: { status: ['PENDING', 'SUBMITTING', 'WAITING', 'UNKNOWN', 'SUCCEEDED', 'READY', 'FAILED', 'CANCELLED'], resource_type: ['ACTIVATE', 'ENERGY', 'BANDWIDTH'], purpose: ['SWEEP', 'PAYOUT', 'EMERGENCY'] },
};
export const details = [
  'balance_status', 'scan_height', 'anchor_height', 'movement_height', 'observed_net', 'difference', 'review_id', 'scan_status',
  'bill_id', 'counterparty', 'direction', 'bill_type', 'source', 'occurred_at',
  'valuation_currency', 'single_payout_review_limit_usdt', 'daily_payout_review_limit_usdt', 'daily_limit_timezone', 'valuation_max_age_seconds', 'accounting_model', 'payout_funding', 'fee_policy',
  'username', 'memo', 'memo_required',
  'block_hash', 'chain_status', 'current_confirmations', 'required_confirmations', 'finality_satisfied', 'risk_status',
  'gross_amount', 'points', 'available', 'reserved', 'fee', 'fee_asset_id', 'merchant_order_id', 'payout_id', 'deposit_id',
  'wallet_id','label','network_id','address','wallet_type','status','monitoring_ready','created_at','updated_at','pool_last_used_at',
  'collection_address','enabled','cutover_height','version','asset_id','symbol','balance','block_height','queried_at','balance_scope',
  'checked_at','balance_checked_at','stale',
  'entry_id','account_sequence','entry_type','resource_id','available_delta','reserved_delta','available_after','reserved_after',
  'asset_amount','price_snapshot_id','usdt_price','price_source','price_observed_at','actor','reason',
  'batch_id','purpose','amount','credited_amount','uncredited_amount','credit_status','display_status','business_status','sweep_status','destination_address','operation_id','fee_reserved','resource_version','reason_code',
  'credit_decision','credit_threshold_snapshot',
  'receipt_id','transfer_event_id','tx_hash','transfer_locator','sender_address','received_at','confirmed_at','operation_type','actual_fee_native',
  'energy_order_id','resource_type','requested_count','required_energy','available_energy','energy_price_sun','provider_order_id','period','quoted_cost','actual_cost','cost_asset','resource_confirmed_at',
  'log_id','action','notification_id','event_type','delivery_id','channel','attempt','last_http_status','last_error','http_status','error_code',
  'recheck_id','merchant_recheck_id','address_id','result_code','completed_at','collection_rate','renewal_amount','renewal_days','renewal_enabled',
  'rate_source','default_version','merchant_version','next_due_at','last_attempt_at','last_result','ignore_amount','credit_threshold','sweep_threshold',
  'decimals','deposit_enabled','payout_enabled','minimum_deposit','minimum_payout','maximum_payout_action',
  'maximum_payout_review_limit','single_payout_review_limit','daily_payout_review_limit','automatic_payout_enabled','override_version',
  'ignore_overridden','credit_overridden','sweep_overridden',
];

export const states = new Set(['ACTIVE','DISABLED','BLOCKED_CONTRACT','WAITING','UNKNOWN','READY','FAILED','CANCELLED','BLOCKED_TRANSPORT','SKIPPED','SUCCEEDED','CREDITED','PENDING','CONFIRMING','REVIEW','SWEPT','SERVICE_FEE_PENDING','WAITING_ENERGY','TOPUP','COLLECTION_RESERVE','COLLECTION_SETTLE','COLLECTION_RELEASE','CUSTOM_DEBIT','RENEWAL','SWEEP','GAS_TOPUP','CUSTOMER_DEPOSIT','SERVICE_FEE','CHAIN_BALANCE','NODE_CONFIRMED_UTXO','MERCHANT','DEFAULT','QUEUED','RUNNING','COMPLETED','MANUAL','PAID',
  'IN','OUT','FUNDING','TRANSFER','NETWORK_FEE','UTXO_SPEND','OBSERVED','ORPHANED','CONFIRMED','BACKFILL','LIVE','PAYOUT','TRANSACTION_LEDGER','EXTERNAL_ADDRESS_TRACKED','SCAN_BEHIND','DIFFERENCE','CHAIN_REVIEW','UNINITIALIZED','ANCHOR_ORPHANED','TRACKING',
  'INDEPENDENT','RECEIPT_SWEEP','SINGLE_RECEIPT','BELOW_THRESHOLD']);
