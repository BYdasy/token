import { onScopeDispose, ref, watch } from 'vue';
import { merchantQuery } from '#/api/merchant';
import type { MerchantRow } from '#/api/merchant';

const receiptLimit = 20;

/** 每次只保留一页收款；翻页固定批次版本，失败后从首页重新核验。 */
export function useMerchantBatchDetails(batchId: () => string) {
  const batch = ref<MerchantRow>();
  const loading = ref(false);
  const failed = ref(false);
  const page = ref(1);
  const cursors = ref<(string | undefined)[]>([undefined]);
  let requestVersion = 0;
  let disposed = false;

  async function loadPage(targetPage: number, after?: string) {
    if (disposed || loading.value || !batchId()) return;
    const id = batchId(), run = ++requestVersion;
    const expectedVersion = batch.value?.resource_version;
    const current = () => !disposed && run === requestVersion && id === batchId();
    loading.value = true;
    failed.value = false;
    try {
      const response = await merchantQuery('batches', {
        batch_id: id,
        receipt_limit: receiptLimit,
        ...(after ? { receipt_after_id: after } : {}),
        ...(expectedVersion == null ? {} : { expected_resource_version: String(expectedVersion) }),
      }, true);
      if (!current()) return;
      const value = response.items?.[0];
      if (!Array.isArray(response.items) || response.items.length !== 1 ||
          !value || value.batch_id !== id || !Array.isArray(value.items) || value.items.length > receiptLimit ||
          !/^[1-9][0-9]*$/.test(String(value.resource_version)) ||
          !/^[0-9]+$/.test(String(value.receipt_count)) ||
          typeof value.receipt_has_more !== 'boolean' ||
          (expectedVersion != null && String(value.resource_version) !== String(expectedVersion)) ||
          (value.receipt_has_more && (typeof value.receipt_next_cursor !== 'string' ||
            !value.receipt_next_cursor.trim() || value.receipt_next_cursor === after))) {
        throw new Error('归集收款分页响应无效');
      }
      batch.value = value;
      page.value = targetPage;
      cursors.value = [...cursors.value.slice(0, targetPage - 1), after];
    } catch {
      if (current()) failed.value = true;
    } finally {
      if (current()) loading.value = false;
    }
  }

  async function reload() {
    requestVersion++;
    loading.value = false;
    failed.value = false;
    batch.value = undefined;
    page.value = 1;
    cursors.value = [undefined];
    await loadPage(1);
  }

  async function next() {
    if (failed.value || !batch.value?.receipt_has_more) return;
    await loadPage(page.value + 1, batch.value.receipt_next_cursor);
  }

  async function previous() {
    if (failed.value || page.value <= 1) return;
    await loadPage(page.value - 1, cursors.value[page.value - 2]);
  }

  watch(batchId, reload, { immediate: true, flush: 'sync' });
  onScopeDispose(() => { disposed = true; requestVersion++; });
  return { batch, loading, failed, page, next, previous, reload };
}
