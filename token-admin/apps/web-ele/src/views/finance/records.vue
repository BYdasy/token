<script setup lang="ts">
import {
  computed,
  onActivated,
  onDeactivated,
  onMounted,
  onUnmounted,
  reactive,
  ref,
  watch,
} from 'vue';
import Page from '#/components/business/BusinessListPage.vue';
import { useAccess } from '@vben/access';
import { useAccessStore } from '@vben/stores';
import { useI18n } from '@vben/locales';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDatePicker,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElPagination,
  ElRadioButton,
  ElRadioGroup,
  ElSelect,
  ElTableColumn,
  ElTag,
} from 'element-plus';
import { ElTable } from '#/adapter/table';
import type { ComputeRecord, DepositReconciliationResult, PageResource } from '#/api/compute';
import { setWithdrawalAttention } from '#/api/withdrawal-attention';
import { LEDGER_DETAIL_TYPES, LEDGER_FACT_FIELDS } from '#/constants/ledger';
import { cancelComputeUnsentPayout, directApproveComputeWithdrawal, reconcileComputePayment, reviewComputeFunds } from '#/api/compute';
import { P, useCompute } from '#/components/business/shared';
import { usePaged } from '#/components/business/usePaged';
import { useDictionaries } from '#/components/business/dictionaries';
import BusinessSearchFields from '#/components/business/BusinessSearchFields.vue';
import ManualFunds from './ManualFunds.vue';
import DepositHashRecheck from './DepositHashRecheck.vue';
import DepositInvestigation from './DepositInvestigation.vue';
import { depositInvestigationReason, depositInvestigationResult } from './deposit-investigation-result';
import { usePayoutInvestigation } from './usePayoutInvestigation';
import { nativeDepositAmount } from './native-deposit-amount';
import { depositCollectionPresentation, depositCollectionText } from './deposit-collection';
import { useFilterRecovery } from '#/components/business/useFilterRecovery';
import { currencyLabel, currencyOptionLabel } from '#/utils/currency';
import { memberCountryName } from '../user/memberPresentation';
const { c, can, enumLabel, date } = useCompute();
const { hasAccessByCodes, hasAccessByRoles } = useAccess();
const access = useAccessStore();
const { locale } = useI18n();
const canGatewayOperate = computed(() => hasAccessByCodes(['super']) || hasAccessByRoles(['super']));
const { dictionaries, loadDictionaries, options } = useDictionaries();
const props = withDefaults(
  defineProps<{ section?: 'deposits' | 'withdrawals' | 'orders' | 'ledger' }>(),
  { section: 'deposits' },
);
const resource = computed<PageResource>(() => props.section);
const collectionPresentation = (row: ComputeRecord) => depositCollectionPresentation(resource.value, row, c);
const {
  rows,
  query,
  pager,
  dates,
  loading,
  failed,
  load,
  search,
  reset,
  resize,
} = usePaged(resource, () => {
  const pagePermissions = {
    deposits: [P.depositRead, P.depositWrite],
    withdrawals: [P.withdrawalRead, P.withdrawalWrite],
    orders: [P.orderRead],
    ledger: [P.ledgerRead],
  }[props.section];
  return can(...pagePermissions);
});
const canReview = computed(() =>
  props.section === 'deposits'
    ? can(P.depositWrite)
    : props.section === 'withdrawals' &&
      can(P.withdrawalWrite),
);
const attentionSaving = ref('');
async function toggleAttention(row: ComputeRecord) {
  if (!canReview.value || resource.value !== 'withdrawals' || attentionSaving.value) return;
  const ignored = !row.attentionIgnored;
  try {
    await ElMessageBox.confirm(c(ignored ? 'v27.ignoreHelp' : 'v27.restoreHelp'), c(ignored ? 'v27.ignore' : 'v27.restore'), { type: 'warning' });
  } catch { return; }
  attentionSaving.value = String(row.id);
  try {
    await setWithdrawalAttention(Number(row.id), ignored);
    row.attentionIgnored = ignored;
    ElMessage.success(c('common.saved'));
    window.dispatchEvent(new Event('token-monitor-settings-changed'));
    window.dispatchEvent(new Event('token-finance-pulse'));
    await load();
  } finally { attentionSaving.value = ''; }
}
const canDirectApprove = computed(() => props.section === 'withdrawals' && can(P.withdrawalDirectApprove));
const globalGateway = computed(() => Boolean(dictionaries.value.gatewayEnabled && dictionaries.value.scope?.global));
const canGatewayStatus = computed(() => resource.value === 'withdrawals' && globalGateway.value && can(P.withdrawalRead));
const canDepositStatus = computed(() => resource.value === 'deposits' && globalGateway.value && can(P.depositRead));
const depositInvestigationOpen = ref(false);
const depositVerificationResult = ref<DepositReconciliationResult | null>(null);
const depositOutcome = computed(() => depositVerificationResult.value ? depositInvestigationResult(depositVerificationResult.value) : null);
const canDepositRecheck = computed(() => resource.value === 'deposits' && globalGateway.value && canGatewayOperate.value);
const depositRecheckOpen = ref(false);
const { open: gatewayOpen, loading: gatewayLoading, failed: gatewayFailed, rows: gatewayRows, pager: gatewayPager, load: loadGateway, show: openGateway, changePage: changeGatewayPage, changeSize: changeGatewaySize } = usePayoutInvestigation(() => canGatewayStatus.value);
useFilterRecovery('finance:payout-investigation', { gatewayPager });
const reconciling = ref('');
const cancelling = ref('');
function investigationReference(row: ComputeRecord) {
  return String(row.gatewayReference || (resource.value === 'withdrawals' ? row.localOrderNo || row.orderNo : '') || '');
}
async function reconcile(row: ComputeRecord) {
  const reference = investigationReference(row);
  if (!reference || reconciling.value || cancelling.value || !globalGateway.value || !canGatewayOperate.value) return;
  openGatewayVerification('reconcile', row);
}
function cancellable(row: ComputeRecord) {
  return resource.value === 'withdrawals' && globalGateway.value && canGatewayOperate.value && ['REVIEW', 'RETRY_WAIT'].includes(row.state) && ['NOT_SENT', 'REJECTED'].includes(row.submissionState);
}
async function cancelUnsent(row: ComputeRecord) {
  const reference = investigationReference(row);
  if (!reference || !cancellable(row) || cancelling.value || reconciling.value) return;
  openGatewayVerification('cancel', row);
}
const gatewayVerificationOpen = ref(false);
const gatewayVerification = reactive({ kind: 'reconcile', reference: '', username: '', orderNo: '', resource: '', remark: '', code: '' });
let verificationVersion = 0;
watch(gatewayVerificationOpen, value => {
  if (!value) { verificationVersion++; gatewayVerification.code = ''; cancelling.value = ''; reconciling.value = ''; }
}, { flush: 'sync' });
watch([resource, () => access.accessToken, locale, canGatewayOperate, globalGateway], () => {
  verificationVersion++; gatewayVerificationOpen.value = false; gatewayVerification.code = '';
  cancelling.value = ''; reconciling.value = ''; depositVerificationResult.value = null;
}, { flush: 'sync' });
function openGatewayVerification(kind: string, row: ComputeRecord) {
  Object.assign(gatewayVerification, { kind, reference: investigationReference(row), username: row.username || '', orderNo: row.localOrderNo || row.orderNo || '', resource: resource.value, remark: '', code: '' });
  gatewayVerificationOpen.value = true;
}
async function submitGatewayVerification() {
  if (!gatewayVerificationOpen.value || !access.accessToken || !canGatewayOperate.value || !globalGateway.value || gatewayVerification.resource !== resource.value || reconciling.value || cancelling.value) return;
  if (!gatewayVerification.remark.trim() || gatewayVerification.remark.length > 500) { ElMessage.warning(c('v11.payment.cancelReasonRequired')); return; }
  if (!/^\d{6}$/.test(gatewayVerification.code)) { ElMessage.warning(c('merchant.totpRequired')); return; }
  const target = resource.value === 'withdrawals' ? 'PAYOUT' : 'DEPOSIT';
  const reference = gatewayVerification.reference;
  const isCancel = gatewayVerification.kind === 'cancel';
  const own = ++verificationVersion;
  depositVerificationResult.value = null;
  if (isCancel) cancelling.value = reference; else reconciling.value = reference;
  try {
    const response = isCancel
      ? await cancelComputeUnsentPayout(reference, gatewayVerification.remark.trim(), gatewayVerification.code)
      : await reconcileComputePayment(target, reference, gatewayVerification.remark.trim(), gatewayVerification.code);
    if (own !== verificationVersion) return;
    if (target === 'DEPOSIT') depositVerificationResult.value = response;
    gatewayVerificationOpen.value = false;
    if (target === 'PAYOUT') ElMessage.success(c(isCancel ? 'v11.payment.cancelled' : 'v11.payment.checked'));
    await load();
    if (gatewayOpen.value) await loadGateway();
  } finally { if (own === verificationVersion) { gatewayVerification.code = ''; cancelling.value = ''; reconciling.value = ''; } }
}
const reviewOpen = ref(false),
  saving = ref(false),
  detailOpen = ref(false);
const manualOpen = ref(false);
const defaultCurrency = computed(
  () =>
    dictionaries.value.defaultCurrency ||
    dictionaries.value.summaryCurrency ||
    '',
);
const selected = ref<ComputeRecord>({});
const review = reactive({ action: 'approve', remark: '' });
const reviewResource = ref<'deposits' | 'withdrawals'>('deposits');
const isReviewable = computed(
  () => resource.value === 'deposits' || resource.value === 'withdrawals',
);
const statusOptions = computed(() =>
  isReviewable.value
    ? ['PENDING', ...(resource.value === 'withdrawals' ? ['PROCESSING'] : []), 'APPROVED', 'REJECTED']
    : resource.value === 'orders'
      ? ['ACTIVE', 'COMPLETED']
      : [],
);
const columns = computed(() =>
  resource.value === 'deposits'
    ? ['walletType', 'currency', 'network', 'amount', 'status', 'createdAt']
    : resource.value === 'withdrawals'
      ? [
          'currency',
          'network',
          'amount',
          'fee',
          'netAmount',
          'address',
          'status',
          'createdAt',
        ]
      : resource.value === 'orders'
        ? [
            'productName',
            'currency',
            'amount',
            'dailyRate',
            'settlementMode',
            'paidIncome',
            'status',
            'maturesAt',
            'createdAt',
          ]
        : [
            'walletType',
            'currency',
            'detailType',
            'businessAmount',
            'amount',
            'frozenChange',
            'availableBefore',
            'frozenBefore',
            'availableAfter',
            'frozenAfter',
            'operatorUsername',
            'remark',
            'createdAt',
          ],
);
const allColumns = computed(() => [
  ...(resource.value === 'ledger' ? ['businessOrderNo', 'ledgerNo'] : ['orderNo']),
  'username',
  ...columns.value,
  'groupName',
  'inviterName',
  'topName',
  'isTest',
  ...(isReviewable.value
    ? [
        'recordSource',
        'createdByUsername',
        'creationRemark',
        'completionMode',
        'remark',
        'reviewedByUsername',
        'reviewedAt',
      ]
    : []),
  ...(resource.value === 'deposits' ? ['firstDeposit'] : []),
]);
const searchFields = computed(() => [
  {
    key: 'currency',
    options: [
      ...new Set(
        (dictionaries.value.currencies || []).map((row) =>
          String(row.currency),
        ),
      ),
    ].map((value) => ({ value, label: currencyOptionLabel(value) })),
  },
  { key: 'orderNo', labelKey: resource.value === 'ledger' ? 'v44Finance.ledgerNo' : 'v6.orderNo' },
  ...(resource.value === 'withdrawals' ? [
    { key: 'attentionIgnored', labelKey: 'v27.attention', options: [{ value: 'false', label: c('v27.attending') }, { value: 'true', label: c('v27.ignored') }] },
    { key: 'gatewayReference', labelKey: 'v11.payment.gatewayReference' },
    { key: 'receiverAddress', labelKey: 'v8.finance.receiverAddress' },
  ] : []),
  ...(isReviewable.value ? [{ key: 'txHash', labelKey: 'v8.finance.txHash' }] : []),
  ...(resource.value === 'orders'
    ? [{ key: 'productId', options: options('products') }]
    : []),
  ...(resource.value === 'deposits'
    ? [
        {
          key: 'collectionState',
          labelKey: 'v51Notifications.collection.status',
          options: [
            { value: 'COLLECTED', label: c('v51Notifications.collection.collected') },
            { value: 'NOT_COLLECTED', label: c('v51Notifications.collection.notCollected') },
          ],
        },
        {
          key: 'firstDeposit',
          labelKey: 'v6.firstDeposit',
          options: [
            { value: 'true', label: c('common.yes') },
            { value: 'false', label: c('common.no') },
          ],
        },
      ]
    : []),
  ...(resource.value === 'ledger'
    ? [
        {
          key: 'detailType',
          labelKey: 'v35Ledger.detailType',
          options: LEDGER_DETAIL_TYPES.map((value) => ({ value, label: c('v35Ledger.type.' + value) })),
        },
        {
          key: 'direction',
          labelKey: 'v6.direction',
          options: ['IN', 'OUT', 'FROZEN'].map((value) => ({
            value,
            label: c('v6.direction' + value),
          })),
        },
      ]
    : []),
  ...(['deposits', 'ledger'].includes(resource.value)
    ? [
        {
          key: 'walletType',
          options: ['COMPUTE', 'PROJECT', 'EARNINGS'].map((value) => ({
            value,
            label: enumLabel(value),
          })),
        },
      ]
    : []),
  ...(isReviewable.value
    ? [
        {
          key: 'network',
          options: [
            ...new Set(
              (dictionaries.value.currencies || []).map((row) =>
                String(row.network),
              ),
            ),
          ].map((value) => ({ label: value, value })),
        },
      ]
    : []),
  { key: 'minAmount', ...(resource.value === 'ledger' ? { labelKey: 'v35Ledger.minBusinessAmount' } : {}) },
  { key: 'maxAmount', ...(resource.value === 'ledger' ? { labelKey: 'v35Ledger.maxBusinessAmount' } : {}) },
  { key: 'inviterName', labelKey: 'v5.directSuperior' },
  { key: 'topName', labelKey: 'v5.topAgent' },
  {
    key: 'isTest',
    labelKey: 'v5.memberType',
    options: [
      { value: 'false', label: c('v5.regularMember') },
      { value: 'true', label: c('v5.testMember') },
    ],
  },
]);
const details = [
  'businessOrderNo',
  'ledgerNo',
  'orderNo',
  'gatewayReference',
  'gatewayState',
  'gatewayReason',
  'gatewayAttempts',
  'gatewayNextAttemptAt',
  'paymentStatus',
  'custodyStatus',
  'uid',
  'username',
  'walletType',
  'sourceWallet',
  'productName',
  'productType',
  'currency',
  'network',
  'amount',
  'accountingRate',
  'accountingAmount',
  'accountingFee',
  'accountingNetAmount',
  'dailyRate',
  'durationDays',
  'settlementMode',
  'expectedIncome',
  'paidIncome',
  'fee',
  'netAmount',
  'address',
  'senderAddress',
  'receiverAddress',
  'txHash',
  'chainStatus',
  'chainConfirmations',
  'chainUpdatedAt',
  'kind',
  'detailType',
  'businessAmount',
  'frozenChange',
  'availableBefore',
  'frozenBefore',
  'availableAfter',
  'frozenAfter',
  'operatorUsername',
  'recordSource',
  'createdByUsername',
  'creationRemark',
  'completionMode',
  'isTest',
  'status',
  'remark',
  'reviewedByUsername',
  'reviewedAt',
  'postedAt',
  'confirmedAt',
  'broadcastAt',
  'startsAt',
  'maturesAt',
  'createdAt',
];
function fieldLabel(key: string) {
  if (['businessOrderNo','ledgerNo'].includes(key)) return c('v44Finance.' + key);
  if (resource.value === 'ledger' && key === 'orderNo') return c('v44Finance.ledgerNo');
  if (['detailType', 'businessAmount'].includes(key)) return c('v35Ledger.' + key);
  if (resource.value === 'ledger' && key === 'amount') return c('v35Ledger.availableChange');
  if (key === 'postedAt') return c(resource.value === 'deposits' ? 'v35Payment.depositPostedAt' : 'v35Payment.withdrawalPostedAt');
  if (['confirmedAt', 'broadcastAt'].includes(key)) return c('v35Payment.' + key);
  if (['gatewayState', 'gatewayReason', 'gatewayAttempts', 'gatewayNextAttemptAt'].includes(key)) return c('v26Payout.' + key);
  if (key === 'uid') return 'UID';
  if (resource.value === 'withdrawals' && ['amount','accountingAmount','accountingFee','accountingNetAmount'].includes(key)) return c(key === 'accountingFee' ? 'v44Finance.withdrawalFee' : key === 'accountingNetAmount' ? 'v44Finance.withdrawalNet' : 'v44Finance.withdrawalGross');
  if (isReviewable.value && key === 'amount') return c('v22Accounting.originalAmount');
  const accountingLabels: Record<string, string> = { accountingRate: 'rate', accountingAmount: 'actualAmount', accountingFee: 'feeAmount', accountingNetAmount: 'netAmount' };
  if (accountingLabels[key]) return c('v22Accounting.' + accountingLabels[key]);
  if (['gatewayReference', 'paymentStatus', 'custodyStatus'].includes(key)) return c('v11.payment.' + key);
  if (['senderAddress', 'receiverAddress', 'txHash', 'chainStatus', 'chainConfirmations', 'chainUpdatedAt'].includes(key))
    return c('v8.finance.' + key);
  if (
    ['operatorUsername', 'createdByUsername', 'reviewedByUsername'].includes(
      key,
    )
  )
    return c('v7.' + key);
  if (['orderNo', 'firstDeposit', 'dailyRate'].includes(key))
    return c('v6.' + key);
  return c(
    key === 'inviterName'
      ? 'v5.directSuperior'
      : key === 'topName'
        ? 'v5.topAgent'
        : key === 'isTest'
          ? 'v5.memberType'
          : [
                'recordSource',
                'createdBy',
                'creationRemark',
                'completionMode',
              ].includes(key)
            ? `v5.${key}`
            : `field.${key}`,
  );
}
function accountingDisplay(row: ComputeRecord, key: string) {
  if (key === 'accountingAmount' && resource.value === 'deposits' && row.status !== 'APPROVED' && row.accountingStatus !== 'POSTED')
    return c('v22Accounting.notPosted');
  if (row.accountingCurrency !== 'USD' || row[key] == null) return c('v22Accounting.unpriced');
  return key === 'accountingRate' ? `1 ${currencyLabel(row.currency)} = ${row[key]} USD` : `${row[key]} USD`;
}
function display(key: string, value: unknown) {
  if (key === 'detailType') return c('v35Ledger.type.' + value);
  if (key === 'gatewayState') return payoutStage(value);
  if (key === 'gatewayReason') return value ? investigationReason(value) : '—';
  if (key === 'currency') return currencyLabel(value) || '—';
  if (key === 'chainStatus') return chainLabel(value);
  if (key === 'custodyStatus') return custodyLabel(value);
  if (key === 'completionMode' && value === 'MCAG_PENDING') return paymentLabel('MCAG_QUEUED');
  if (['paymentStatus', 'status', 'recordSource', 'completionMode'].includes(key)) return paymentLabel(value);
  if (
    key === 'username' ||
    key.endsWith('Username') ||
    ['inviterName', 'topName'].includes(key)
  )
    return value || '—';
  if (key === 'firstDeposit') return c(value ? 'common.yes' : 'common.no');
  if (key === 'isTest') return c(value ? 'v5.testMember' : 'v5.regularMember');
  if (
    ['ADMIN_MANUAL', 'TEST_COMPLETED', 'MANUAL_CONFIRMED'].includes(
      String(value),
    )
  )
    return c(`v5.${value}`);
  return key.endsWith('At') ? date(value) : enumLabel(value);
}
function chainLabel(value: unknown) {
  const status = String(value || 'NOT_APPLICABLE');
  if (['ORPHANED', 'CONFIRMING', 'PENDING'].includes(status)) return c('v11.payment.chain.' + status);
  return ['NOT_APPLICABLE', 'WAITING', 'BROADCAST', 'CONFIRMED', 'FAILED'].includes(status)
    ? c('v8.finance.chain.' + status)
    : c('v8.finance.chain.UNKNOWN');
}
function paymentLabel(value: unknown) {
  const state = String(value || '');
  if (state === 'DIRECT_CONFIRMED') return c('v24Finance.directConfirmed');
  if (state === 'MCAG_DISABLED_ADDRESS') return c('v18.deposit.disabledAddress');
  if (['ADMIN_MANUAL', 'MANUAL_CONFIRMED', 'TEST_COMPLETED'].includes(state)) return c('v5.' + state);
  const completion: Record<string, string> = { MCAG_COMPLETED: 'MCAG_SUCCEEDED', MCAG_RELEASED: 'MCAG_FAILED' };
  const translated = completion[state] || state;
  return ['PROCESSING', 'MCAG', 'MCAG_PENDING', 'MCAG_HELD', 'MCAG_REVIEW', 'MCAG_QUEUED', 'MCAG_SUCCEEDED', 'MCAG_FAILED', 'MCAG_VERIFIED', 'MCAG_REVERSED', 'MANUAL_REVIEW', 'MANUAL_PENDING', 'MANUAL_REJECTED'].includes(translated)
    ? c('v11.payment.state.' + translated) : enumLabel(value);
}
function requiresInvestigation(row: ComputeRecord) {
  return ['MCAG_REVERSED', 'MCAG_REVIEW', 'MCAG_HELD', 'MCAG_DISABLED_ADDRESS'].includes(String(row.paymentStatus)) || row.chainStatus === 'ORPHANED'
    || (resource.value === 'withdrawals' && row.status === 'PROCESSING' && row.gatewayState !== 'RETRY_WAIT' && Boolean(row.gatewayReason));
}
function payoutStage(value: unknown) {
  const stage = String(value || '');
  return ['NEW', 'QUOTED', 'CREATING', 'SUBMITTED', 'RETRY_WAIT', 'REVIEW', 'SUCCEEDED', 'RELEASED'].includes(stage)
    ? c('v26Payout.stage.' + stage) : '—';
}
function investigationReason(value: unknown) {
  const reason = String(value || 'UNKNOWN');
  const depositReason = depositInvestigationReason(reason, c);
  if (depositReason) return depositReason;
  if (['GATEWAY_TIMEOUT', 'GATEWAY_ACCESS_DENIED', 'GATEWAY_RATE_LIMITED', 'GATEWAY_INVALID_RESPONSE',
    'MERCHANT_POOL_UNAVAILABLE', 'CUSTODY_PAYOUT_DISABLED'].includes(reason)) return c('v26Payout.reason.' + reason);
  if (reason === 'DISABLED_ADDRESS') return c('v18.deposit.disabledAddress');
  return ['UNBOUND_ADDRESS', 'CHAIN_PENDING', 'NOT_CUSTOMER_DEPOSIT', 'TEST_MEMBER_NOT_ALLOWED', 'CHAIN_REVERSED', 'COLLECTION_FAILED', 'COLLECTION_REVIEW', 'RISK_HELD', 'AMOUNT_PRECISION', 'RELEASE_NOT_CONFIRMED', 'UNKNOWN',
    'GATEWAY_UNAVAILABLE', 'SYNC_FAILED', 'CHANNEL_UNAVAILABLE', 'REMOTE_ORDER_MISSING', 'TEST_MEMBER_CHANGED', 'CREATE_REJECTED', 'QUOTE_REFRESH_REQUIRED', 'LOCAL_CANCELLED',
    'ADMIN_REJECTED', 'INSUFFICIENT_FUNDS', 'INSUFFICIENT_CHAIN_BALANCE', 'INSUFFICIENT_NATIVE_GAS', 'CHAIN_EXPIRED_UNBROADCAST', 'CHAIN_REVERTED', 'CHAIN_FAILED', 'BROADCAST_HASH_MISMATCH', 'BROADCAST_IN_PROGRESS', 'SIGNING_LEASE_EXPIRED'].includes(reason)
    ? c('v11.payment.reason.' + reason) : `${c('v11.payment.reason.UNKNOWN')} (${reason})`;
}
function custodyLabel(value: unknown) {
  const state = String(value || '');
  return ['AWAITING_APPROVAL', 'READY', 'SIGNING', 'SIGNED', 'UNKNOWN', 'CONFIRMING', 'SUCCEEDED', 'FAILED', 'REJECTED', 'REVIEW'].includes(state)
    ? c('v11.payment.custody.' + state) : c('v11.payment.reason.UNKNOWN');
}
const gatewayFeeRows = computed(() => {
  const fees = selected.value.gatewayFees;
  if (resource.value !== 'withdrawals' || !fees || typeof fees !== 'object') return [];
  return ['maxFeeCharge', 'platformFee', 'networkFeeChargeCap', 'actualFeeNative']
    .filter(key => fees[key] != null && fees[key] !== '')
    .map(key => ({ key, value: fees[key], asset: fees[key === 'actualFeeNative' ? 'networkFeeAssetId' : 'feeAssetId'] || '—' }));
});
const gatewayReviewRows = computed(() => Object.entries(selected.value.gatewayReview || {})
  .filter(([, value]) => value != null && value !== '')
  .map(([key, value]) => ({ key, value: key.endsWith('At') ? date(value) : value })));
const gatewayChainRows = computed(() => ['requiredConfirmations', 'finalitySatisfied', 'confirmationCheckedAt', 'observedBlockHash', 'broadcastAt', 'confirmedAt', 'completedAt']
  .filter(key => selected.value[key] != null && selected.value[key] !== '')
  .map(key => ({ key, value: key.endsWith('At') ? date(selected.value[key]) : typeof selected.value[key] === 'boolean' ? c(selected.value[key] ? 'merchant.yes' : 'merchant.no') : selected.value[key] })));
function reviewable(row: ComputeRecord) {
  return row.status === 'PENDING' && !(resource.value === 'deposits' && row.recordSource === 'MCAG');
}
function directApprovable(row: ComputeRecord) {
  return resource.value === 'withdrawals' && row.status === 'PENDING'
    && !row.completionMode && !row.externalOrderNo && !row.txHash && !row.senderAddress
    && ['', 'MANUAL_PENDING', 'MANUAL_REVIEW'].includes(String(row.paymentStatus || ''))
    && ['', 'NOT_APPLICABLE'].includes(String(row.chainStatus || ''));
}
async function copyValue(value: unknown) {
  if (!value) return;
  try {
    await navigator.clipboard.writeText(String(value));
    ElMessage.success(c('v8.finance.copied'));
  } catch {
    ElMessage.warning(c('v8.finance.copyUnavailable'));
  }
}
const recipientDetails = computed(() => {
  try {
    const raw = selected.value.addressSnapshot;
    const snapshot = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (!snapshot || typeof snapshot !== 'object' || Array.isArray(snapshot))
      return [];
    const rows: { label: string; value: string }[] = [];
    if (snapshot.memo)
      rows.push({ label: c('field.memo'), value: String(snapshot.memo) });
    if (
      snapshot.fields &&
      typeof snapshot.fields === 'object' &&
      !Array.isArray(snapshot.fields)
    ) {
      for (const [key, value] of Object.entries(snapshot.fields)) {
        if (value != null && typeof value !== 'object')
          rows.push({
            label: c(`currency.addressField.${key}`),
            value: String(value),
          });
      }
    }
    return rows;
  } catch {
    return [];
  }
});
function openReview(row: ComputeRecord) {
  selected.value = row;
  reviewResource.value = resource.value as 'deposits' | 'withdrawals';
  Object.assign(review, { action: canReview.value ? 'approve' : 'direct', remark: '' });
  reviewOpen.value = true;
}
async function submitReview() {
  const direct = review.action === 'direct';
  if (saving.value || !reviewable(selected.value) || reviewResource.value !== resource.value
    || (direct ? !canDirectApprove.value || !directApprovable(selected.value) : !canReview.value))
    return;
  if ((direct || review.action === 'reject') && !review.remark.trim())
    return void ElMessage.warning(c(direct ? 'v24Finance.directRemarkRequired' : 'validation.rejectRemark'));
  saving.value = true;
  try {
    if (direct) await directApproveComputeWithdrawal(Number(selected.value.id), review.remark.trim());
    else await reviewComputeFunds(reviewResource.value, Number(selected.value.id), {
      approved: review.action === 'approve', remark: review.remark.trim(),
    });
    ElMessage.success(c('common.reviewed'));
    reviewOpen.value = false;
    await load();
  } finally {
    saving.value = false;
  }
}
watch(resource, () => {
  manualOpen.value = false;
  reviewOpen.value = false;
  gatewayOpen.value = false;
  gatewayVerificationOpen.value = false;
  gatewayVerification.code = '';
  depositRecheckOpen.value = false;
  depositInvestigationOpen.value = false;
  void reset();
});
onMounted(() => Promise.all([load(), loadDictionaries()]));
const refreshSeconds = ref(0);
let refreshTimer: ReturnType<typeof setInterval> | undefined;
let pageActive = true;
function stopRefresh() {
  if (refreshTimer) clearInterval(refreshTimer);
  refreshTimer = undefined;
}
function startRefresh() {
  stopRefresh();
  const seconds = refreshSeconds.value;
  refreshTimer =
    seconds && pageActive && isReviewable.value
      ? setInterval(() => {
          if (
            pageActive &&
            !document.hidden &&
            !loading.value &&
            !failed.value &&
            !saving.value &&
            !manualOpen.value &&
            !gatewayVerificationOpen.value &&
            !depositInvestigationOpen.value &&
            !reviewOpen.value
          )
            void load({}, true);
        }, seconds * 1000)
      : undefined;
}
watch([refreshSeconds, isReviewable], startRefresh);
onActivated(() => {
  pageActive = true;
  startRefresh();
});
onDeactivated(() => {
  pageActive = false;
  gatewayOpen.value = false;
  depositInvestigationOpen.value = false;
  gatewayVerificationOpen.value = false;
  gatewayVerification.code = '';
  stopRefresh();
});
onUnmounted(() => { verificationVersion++; stopRefresh(); });
</script>
<template>
  <Page>
    <div class="compute-page">
      <ElAlert
        v-if="isReviewable"
        :title="c('v11.payment.financeHelp')"
        type="info"
        :closable="false"
      />
      <ElCard shadow="never">
        <div class="compute-toolbar">
          <ElDatePicker
            v-model="dates"
            type="datetimerange"
            class="compute-range"
            :start-placeholder="c('common.rangeStart')"
            :end-placeholder="c('field.endsAt')"
            value-format="YYYY-MM-DDTHH:mm:ss"
          />
          <ElInput
            v-model="query.username"
            class="compute-filter"
            clearable
            :placeholder="c('field.username')"
            @keyup.enter="search"
          />
          <ElSelect
            v-if="statusOptions.length"
            v-model="query.status"
            class="compute-filter"
            clearable
            :placeholder="c('field.status')"
          >
            <ElOption
              v-for="value in statusOptions"
              :key="value"
              :value="value"
              :label="paymentLabel(value)"
            />
          </ElSelect>
          <ElSelect
            v-model="query.groupId"
            class="compute-filter"
            clearable
            :placeholder="c('field.groupId')"
          >
            <ElOption
              v-for="option in options('groups')"
              :key="option.value"
              v-bind="option"
            />
          </ElSelect>
          <ElInput
            v-model="query.teamUsername"
            class="compute-filter"
            clearable
            :placeholder="c('v6.teamUsername')"
            @keyup.enter="search"
          />
          <BusinessSearchFields
            :model="query"
            :fields="searchFields"
            @search="search"
          />
          <ElButton :loading="loading" type="primary" @click="search">
            {{ c('common.search') }}
          </ElButton>
          <ElButton @click="reset">{{ c('common.reset') }}</ElButton>
          <ElButton
            v-if="canReview && isReviewable"
            type="primary"
            :disabled="!defaultCurrency"
            @click="manualOpen = true"
            >{{
              c(
                resource === 'deposits'
                  ? 'v5.manualDeposit'
                  : 'v5.manualWithdrawal',
              )
            }}</ElButton
          >
          <ElSelect
            v-if="isReviewable"
            v-model="refreshSeconds"
            class="compute-filter"
            :placeholder="c('v6.autoRefresh')"
          >
            <ElOption :value="0" :label="c('v6.refreshOff')" /><ElOption
              v-for="seconds in [10, 30, 60]"
              :key="seconds"
              :value="seconds"
              :label="
                c('v6.refreshEvery') + ' ' + seconds + ' ' + c('v6.seconds')
              "
            />
          </ElSelect>
          <ElButton v-if="canGatewayStatus" @click="openGateway">{{ c('v11.payment.investigation') }}</ElButton>
          <ElButton v-if="canDepositStatus" @click="depositInvestigationOpen = true">{{ c('v48DepositReview.title') }}</ElButton>
          <ElButton v-if="canDepositRecheck" @click="depositRecheckOpen = true">{{ c('v47Recheck.title') }}</ElButton>
        </div>
      </ElCard>
      <ElCard class="compute-list-card" shadow="never">
        <ElAlert v-if="depositVerificationResult && depositOutcome" :closable="false" :type="depositOutcome.type" :title="c('v48DepositReview.result.' + depositOutcome.key)" :description="[depositVerificationResult.localOrderNo, depositVerificationResult.reason ? investigationReason(depositVerificationResult.reason) : ''].filter(Boolean).join(' · ')" class="mb-4" />
        <ElAlert
          v-if="failed"
          type="error"
          :closable="false"
          :title="c('common.loadFailed')"
          class="mb-4"
        />
        <ElTable height="100%"
          v-loading="loading"
          :data="rows"
          stripe
          :empty-text="c('common.empty')"
        >
          <template v-if="isReviewable">
            <ElTableColumn :label="c('v8.finance.businessInfo')" min-width="218" fixed="left">
              <template #default="{ row }">
                <div class="finance-stack">
                  <ElButton link type="primary" class="finance-number" :title="c('v8.finance.copyNumber')" @click="copyValue(row.orderNo)">{{ row.orderNo || '—' }}</ElButton>
                  <span>{{ date(row.createdAt) }}</span>
                  <span>{{ display('recordSource', row.recordSource) }}</span>
                  <span v-if="row.gatewayReference">{{ c('v11.payment.gatewayReference') }}: <ElButton link type="primary" class="finance-number" @click="copyValue(row.gatewayReference)">{{ row.gatewayReference }}</ElButton></span>
                  <ElTag v-if="resource === 'deposits' && row.firstDeposit" size="small" type="success">{{ c('v6.firstDeposit') }}</ElTag>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn :label="c('v8.finance.userInfo')" min-width="180">
              <template #default="{ row }">
                <div class="finance-stack">
                  <strong>{{ row.username || '—' }}</strong>
                  <span>UID: {{ row.uid || '—' }}</span>
                  <span>{{ c('v8.lastLoginCountry') }}: {{ memberCountryName(row.lastLoginCountry) }}</span>
                  <span>{{ row.groupName || '—' }} · {{ display('isTest', row.isTest) }}</span>
                  <span>{{ c('v5.directSuperior') }}: {{ row.inviterName || '—' }}</span>
                  <span>{{ c('v5.topAgent') }}: {{ row.topName || '—' }}</span>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn :label="c('v8.finance.channelInfo')" :min-width="resource === 'withdrawals' ? 210 : 145">
              <template #default="{ row }">
                <div class="finance-stack">
                  <strong>{{ currencyLabel(row.currency) }} / {{ row.network }}</strong>
                  <span>{{ enumLabel(row.walletType) }}</span>
                  <template v-if="resource === 'withdrawals'">
                    <span>
                      {{ c('field.totalDeposits') }}: {{ row.historyTotals?.totalDeposits ?? '—' }} {{ row.historyTotals?.currency || 'USD' }}
                      <small v-if="Number(row.historyTotals?.depositUnpricedCount) > 0" class="text-warning"> · {{ c('v22Accounting.unpriced') }}: {{ row.historyTotals.depositUnpricedCount }}</small>
                    </span>
                    <span>
                      {{ c('field.totalWithdrawals') }}: {{ row.historyTotals?.totalWithdrawals ?? '—' }} {{ row.historyTotals?.currency || 'USD' }}
                      <small v-if="Number(row.historyTotals?.withdrawalUnpricedCount) > 0" class="text-warning"> · {{ c('v22Accounting.unpriced') }}: {{ row.historyTotals.withdrawalUnpricedCount }}</small>
                    </span>
                  </template>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn :label="c('v8.finance.amountInfo')" min-width="220">
              <template #default="{ row }">
                <div class="finance-stack">
                  <template v-if="resource === 'withdrawals'">
                    <strong>{{ c('v44Finance.withdrawalNet') }}: {{ accountingDisplay(row, 'accountingNetAmount') }}</strong>
                    <span>{{ c('field.netAmount') }}: {{ row.netAmount }} {{ currencyLabel(row.currency) }}</span>
                    <span>{{ c('v44Finance.withdrawalFee') }}: {{ accountingDisplay(row, 'accountingFee') }} · {{ row.fee }} {{ currencyLabel(row.currency) }}</span>
                    <span>{{ c('v44Finance.withdrawalGross') }}: {{ accountingDisplay(row, 'accountingAmount') }} · {{ row.amount }} {{ currencyLabel(row.currency) }}</span>
                    <span>{{ c('v22Accounting.rate') }}: {{ accountingDisplay(row, 'accountingRate') }}</span>
                  </template>
                  <template v-else>
                    <strong>{{ c('v22Accounting.originalAmount') }}: {{ nativeDepositAmount(resource, row) }} {{ currencyLabel(row.currency) }}</strong>
                    <span>{{ c('v22Accounting.rate') }}: {{ accountingDisplay(row, 'accountingRate') }}</span>
                    <span>{{ c('v22Accounting.actualAmount') }}: {{ accountingDisplay(row, 'accountingAmount') }}</span>
                  </template>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn :label="c('v8.finance.chainInfo')" min-width="275">
              <template #default="{ row }">
                <div class="finance-stack">
                  <span>{{ c('v8.finance.senderAddress') }}: <span class="finance-chain-value" :title="row.senderAddress">{{ row.senderAddress || '—' }}</span><ElButton v-if="row.senderAddress" link type="primary" @click="copyValue(row.senderAddress)">{{ c('v8.finance.copy') }}</ElButton></span>
                  <span>{{ c('v8.finance.receiverAddress') }}: <span class="finance-chain-value" :title="row.receiverAddress">{{ row.receiverAddress || '—' }}</span><ElButton v-if="row.receiverAddress" link type="primary" @click="copyValue(row.receiverAddress)">{{ c('v8.finance.copy') }}</ElButton></span>
                  <span>{{ c('v8.finance.txHash') }}: <a v-if="row.transactionUrl" class="finance-chain-value transaction-link" :href="row.transactionUrl" target="_blank" rel="noopener noreferrer" :title="row.txHash">{{ row.txHash }}</a><span v-else class="finance-chain-value" :title="row.txHash">{{ row.txHash || '—' }}</span><ElButton v-if="row.txHash" link type="primary" @click="copyValue(row.txHash)">{{ c('v8.finance.copy') }}</ElButton></span>
                  <span>{{ chainLabel(row.chainStatus) }}<template v-if="row.chainConfirmations != null"> · {{ c('v8.finance.chainConfirmations') }}: {{ row.chainConfirmations }}</template></span>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn :label="c('v8.finance.reviewInfo')" :min-width="resource === 'deposits' ? 260 : 190">
              <template #default="{ row }">
                <div class="finance-stack">
                  <div class="finance-review-tags" :class="{ 'finance-review-tags--vertical': resource === 'withdrawals' }">
                  <ElTag :type="requiresInvestigation(row) || ['REJECTED', 'FAILED'].includes(row.status) ? 'danger' : ['PENDING', 'PROCESSING'].includes(row.status) ? 'warning' : 'success'">{{ paymentLabel(row.status) }}</ElTag>
                  <ElTag v-if="resource === 'withdrawals' && row.attentionIgnored" type="info">{{ c('v27.ignored') }}</ElTag>
                  <ElTag v-if="row.paymentStatus" :type="requiresInvestigation(row) ? 'danger' : 'info'">{{ paymentLabel(row.paymentStatus) }}</ElTag>
                  <ElTag v-if="collectionPresentation(row)" :type="collectionPresentation(row)?.type">{{ collectionPresentation(row)?.label }}</ElTag>
                  <ElTag v-if="collectionPresentation(row) && row.collectionReason" class="finance-collection-reason-tag" :type="collectionPresentation(row)?.type">{{ collectionPresentation(row)?.reason }}</ElTag>
                  </div>
                  <template v-if="resource === 'withdrawals' && row.gatewayState">
                    <span>{{ c('v26Payout.gatewayState') }}: {{ payoutStage(row.gatewayState) }}</span>
                    <span v-if="row.gatewayReason" :class="row.gatewayState === 'RETRY_WAIT' ? 'text-warning' : 'text-danger'">{{ investigationReason(row.gatewayReason) }}</span>
                    <span v-if="row.status === 'PROCESSING' && row.gatewayNextAttemptAt">{{ c('v26Payout.gatewayNextAttemptAt') }}: {{ date(row.gatewayNextAttemptAt) }}</span>
                  </template>
                  <span v-if="row.custodyStatus">{{ c('v11.payment.custodyStatus') }}: {{ custodyLabel(row.custodyStatus) }}</span>
                  <span v-if="row.completionMode">{{ display('completionMode', row.completionMode) }}</span>
                  <span>{{ c('v7.createdByUsername') }}: {{ row.createdByUsername || '—' }}</span>
                  <span>{{ c('v7.reviewedByUsername') }}: {{ row.reviewedByUsername || '—' }}</span>
                  <span>{{ date(row.reviewedAt) }}</span>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn :label="c('v35Payment.timeline')" min-width="220">
              <template #default="{ row }">
                <div class="finance-stack">
                  <span>{{ c('v35Payment.createdAt') }}: {{ date(row.createdAt) }}</span>
                  <span>{{ fieldLabel('postedAt') }}: {{ date(row.postedAt) }}</span>
                  <span>{{ c('v35Payment.confirmedAt') }}: {{ date(row.confirmedAt) }}</span>
                  <span v-if="resource === 'withdrawals' && row.broadcastAt">{{ c('v35Payment.broadcastAt') }}: {{ date(row.broadcastAt) }}</span>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn :label="c('field.remark')" min-width="185">
              <template #default="{ row }"><div class="finance-stack"><span>{{ c('v5.creationRemark') }}: {{ row.creationRemark || '—' }}</span><span>{{ c('field.remark') }}: {{ row.remark || '—' }}</span></div></template>
            </ElTableColumn>
          </template>
          <ElTableColumn
            v-for="key in isReviewable ? [] : allColumns"
            :key="key"
            :label="fieldLabel(key)"
            :min-width="
              key.endsWith('At') ? 180 : key === 'address' ? 210 : 120
            "
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <ElTag
                v-if="key === 'status'"
                :type="
                  row.status === 'REJECTED'
                    ? 'danger'
                    : row.status === 'PENDING'
                      ? 'warning'
                      : 'success'
                "
              >
                {{ enumLabel(row.status) }}
              </ElTag>
              <ElButton v-else-if="['orderNo', 'businessOrderNo', 'ledgerNo'].includes(key)" link type="primary" class="finance-number" @click="copyValue(row[key])">{{ row[key] || '—' }}</ElButton>
              <span v-else>{{ display(key, row[key]) }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn
            fixed="right"
            :label="c('common.actions')"
            :width="(canReview || canDirectApprove) && isReviewable ? 160 : 90"
          >
            <template #default="{ row }">
              <ElButton
                link
                type="primary"
                @click="
                  selected = row;
                  detailOpen = true;
                "
              >
                {{ c('common.details') }}
              </ElButton>
              <ElButton
                v-if="isReviewable && reviewable(row) && (canReview || (canDirectApprove && directApprovable(row)))"
                link
                type="warning"
                @click="openReview(row)"
              >
                {{ c('common.review') }}
              </ElButton>
              <ElButton v-if="resource === 'withdrawals' && canReview" link type="info" :disabled="Boolean(attentionSaving)" :loading="attentionSaving === String(row.id)" @click="toggleAttention(row)">{{ c(row.attentionIgnored ? 'v27.restore' : 'v27.ignore') }}</ElButton>
              <ElButton v-if="isReviewable && globalGateway && canGatewayOperate && (row.gatewayReference || (resource === 'withdrawals' && row.gatewayState))" link type="primary" :loading="reconciling === investigationReference(row)" :disabled="Boolean(reconciling || cancelling)" @click="reconcile(row)">{{ c('v11.payment.recheck') }}</ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
        <div class="compute-pagination">
          <ElPagination
            v-model:current-page="pager.page"
            v-model:page-size="pager.size"
            :total="pager.total"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            @current-change="load()"
            @size-change="resize"
          />
        </div>
      </ElCard>
      <ElDialog v-model="gatewayOpen" width="min(1080px,96vw)" :title="c('v11.payment.investigation')">
        <div class="compute-toolbar"><ElButton :loading="gatewayLoading" @click="loadGateway">{{ c('v11.payment.refresh') }}</ElButton></div>
        <ElAlert v-if="gatewayFailed" :closable="false" type="error" :title="c('common.loadFailed')" />
        <ElTable v-loading="gatewayLoading" :data="gatewayRows" :empty-text="c('common.empty')">
          <ElTableColumn :label="c('field.username')" min-width="130"><template #default="{ row }">{{ row.username || '—' }}<span v-if="row.uid"> · UID {{ row.uid }}</span></template></ElTableColumn>
          <ElTableColumn :label="c('v6.orderNo')" min-width="180"><template #default="{ row }">{{ row.localOrderNo || '—' }}</template></ElTableColumn>
          <ElTableColumn :label="c('v11.payment.gatewayReference')" min-width="210"><template #default="{ row }"><ElButton link type="primary" @click="copyValue(row.gatewayReference)">{{ row.gatewayReference || '—' }}</ElButton></template></ElTableColumn>
          <ElTableColumn :label="c('v8.finance.channelInfo')" min-width="150"><template #default="{ row }">{{ currencyLabel(row.currency) || '—' }} / {{ row.network || '—' }}<br />{{ enumLabel(row.walletType) }}</template></ElTableColumn>
          <ElTableColumn :label="c('field.amount')" min-width="130"><template #default="{ row }">{{ row.amountText ?? row.amount ?? '—' }}</template></ElTableColumn>
          <ElTableColumn :label="c('v11.payment.reasonLabel')" min-width="170"><template #default="{ row }">{{ investigationReason(row.reason) }}</template></ElTableColumn>
          <ElTableColumn v-if="resource === 'withdrawals'" :label="c('v26Payout.gatewayState')" min-width="160"><template #default="{ row }">{{ payoutStage(row.state) }}</template></ElTableColumn>
          <ElTableColumn v-if="resource === 'withdrawals'" :label="c('v26Payout.gatewayNextAttemptAt')" min-width="170"><template #default="{ row }">{{ date(row.nextAttemptAt) }}</template></ElTableColumn>
          <ElTableColumn :label="c('field.updatedAt')" min-width="170"><template #default="{ row }">{{ date(row.updatedAt) }}</template></ElTableColumn>
          <ElTableColumn v-if="canGatewayOperate" :label="c('common.actions')" width="180"><template #default="{ row }"><ElButton v-if="investigationReference(row)" link type="primary" :loading="reconciling === investigationReference(row)" :disabled="Boolean(reconciling || cancelling)" @click="reconcile(row)">{{ c('v11.payment.recheck') }}</ElButton><ElButton v-if="investigationReference(row) && cancellable(row)" link type="danger" :loading="cancelling === investigationReference(row)" :disabled="Boolean(reconciling || cancelling)" @click="cancelUnsent(row)">{{ c('v11.payment.cancelUnsent') }}</ElButton></template></ElTableColumn>
        </ElTable>
        <ElPagination class="mt-4" :current-page="gatewayPager.page" :page-size="gatewayPager.pageSize" :total="gatewayPager.total" :page-sizes="[20, 50, 100]" layout="total, sizes, prev, pager, next" @current-change="changeGatewayPage" @size-change="changeGatewaySize" />
      </ElDialog>
      <ElDialog
        v-model="detailOpen"
        class="compute-dialog"
        width="760px"
        :title="c('common.details')"
      >
        <ElAlert v-if="resource === 'ledger' && selected.detailStatus !== 'MATCHED'" :title="c(selected.detailStatus === 'CONFLICT' ? 'v35Ledger.sourceConflict' : 'v35Ledger.historyUnavailable')" :type="selected.detailStatus === 'CONFLICT' ? 'error' : 'info'" :closable="false" class="mb-4" />
        <ElAlert v-if="resource === 'ledger' && selected.businessOrderStatus === 'CONFLICT'" :title="c('v35Ledger.sourceConflict')" type="error" :closable="false" class="mb-4" />
        <ElDescriptions :column="2" border>
          <ElDescriptionsItem v-if="collectionPresentation(selected)" :label="depositCollectionText('label', c)">
            <div class="finance-stack">
              <ElTag :type="collectionPresentation(selected)?.type">{{ collectionPresentation(selected)?.label }}</ElTag>
              <span v-if="collectionPresentation(selected)?.reason" class="finance-collection-reason">{{ collectionPresentation(selected)?.reason }}</span>
            </div>
          </ElDescriptionsItem>
          <template v-for="key in details" :key="key">
            <ElDescriptionsItem
              v-if="!(resource === 'ledger' && key === 'orderNo') && (resource === 'withdrawals' || !['gatewayState', 'gatewayReason', 'gatewayAttempts', 'gatewayNextAttemptAt'].includes(key)) && (selected[key] != null || (isReviewable && ['accountingRate', 'accountingAmount'].includes(key)))"
              :label="fieldLabel(key)"
            >
              <a v-if="key === 'txHash' && selected.transactionUrl" :href="selected.transactionUrl" target="_blank" rel="noopener noreferrer" class="compute-break transaction-link">{{ selected.txHash }}</a>
              <span v-else-if="key.startsWith('accounting')" class="compute-break">{{ accountingDisplay(selected, key) }}</span>
              <span v-else-if="isReviewable && ['amount', 'fee', 'netAmount'].includes(key)" class="compute-break">{{ key === 'amount' ? nativeDepositAmount(resource, selected) : selected[key] }} {{ currencyLabel(selected.currency) }}</span>
              <span v-else class="compute-break">
                {{ display(key, selected[key]) }}
              </span>
            </ElDescriptionsItem>
          </template>
          <ElDescriptionsItem
            v-for="item in recipientDetails"
            :key="item.label"
            :label="item.label"
          >
            <span class="compute-break">{{ item.value }}</span>
          </ElDescriptionsItem>
        </ElDescriptions>
        <template v-if="resource === 'ledger' && selected.detailFacts && Object.keys(selected.detailFacts).length">
          <h3 class="mb-3 mt-5">{{ c('v35Ledger.sourceDetails') }}</h3>
          <ElDescriptions :column="2" border>
            <template v-for="key in LEDGER_FACT_FIELDS" :key="key">
              <ElDescriptionsItem v-if="selected.detailFacts[key] != null" :label="c('v35Ledger.fact.' + key)">
                {{ key.endsWith('At') ? date(selected.detailFacts[key]) : key === 'productType' ? c('v35Ledger.product.' + selected.detailFacts[key]) : ['rewardMode', 'creditMode'].includes(key) ? c('v35Ledger.mode.' + selected.detailFacts[key]) : selected.detailFacts[key] }}<template v-if="key === 'rewardValue' && selected.detailFacts.rewardMode === 'PERCENT'">%</template>
              </ElDescriptionsItem>
            </template>
          </ElDescriptions>
        </template>
        <template v-if="gatewayFeeRows.length">
          <h3 class="mb-3 mt-5">{{ c('v11.payment.gatewayFees') }}</h3>
          <ElDescriptions :column="2" border>
            <ElDescriptionsItem v-for="fee in gatewayFeeRows" :key="fee.key" :label="c('v11.payment.fee.' + fee.key)">{{ fee.value }} {{ fee.asset }}</ElDescriptionsItem>
          </ElDescriptions>
        </template>
        <template v-if="gatewayReviewRows.length">
          <h3 class="mb-3 mt-5">{{ c('v13.payment.review') }}</h3>
          <ElDescriptions :column="2" border><ElDescriptionsItem v-for="item in gatewayReviewRows" :key="item.key" :label="c('v13.payment.' + item.key)">{{ item.value }}</ElDescriptionsItem></ElDescriptions>
        </template>
        <template v-if="gatewayChainRows.length">
          <h3 class="mb-3 mt-5">{{ c('v13.payment.chain') }}</h3>
          <ElDescriptions :column="2" border><ElDescriptionsItem v-for="item in gatewayChainRows" :key="item.key" :label="c('v13.payment.' + item.key)"><span class="compute-break">{{ item.value }}</span></ElDescriptionsItem></ElDescriptions>
        </template>
      </ElDialog>
      <ElDialog
        v-model="reviewOpen"
        class="compute-dialog"
        width="580px"
        :title="c('common.review')"
        :close-on-click-modal="false"
        :show-close="!saving"
        :close-on-press-escape="!saving"
      >
        <ElAlert
          :title="
            c(
              reviewResource === 'withdrawals'
                ? 'v5.withdrawalApproval'
                : 'hint.depositApproval',
            )
          "
          type="warning"
          :closable="false"
          class="mb-5"
        />
        <ElDescriptions :column="1" border class="mb-5">
          <ElDescriptionsItem :label="c('v6.orderNo')">
            {{ selected.orderNo }}
          </ElDescriptionsItem>
          <ElDescriptionsItem :label="c('field.username')">
            {{ selected.username || '—' }}
          </ElDescriptionsItem>
          <template v-if="resource === 'withdrawals'">
            <ElDescriptionsItem :label="c('v44Finance.withdrawalNet')"><strong>{{ accountingDisplay(selected, 'accountingNetAmount') }}</strong> · {{ selected.netAmount }} {{ currencyLabel(selected.currency) }}</ElDescriptionsItem>
            <ElDescriptionsItem :label="c('v44Finance.withdrawalFee')">{{ accountingDisplay(selected, 'accountingFee') }} · {{ selected.fee }} {{ currencyLabel(selected.currency) }}</ElDescriptionsItem>
            <ElDescriptionsItem :label="c('v44Finance.withdrawalGross')">{{ accountingDisplay(selected, 'accountingAmount') }} · {{ selected.amount }} {{ currencyLabel(selected.currency) }}</ElDescriptionsItem>
          </template>
          <template v-else>
            <ElDescriptionsItem :label="c('v22Accounting.originalAmount')">{{ nativeDepositAmount(resource, selected) }} {{ currencyLabel(selected.currency) }}</ElDescriptionsItem>
            <ElDescriptionsItem :label="c('v22Accounting.actualAmount')">{{ accountingDisplay(selected, 'accountingAmount') }}</ElDescriptionsItem>
          </template>
          <ElDescriptionsItem :label="c('v22Accounting.rate')">{{ accountingDisplay(selected, 'accountingRate') }}</ElDescriptionsItem>
          <ElDescriptionsItem
            v-if="selected.address"
            :label="c('field.address')"
          >
            <span class="compute-break">{{ selected.address }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem
            v-for="item in recipientDetails"
            :key="item.label"
            :label="item.label"
          >
            <span class="compute-break">{{ item.value }}</span>
          </ElDescriptionsItem>
        </ElDescriptions>
        <ElForm label-position="top" :disabled="saving">
          <ElFormItem :label="c('field.approved')">
            <ElRadioGroup v-model="review.action">
              <ElRadioButton v-if="canReview" value="approve">
                {{ c('common.approve') }}
              </ElRadioButton>
              <ElRadioButton v-if="canDirectApprove && directApprovable(selected)" value="direct">
                {{ c('v24Finance.directApprove') }}
              </ElRadioButton>
              <ElRadioButton v-if="canReview" value="reject">
                {{ c('common.reject') }}
              </ElRadioButton>
            </ElRadioGroup>
          </ElFormItem>
          <ElAlert v-if="review.action === 'direct'" :title="c('v24Finance.directHint')" type="warning" :closable="false" class="mb-4" />
          <ElFormItem :label="c('field.remark')" :required="review.action !== 'approve'">
            <ElInput
              v-model="review.remark"
              type="textarea"
              :rows="3"
              maxlength="500"
              show-word-limit
              :placeholder="c('hint.reviewRemark')"
            />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton :disabled="saving" @click="reviewOpen = false">
            {{ c('common.cancel') }}
          </ElButton>
          <ElButton
            :type="review.action === 'reject' ? 'danger' : 'primary'"
            :loading="saving"
            @click="submitReview"
          >
            {{ c('common.confirmReview') }}
          </ElButton>
        </template>
      </ElDialog>
      <ManualFunds
        v-if="resource === 'deposits' || resource === 'withdrawals'"
        v-model="manualOpen"
        :resource="resource"
        :default-currency="defaultCurrency"
        @saved="load()"
      />
      <DepositHashRecheck v-if="resource === 'deposits'" v-model="depositRecheckOpen" :allowed="canDepositRecheck" @saved="load()" />
      <DepositInvestigation v-if="resource === 'deposits'" v-model="depositInvestigationOpen" :allowed="canDepositStatus" :can-operate="canGatewayOperate" @saved="load()" />
      <ElDialog v-model="gatewayVerificationOpen" :title="c('merchant.gatewayVerification')" width="min(600px,94vw)" :close-on-click-modal="false" @closed="gatewayVerification.code = ''">
        <ElAlert type="warning" :closable="false" :title="c('merchant.secureHint')" class="mb-4" />
        <ElAlert type="info" :closable="false" :title="c(gatewayVerification.kind === 'cancel' ? 'v11.payment.cancelUnsentHelp' : 'merchant.reconcileHint')" class="mb-4" />
        <p class="mb-4">{{ gatewayVerification.username }} · {{ gatewayVerification.orderNo || gatewayVerification.reference }}</p>
        <ElForm label-position="top" @submit.prevent="submitGatewayVerification">
          <ElFormItem :label="c('merchant.operationReason')"><ElInput v-model="gatewayVerification.remark" type="textarea" :rows="3" maxlength="500" show-word-limit /></ElFormItem>
          <ElFormItem :label="c('merchant.googleCode')"><ElInput v-model="gatewayVerification.code" maxlength="6" inputmode="numeric" autocomplete="off" /></ElFormItem>
        </ElForm>
        <template #footer><ElButton @click="gatewayVerificationOpen = false">{{ c('common.cancel') }}</ElButton><ElButton type="primary" :loading="Boolean(reconciling || cancelling)" @click="submitGatewayVerification">{{ c('merchant.submit') }}</ElButton></template>
      </ElDialog>
    </div>
  </Page>
</template>

<style scoped>
.transaction-link { color: var(--el-color-primary); text-decoration: underline; text-underline-offset: 2px; }
.finance-stack { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; padding: 4px 0; line-height: 1.55; overflow-wrap: anywhere; }
.finance-review-tags { display: flex; flex-wrap: wrap; align-items: center; gap: 4px; max-width: 100%; }
.finance-review-tags--vertical { flex-direction: column; align-items: flex-start; }
.finance-review-tags :deep(.el-tag) { height: auto; min-height: 24px; max-width: 100%; white-space: normal; }
.finance-review-tags :deep(.el-tag__content) { min-width: 0; white-space: normal; overflow-wrap: anywhere; }
.finance-collection-reason-tag { line-height: 1.55; padding-block: 3px; }
.finance-collection-reason { color: var(--exp-text-secondary); font-size: 12px; overflow-wrap: anywhere; }
.finance-stack > strong { font-weight: 600; color: var(--exp-text); }
.finance-stack > span:not([class]) { font-size: 12px; color: var(--exp-text-secondary); }
.finance-number { height: auto; max-width: 100%; user-select: text; font-variant-numeric: tabular-nums; white-space: normal; overflow-wrap: anywhere; text-align: left; }
.finance-chain-value { display: inline-block; max-width: 160px; overflow: hidden; text-overflow: ellipsis; vertical-align: bottom; white-space: nowrap; user-select: text; }
</style>
