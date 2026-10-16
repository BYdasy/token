import { onScopeDispose, reactive, ref, watch } from 'vue';
import { useAccessStore } from '@vben/stores';
import { useI18n } from '@vben/locales';
import { fetchComputeGatewayStatus, type ComputeRecord } from '#/api/compute';

/** 弹窗独立分页；关闭、切换身份或权限后，旧查询不能回填。 */
export function usePayoutInvestigation(allowed: () => boolean) {
  const access = useAccessStore();
  const { locale } = useI18n();
  const open = ref(false), loading = ref(false), failed = ref(false);
  const rows = ref<ComputeRecord[]>([]);
  const pager = reactive({ page: 1, pageSize: 20, total: 0 });
  let generation = 0, disposed = false;
  const context = () => `${access.accessToken}\n${locale.value}`;
  function invalidate(clear = false) {
    generation++;
    loading.value = false;
    if (clear) { rows.value = []; failed.value = false; Object.assign(pager, { page: 1, pageSize: 20, total: 0 }); }
  }
  async function load() {
    if (disposed || !open.value || !allowed() || !access.accessToken) return;
    const own = ++generation, owner = context();
    const page = pager.page, pageSize = pager.pageSize;
    const current = () => !disposed && own === generation && owner === context() && open.value && allowed();
    loading.value = true; failed.value = false;
    try {
      const response = await fetchComputeGatewayStatus('withdrawals', { page, pageSize });
      if (!current()) return;
      const total = Math.max(0, Number(response.total) || 0);
      const lastPage = Math.max(1, Math.ceil(total / pageSize));
      pager.total = total;
      if (page > lastPage) { pager.page = lastPage; await load(); return; }
      rows.value = Array.isArray(response.payoutReview) ? response.payoutReview : [];
    } catch {
      if (current()) { rows.value = []; failed.value = true; }
    } finally { if (own === generation) loading.value = false; }
  }
  function show() { if (!allowed()) return; open.value = true; void load(); }
  function changePage(page: number) { pager.page = page; void load(); }
  function changeSize(pageSize: number) { pager.pageSize = pageSize; pager.page = 1; void load(); }
  watch(open, value => { if (!value) invalidate(); }, { flush: 'sync' });
  watch(() => [access.accessToken, allowed()] as const, ([token, permitted], [previousToken]) => {
    if (token !== previousToken || !permitted) { invalidate(true); open.value = false; }
  }, { flush: 'sync' });
  watch(locale, () => { invalidate(); if (open.value) void load(); }, { flush: 'sync' });
  onScopeDispose(() => { disposed = true; invalidate(true); });
  return { open, loading, failed, rows, pager, load, show, changePage, changeSize };
}
