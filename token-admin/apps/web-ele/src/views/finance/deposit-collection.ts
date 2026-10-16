import collectionGasEnglish from '../../../../../../docs/i18n/current-admin-en.json';

type CollectionRecord = Record<string, unknown>;
type Translate = (key: string) => string;

const gasReasonPrefix = 'admin.compute.v50CollectionGas.reason.';
export const collectionGasReasonCodes = Object.keys(collectionGasEnglish)
  .filter(key => key.startsWith(gasReasonPrefix))
  .map(key => key.slice(gasReasonPrefix.length));

/** 补费上下文与 Crypto collection-gas.ts 一致；同码的代付原因不能复用到归集。 */
export function collectionGasReasonText(reason: string, translate: Translate): string | undefined {
  const fullKey = `${gasReasonPrefix}${reason}`;
  if (!Object.hasOwn(collectionGasEnglish, fullKey)) return undefined;
  const shortKey = fullKey.slice('admin.compute.'.length);
  const translated = translate(shortKey);
  return translated && translated !== shortKey && translated !== fullKey
    ? translated : (collectionGasEnglish as Record<string, string>)[fullKey];
}

/** 英语兜底与有限协议键；未识别的公开原因原样保留，不猜测归集条件。 */
export const depositCollectionEnglish: Record<string, string> = {
  label: 'Collection status',
  processing: 'Collection processing',
  collected: 'Collected',
  notCollected: 'Not collected',
  unknown: 'Collection status pending',
  'status.WAITING': 'Awaiting collection',
  'status.QUEUED': 'Collection queued',
  'status.CONFIRMING': 'Collection confirming',
  'status.SUCCEEDED': 'Collection completed',
  'status.FAILED': 'Collection failed',
  'status.REVIEW': 'Collection pending review',
  'status.UNKNOWN': 'Collection result unknown',
  'reason.WAITING_BATCH': 'Waiting for a collection batch',
  'reason.SYNC_PENDING': 'Waiting for collection status to sync',
  'reason.LINK_CONFLICT': 'Collection reference needs review',
  'reason.SERVICE_FEE_PENDING': 'Insufficient service fee balance',
  'reason.INSUFFICIENT_NATIVE_GAS': 'Insufficient native currency for network fees',
  'reason.SWEEP_PENDING': 'Waiting for the previous collection',
  'reason.WAITING_ENERGY': 'Waiting for energy resources',
  'reason.BELOW_THRESHOLD': 'Collection threshold not met',
  'reason.FEE_LIMIT_EXCEEDED': 'Estimated network fee exceeds the limit',
  'reason.GAS_TOPUP_PENDING': 'Waiting for network fee funding',
  'reason.GAS_TOPUP_UNCONFIRMED': 'Waiting for fee funding confirmation',
  'reason.GAS_TOPUP_COUNT_LIMIT': 'Fee funding attempt limit reached',
  'reason.GAS_TOPUP_POLICY_LIMIT': 'Fee funding policy limit reached',
  'reason.GAS_TOPUP_DAILY_LIMIT': 'Daily fee funding budget is insufficient',
  'reason.GAS_TOPUP_UNECONOMICAL': 'Waiting for economical fee funding',
  'reason.CHAIN_REVERSED': 'On-chain reversal requires review',
  'reason.COLLECTION_FAILED': 'Collection failed',
  'reason.COLLECTION_REVIEW': 'Collection requires review',
  'reason.DISABLED_ADDRESS': 'Deposit address is disabled',
  'reason.UNBOUND_ADDRESS': 'Deposit address ownership needs review',
  'reason.SWEEP_DISABLED': 'Collection execution is disabled',
  'reason.INVALID_FEE_CONFIGURATION': 'Collection fee configuration needs review',
  'reason.INVALID_WALLET': 'Collection wallet is unavailable',
  'reason.COLLECTION_RETRY': 'Collection is waiting to retry',
  'reason.RPC_UNAVAILABLE': 'On-chain verification is temporarily unavailable',
  'reason.GAS_TOPUP_COOLDOWN': 'Waiting before recalculating fee funding',
  'reason.GAS_TOPUP_QUOTE_EXPIRED': 'Fee funding quote expired; waiting for a new estimate',
  'reason.CHAIN_REVERTED': 'On-chain execution failed',
  'reason.CHAIN_EXECUTION_UNVERIFIED': 'On-chain execution result needs review',
};

/** 协议别名只用于展示，不能改写原始原因码或据此改变资金状态。 */
const collectionReasonAliases: Record<string, string> = {
  BELOW_SWEEP_THRESHOLD: 'BELOW_THRESHOLD',
  WAITING_GAS_TOPUP: 'GAS_TOPUP_UNCONFIRMED',
  FEE_CAP_EXCEEDED: 'FEE_LIMIT_EXCEEDED',
  CHAIN_REORG_REVIEW: 'CHAIN_REVERSED',
  CHAIN_REORG_OR_REVERTED: 'CHAIN_REVERSED',
  BATCH_REVIEW_REQUIRED: 'COLLECTION_REVIEW',
};

function collectionReasonCode(reason: string): string {
  return Object.hasOwn(collectionReasonAliases, reason) ? collectionReasonAliases[reason]! : reason;
}

export function depositCollectionText(key: string, translate: Translate) {
  const fullKey = `v49Merchant.depositCollection.${key}`;
  const translated = translate(fullKey);
  return translated && translated !== fullKey && translated !== `admin.compute.${fullKey}`
    ? translated : depositCollectionEnglish[key] || key;
}

export function depositCollectionReasonText(reason: unknown, translate: Translate): string {
  const raw = reason == null ? '' : String(reason);
  if (!raw) return '';
  const code = collectionReasonCode(raw);
  const gasReason = collectionGasReasonText(code, translate);
  if (gasReason !== undefined) return gasReason;
  const key = `reason.${code}`;
  return Object.hasOwn(depositCollectionEnglish, key) ? depositCollectionText(key, translate) : raw;
}

export function depositCollectionPresentation(resource: string, row: CollectionRecord, translate: Translate) {
  if (resource !== 'deposits' || row.recordSource !== 'MCAG') return null;
  const state = String(row.collectionState || 'UNKNOWN');
  const status = String(row.collectionStatus || '');
  const reason = String(row.collectionReason || '');
  const displayedReason = collectionReasonCode(reason);
  const collected = state === 'COLLECTED' && status === 'SUCCEEDED';
  const notCollected = state === 'NOT_COLLECTED' && (['WAITING', 'QUEUED', 'CONFIRMING', 'FAILED'].includes(status) || (!status && reason === 'WAITING_BATCH'));
  const statusKey = `status.${status}`;
  const statusText = status ? (Object.hasOwn(depositCollectionEnglish, statusKey)
    ? depositCollectionText(statusKey, translate) : status) : '';
  const reasonText = depositCollectionReasonText(reason, translate);
  const label = depositCollectionText(collected ? 'collected' : notCollected ? 'notCollected' : 'unknown', translate);
  const needsReview = ['FAILED', 'REVIEW', 'UNKNOWN'].includes(status) || [
    'LINK_CONFLICT', 'CHAIN_REVERSED', 'COLLECTION_REVIEW', 'INVALID_FEE_CONFIGURATION',
    'INVALID_WALLET', 'CHAIN_REVERTED', 'CHAIN_EXECUTION_UNVERIFIED',
  ].includes(displayedReason);
  return {
    label,
    type: (needsReview ? 'warning' : collected ? 'success' : 'info') as 'success' | 'warning' | 'info',
    reason: [...new Set([statusText, reasonText].filter(Boolean))].join(' · '),
  };
}
