import { onScopeDispose, reactive, ref, watch } from 'vue';
import { useAccessStore } from '@vben/stores';
import { useI18n } from '@vben/locales';
import { fetchComputeGatewayStatus, reconcileComputePayment, type ComputeRecord, type DepositReconciliationResult, type DepositReviewRow } from '#/api/compute';

/** 充值异常查询与逐次核验共用身份边界，旧分页和核验响应不能回填新会话。 */
export function useDepositInvestigation(allowed: () => boolean, canOperate: () => boolean, saved: () => void) {
  const access = useAccessStore();
  const { locale } = useI18n();
  const open = ref(false), loading = ref(false), failed = ref(false), saving = ref(false);
  const rows = ref<DepositReviewRow[]>([]), environment = ref('');
  const pager = reactive({ page: 1, pageSize: 20, total: 0 });
  const selected = ref<DepositReviewRow | null>(null), verificationOpen = ref(false);
  const verification = reactive({ remark: '', code: '' });
  const result = ref<DepositReconciliationResult | null>(null), submissionFailed = ref(false);
  let generation = 0, operation = 0, disposed = false;
  const context = () => `${access.accessToken}\n${locale.value}`;
  function invalidate(clear = false) {
    generation++; operation++; loading.value = false; saving.value = false;
    verificationOpen.value = false; verification.code = ''; result.value = null; submissionFailed.value = false;
    if (clear) {
      rows.value = []; environment.value = ''; failed.value = false; selected.value = null;
      verification.remark = ''; Object.assign(pager, { page: 1, pageSize: 20, total: 0 });
    }
  }
  async function load() {
    if (disposed || !open.value || !allowed() || !access.accessToken) return;
    const own = ++generation, owner = context(), page = pager.page, pageSize = pager.pageSize;
    const current = () => !disposed && own === generation && owner === context() && open.value && allowed();
    loading.value = true; failed.value = false;
    try {
      const response = await fetchComputeGatewayStatus('deposits', { page, pageSize });
      if (!current()) return;
      pager.total = Math.max(0, Number(response.total) || 0);
      environment.value = String(response.environment || '');
      const lastPage = Math.max(1, Math.ceil(pager.total / pageSize));
      if (page > lastPage) { pager.page = lastPage; await load(); return; }
      rows.value = Array.isArray(response.review) ? response.review : [];
    } catch {
      if (current()) { rows.value = []; failed.value = true; }
    } finally { if (own === generation) loading.value = false; }
  }
  function show() { if (!allowed() || !access.accessToken) return; open.value = true; void load(); }
  function changePage(page: number) { pager.page = page; void load(); }
  function changeSize(size: number) { pager.pageSize = size; pager.page = 1; void load(); }
  function actionable(row: ComputeRecord): row is DepositReviewRow {
    return allowed() && canOperate() && row.canReconcile === true && ['DEPOSIT_ORDER', 'COLLECTION_BATCH'].includes(row.resourceType) && Boolean(row.gatewayReference);
  }
  function select(row: ComputeRecord) {
    if (!open.value || saving.value || !actionable(row)) return;
    operation++; selected.value = { ...row }; verification.remark = ''; verification.code = '';
    result.value = null; submissionFailed.value = false; verificationOpen.value = true;
  }
  async function submit() {
    const row = selected.value;
    if (!open.value || !verificationOpen.value || saving.value || !row || !actionable(row) || !access.accessToken) return;
    if (!verification.remark.trim() || verification.remark.length > 500) return 'remark';
    if (!/^\d{6}$/.test(verification.code)) return 'code';
    const own = ++operation, owner = context();
    const current = () => !disposed && own === operation && owner === context() && open.value && verificationOpen.value && actionable(row);
    saving.value = true; submissionFailed.value = false; result.value = null;
    try {
      const response = await reconcileComputePayment('DEPOSIT', row.gatewayReference, verification.remark.trim(), verification.code);
      if (!current()) return;
      result.value = response; verificationOpen.value = false;
      saved(); await load();
    } catch {
      if (current()) submissionFailed.value = true;
    } finally { if (own === operation) { saving.value = false; verification.code = ''; } }
  }
  watch(open, value => { if (!value) invalidate(); }, { flush: 'sync' });
  watch(verificationOpen, value => { if (!value) { operation++; saving.value = false; verification.code = ''; } }, { flush: 'sync' });
  watch(() => [access.accessToken, allowed(), canOperate()] as const, ([token, permitted, operator], previous) => {
    if (token !== previous[0] || !permitted) { invalidate(true); open.value = false; }
    else if (operator !== previous[2]) { invalidate(); if (open.value) void load(); }
  }, { flush: 'sync' });
  watch(locale, () => { invalidate(); if (open.value) void load(); }, { flush: 'sync' });
  onScopeDispose(() => { disposed = true; invalidate(true); });
  return { open, loading, failed, saving, rows, environment, pager, selected, verificationOpen, verification, result, submissionFailed, load, show, changePage, changeSize, actionable, select, submit };
}
