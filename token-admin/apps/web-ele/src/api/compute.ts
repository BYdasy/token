import { requestClient } from './request';
import { useAccessStore } from '@vben/stores';

/** 金额保持服务端十进制字符串，不在浏览器中执行资金记账。 */
export type ComputeRecord = Record<string, any>;
export interface ComputeAccountingValues {
  accountingCurrency?: string;
  accountingRate?: string | number | null;
  accountingAmount?: string | number | null;
  accountingFee?: string | number | null;
  accountingNetAmount?: string | number | null;
  accountingVersion?: number;
  accountingStatus?: string;
  availableUsd?: string | number;
  availableAmount?: string | number;
  quoteAt?: string;
}
export type ComputeWithdrawalQuote = ComputeRecord & ComputeAccountingValues;
export interface VipRewardRule {
  depth: number;
  firstDepositEnabled: boolean;
  firstDepositRate: string | number;
  depositEnabled: boolean;
  depositRate: string | number;
  vipEnabled: boolean;
  vipRate: string | number;
  productEnabled: boolean;
  productRate: string | number;
  depositCapByCompute: boolean;
  vipRequireComputeBalance: boolean;
  requireEffectiveMember: boolean;
  effectiveDepositThreshold: string | number;
}
export interface ComputePage {
  records: ComputeRecord[];
  total: number;
}
export interface ComputeQuery {
  [key: string]: string | number | boolean | undefined;
  page?: number;
  size?: number;
  keyword?: string;
  status?: string;
  memberId?: string;
  startTime?: string;
  endTime?: string;
  jobId?: number;
  currency?: string;
  groupId?: number;
  agentId?: number;
  parentId?: number;
  activityId?: number | string;
  type?: string;
  kind?: string;
  includeSelf?: boolean;
  depth?: number | string;
}
export type ConfigResource =
  | 'providers'
  | 'products'
  | 'models'
  | 'levels'
  | 'fee-rules'
  | 'transfer-rules';
export type PageResource =
  | 'members'
  | 'deposits'
  | 'withdrawals'
  | 'orders'
  | 'ledger'
  | 'job-runs'
  | 'recoveries'
  | 'groups'
  | 'group-members'
  | 'agents'
  | 'agent-bindings'
  | 'addresses'
  | 'reports/team'
  | 'reports/personal'
  | 'activities'
  | 'activity-participations'
  | 'content';
const base = '/compute/admin';
const options = { timeout: 30_000 };
export const fetchUserPresentationSettings = () =>
  requestClient.get<ComputeRecord>(`${base}/presentation-settings`, options);
export const saveUserPresentationSettings = (data: ComputeRecord) =>
  requestClient.put<ComputeRecord>(`${base}/presentation-settings`, data, options);
export const fetchExchangeSettings = () => requestClient.get<ComputeRecord>(`${base}/exchange-settings`, options);
export const saveExchangeSettings = (provider: string, data: ComputeRecord) => requestClient.put<ComputeRecord>(`${base}/exchange-settings/${provider}`, data, options);
export const refreshExchangeProvider = (provider: string) => requestClient.post<ComputeRecord>(`${base}/exchange-settings/${provider}/refresh`, {}, options);
export const fetchExchangeStatus = () => requestClient.get<ComputeRecord>(`${base}/exchange-settings/status`, { ...options, headers: { 'X-Background-Request': 'true' } });

export const fetchComputeOverview = (params: ComputeQuery = {}) =>
  requestClient.get<ComputeRecord>(`${base}/overview`, { ...options, params });
export const fetchComputeOverviewDetails = (kind: 'DEPOSIT' | 'WITHDRAWAL', params: ComputeQuery = {}) =>
  requestClient.get<ComputePage & { summaryUnit: string }>(`${base}/overview/finance/${kind}`, { ...options, params });
export const fetchComputeAnalytics = (params: ComputeQuery = {}) =>
  requestClient.get<ComputeRecord>(`${base}/analytics`, { ...options, params });
export const fetchComputeGlobalReport = (params: ComputeQuery = {}) =>
  requestClient.get<ComputeRecord>(`${base}/reports/global`, { ...options, params });
export const fetchComputeConfig = (resource: ConfigResource) =>
  requestClient.get<ComputeRecord[]>(`${base}/${resource}`, options);
export interface WithdrawalLimitConfig { enabled: boolean; period: 'DAY' | 'WEEK'; count: number }
export const fetchWithdrawalLimit = () => requestClient.get<WithdrawalLimitConfig>(`${base}/fee-rules/withdrawal-limit`, options);
export const saveWithdrawalLimit = (data: WithdrawalLimitConfig) => requestClient.put<WithdrawalLimitConfig>(`${base}/fee-rules/withdrawal-limit`, data, options);
export interface WithdrawalReviewConfig { mode: 'ALL' | 'THRESHOLD'; thresholdToken: string }
export const fetchWithdrawalReview = () => requestClient.get<WithdrawalReviewConfig>(`${base}/fee-rules/withdrawal-review`, options);
export const saveWithdrawalReview = (data: WithdrawalReviewConfig) => requestClient.put<WithdrawalReviewConfig>(`${base}/fee-rules/withdrawal-review`, data, options);
export const saveComputeConfig = async (
  resource: ConfigResource,
  data: ComputeRecord,
) => {
  const result = await requestClient.post<ComputeRecord>(
    `${base}/${resource}`,
    data,
    options,
  );
  invalidateComputeDictionaries();
  return result;
};
export const fetchComputePage = (
  resource: PageResource,
  params: ComputeQuery,
  background = false,
) =>
  requestClient.get<ComputePage>(`${base}/${resource}`, {
    ...options,
    params,
    ...(background ? { headers: { 'X-Background-Request': 'true', 'X-Client-Silent-Error': '1' } } : {}),
  });
export const updateComputeMember = (
  id: number,
  data: {
    level?: number;
    enabled?: boolean;
    isTest?: boolean;
    invitationEnabled?: boolean;
    password?: string;
    groupId?: number | null;
    agentId?: number | null;
    email?: string;
    phone?: string;
    payPassword?: string;
    securityScore?: number | string;
    tokenIncomePercent?: number | string;
    addressLimit?: number | string | null;
    remark?: string;
  },
) => requestClient.put(`${base}/members/${id}`, data, options);
export const rotateComputeMemberInviteCode = (
  id: number,
  data: { expectedInviteCode: string; requestNo: string },
) => requestClient.post<{ memberId: number; inviteCode: string }>(
  `${base}/members/${id}/invite-code/rotate`, data, options,
);
export const createComputeMember = (data: ComputeRecord) =>
  requestClient.post<ComputeRecord>(`${base}/members`, data, options);
export const createComputeImpersonation = (id: number) =>
  requestClient.post<{ ticket: string; expiresAt: string }>(`${base}/members/${id}/impersonation`, {}, options);
export const forceComputeMemberLogout = (id: number) =>
  requestClient.post<{ revoked: number }>(`${base}/members/${id}/force-logout`, {}, options);
export const getComputeMemberAncestors = (id: number) =>
  requestClient.get<Array<{ username: string; depth: number }>>(`${base}/members/${id}/ancestors`, options);
export const fetchMemberWallets = (id: number) =>
  requestClient.get<{ memberId: number; wallets: ComputeRecord[] }>(
    `${base}/members/${id}/wallets`,
    options,
  );
export const adjustMemberWallet = (
  id: number,
  data: {
    requestId: string;
    currency: string;
    walletType: string;
    direction: 'ADD' | 'SUBTRACT';
    amount: string;
    reason: 'SYSTEM' | 'SALARY';
    remark: string;
  },
) =>
  requestClient.post<ComputeRecord>(
    `${base}/members/${id}/adjustments`,
    data,
    options,
  );
export const previewMemberTransfer = (
  id: number,
  targetUsername: string | null,
) =>
  requestClient.post<ComputeRecord>(
    `${base}/members/${id}/inviter-preview`,
    { targetUsername },
    options,
  );
export const transferMemberInviter = (
  id: number,
  data: {
    targetUsername: string | null;
    expectedVersion: number | string;
    requestId: string;
    remark: string;
  },
) =>
  requestClient.post<ComputeRecord>(
    `${base}/members/${id}/inviter-transfer`,
    data,
    options,
  );
export const reviewComputeFunds = (
  resource: 'deposits' | 'withdrawals',
  id: number,
  data: { approved: boolean; remark: string },
) => requestClient.post(`${base}/${resource}/${id}/review`, data, options);
export const directApproveComputeWithdrawal = (id: number, remark: string) =>
  requestClient.post(`${base}/withdrawals/${id}/direct-approve`, { remark }, options);
export const fetchComputeGatewayStatus = (resource: 'deposits' | 'withdrawals', params?: { page: number; pageSize: number }) =>
  requestClient.get<ComputeRecord>(`${base}/gateway/${resource === 'withdrawals' ? 'payouts' : 'deposits'}/status`, { ...options, params });
export interface DepositReviewRow extends ComputeRecord {
  gatewayReference: string;
  resourceType: 'DEPOSIT_ORDER' | 'COLLECTION_BATCH';
  canReconcile: boolean;
  canRecover: boolean;
  missingLocalOrder: boolean;
}
export interface DepositReconciliationResult extends ComputeRecord {
  resourceType?: DepositReviewRow['resourceType'];
  state?: string;
  reason?: string | null;
  localOrderNo?: string | null;
  credited?: boolean;
  alreadyCredited?: boolean;
  orderCreated?: boolean;
}
export interface DepositRecheckAsset {
  assetId: string;
  networkId: string;
  networkName: string;
  family: string;
  symbol: string;
  displayName: string;
}
export interface DepositRecheckOptions {
  merchantId: string;
  environment: string;
  assets: DepositRecheckAsset[];
}
export interface DepositRecheckRequest {
  requestId: string;
  assetId: string;
  networkId: string;
  txHash: string;
  address: string;
  transferLocator?: string;
  code: string;
}
export interface DepositRecheckResult {
  recheckId: string;
  requestId: string;
  status: string;
  resultCode?: string;
  nextRetryAt?: string;
  resolvedAt?: string;
}
export const fetchDepositRecheckOptions = () =>
  requestClient.get<DepositRecheckOptions>(`${base}/gateway/deposits/recheck-options`, options);
export const createDepositHashRecheck = (body: DepositRecheckRequest) =>
  requestClient.post<DepositRecheckResult>(`${base}/gateway/deposits/recheck`, body, options);
export const reconcileComputePayment = (kind: 'DEPOSIT' | 'PAYOUT', reference: string, remark: string, code: string) =>
  requestClient.post<ComputeRecord>(`${base}/gateway/reconciliation`, { kind, reference, remark, code }, options);
export const cancelComputeUnsentPayout = (reference: string, remark: string, code: string) =>
  requestClient.post<ComputeRecord>(`${base}/gateway/payouts/${encodeURIComponent(reference)}/cancel`, { remark, code }, options);
export const fetchComputeSettings = () =>
  requestClient.get<ComputeRecord>(`${base}/settings`, options);
export const saveComputeSettings = (data: ComputeRecord) =>
  requestClient.put(`${base}/settings`, data, options);
export const fetchComputeJobs = () =>
  requestClient.get<ComputeRecord[]>(`${base}/jobs`, options);
export const saveComputeJob = (
  id: number,
  data: { enabled: boolean; cronExpression: string },
) => requestClient.put(`${base}/jobs/${id}`, data, options);
export const runComputeJob = (id: number) =>
  requestClient.post<ComputeRecord>(`${base}/jobs/${id}/run`, {}, options);
export const issueComputeRecovery = (id: number) =>
  requestClient.post<{ token: string; expiresAt: string }>(
    `${base}/recoveries/${id}/issue`,
    {},
    options,
  );

export interface ComputeDictionaries {
  gatewayEnabled?: boolean;
  teamDepth?: number;
  teamSupportedDepth?: number;
  defaultCurrency?: string;
  summaryCurrency?: string;
  creationRules?: Record<string, boolean>;
  scope: {
    global: boolean;
    canManageGlobal?: boolean;
    agentId?: number | null;
    enabled?: boolean;
    memberIds?: number[];
    rootMemberIds?: number[];
    scopeRestricted?: boolean;
  };
  products?: ComputeRecord[];
  models?: ComputeRecord[];
  levels?: ComputeRecord[];
  currencies?: ComputeRecord[];
  countries?: Array<{ code: string; name: string; englishName: string }>;
  groups?: ComputeRecord[];
  agents?: ComputeRecord[];
  adminAccounts?: ComputeRecord[];
}
let dictionaryState:
  | {
      key: string;
      expires: number;
      value?: ComputeDictionaries;
      pending?: Promise<ComputeDictionaries>;
    }
  | undefined;
/** 只合并当前会话在途字典请求，范围与后台账号状态每次直读。 */
export function fetchComputeDictionaries() {
  const access = useAccessStore();
  const key = `${access.accessToken}:${access.accessCodes.join(',')}`;
  if (dictionaryState?.key === key && dictionaryState.expires > Date.now()) {
    if (dictionaryState.pending) return dictionaryState.pending;
  }
  const state = { key, expires: Date.now() + 30_000 } as NonNullable<
    typeof dictionaryState
  >;
  dictionaryState = state;
  state.pending = requestClient
    .get<ComputeDictionaries>(`${base}/dictionaries`, options)
    .then((value) => {
      state.value = value;
      return value;
    })
    .finally(() => {
      state.pending = undefined;
      if (dictionaryState === state) dictionaryState = undefined;
    });
  return state.pending;
}
export function invalidateComputeDictionaries() {
  dictionaryState = undefined;
}
export async function saveComputeResource(
  resource: 'groups' | 'agents' | 'agent-bindings' | 'activities' | 'content',
  data: ComputeRecord,
) {
  const result = await requestClient.post<ComputeRecord>(
    `${base}/${resource}`,
    data,
    options,
  );
  invalidateComputeDictionaries();
  return result;
}
export const updateComputeAddress = (id: number, data: ComputeRecord) =>
  requestClient.put(`${base}/addresses/${id}`, data, options);
export const disableComputeAddress = (id: number) =>
  requestClient.delete(`${base}/addresses/${id}`, options);
export const setComputeMemberGroup = (
  memberIds: number[],
  groupId: number | null,
) =>
  requestClient.post(`${base}/members/group`, { memberIds, groupId }, options);
export const deleteComputeResource = (
  resource: 'activities' | 'content',
  id: number,
) => requestClient.delete(`${base}/${resource}/${id}`, options);
export const createComputeAddress = (data: ComputeRecord) =>
  requestClient.post(`${base}/addresses`, data, options);
export const fetchComputeContent = (id: number, background = false) =>
  requestClient.get<ComputeRecord>(`${base}/content/${id}`, { ...options,
    ...(background ? { headers: { 'X-Background-Request': 'true' } } : {}) });
export const publishComputeContent = (id: number) =>
  requestClient.post<ComputeRecord>(
    `${base}/content/${id}/publish`,
    {},
    options,
  );
export const recallComputeContent = (id: number) =>
  requestClient.post(`${base}/content/${id}/recall`, {}, options);
export const setComputeContentEnabled = (id: number, enabled: boolean) =>
  requestClient.put(`${base}/content/${id}/enabled`, { enabled }, options);
export const fetchComputeRecipients = (
  id: number,
  params: ComputeQuery & { read?: boolean; completed?: boolean },
) =>
  requestClient.get<ComputePage>(`${base}/content/${id}/recipients`, {
    ...options,
    params,
  });
export const releaseComputeTaskRestriction = (id: number, memberId: number) =>
  requestClient.post(`${base}/content/${id}/recipients/${memberId}/release`, {}, options);
export const startContentTranslation = (
  id: number,
  targetLanguages?: string[],
) =>
  requestClient.post<ComputeRecord>(
    `${base}/content/${id}/translations`,
    { targetLanguages },
    options,
  );
export const fetchContentTranslation = (id: number, taskId: number | string) =>
  requestClient.get<ComputeRecord>(
    `${base}/content/${id}/translation-tasks/${taskId}`,
    { ...options, headers: { 'X-Background-Request': 'true' } },
  );

export type TranslationFormResource = 'CONTENT' | 'PRODUCT' | 'LEVEL' | 'COMPANY_FILE';
export const startFormTranslation = (resource: TranslationFormResource, body: ComputeRecord) =>
  requestClient.post<ComputeRecord>(`${base}/translation-forms/${resource}`, body);
export const fetchFormTranslation = (resource: TranslationFormResource, taskId: number) =>
  requestClient.get<ComputeRecord>(`${base}/translation-forms/${resource}/${taskId}`, { headers: { 'X-Background-Request': 'true' } });

export const startCatalogTranslation = (
  resource: 'products' | 'levels',
  id: number,
  targetLanguages?: string[],
) =>
  requestClient.post<ComputeRecord>(
    `${base}/catalog/${resource}/${id}/translations`,
    { targetLanguages },
    options,
  );
export const fetchCatalogTranslation = (
  resource: 'products' | 'levels',
  id: number,
  taskId: number | string,
) =>
  requestClient.get<ComputeRecord>(
    `${base}/catalog/${resource}/${id}/translation-tasks/${taskId}`,
    { ...options, headers: { 'X-Background-Request': 'true' } },
  );
export const createManualDeposit = (data: ComputeRecord) =>
  requestClient.post<ComputeRecord>(`${base}/deposits/manual`, data, options);
export const quoteManualWithdrawal = (data: ComputeRecord) =>
  requestClient.post<ComputeWithdrawalQuote>(
    `${base}/withdrawals/manual-quote`,
    data,
    options,
  );
export const createManualWithdrawal = (data: ComputeRecord) =>
  requestClient.post<ComputeRecord>(
    `${base}/withdrawals/manual`,
    data,
    options,
  );

export const fetchWheelMembers = (
  activityId: number,
  params: Record<string, unknown>,
) =>
  requestClient.get<ComputePage>(`${base}/activities/${activityId}/members`, {
    ...options,
    params,
  });
export const adjustWheelMember = (
  activityId: number,
  memberId: number,
  data: ComputeRecord,
) =>
  requestClient.post<ComputeRecord>(
    `${base}/activities/${activityId}/members/${memberId}`,
    data,
    options,
  );
export const resetWheelRound = (activityId: number) =>
  requestClient.post<ComputeRecord>(
    `${base}/activities/${activityId}/reset-round`,
    {},
    options,
  );
export const redeemWheelParticipation = (id: number, mode: 'AUTO' | 'MANUAL') =>
  requestClient.post<ComputeRecord>(
    `${base}/activity-participations/${id}/redeem`,
    { mode },
    options,
  );
export const fetchWheelStatistics = (params: ComputeQuery = {}) =>
  requestClient.get<ComputeRecord>(`${base}/activity-statistics`, {
    ...options,
    params,
  });
