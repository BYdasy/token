import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { effectScope, ref } from 'vue';

const api = vi.hoisted(() => ({ query: vi.fn() }));
vi.mock('#/api/merchant', () => ({ merchantQuery: api.query }));
import { useMerchantBatchDetails } from './useMerchantBatchDetails';

const scopes: ReturnType<typeof effectScope>[] = [];
function fixture(id = 'batch-1') {
  const batchId = ref(id);
  const scope = effectScope();
  scopes.push(scope);
  const state = scope.run(() => useMerchantBatchDetails(() => batchId.value))!;
  return { batchId, scope, state };
}
function response(id = 'batch-1', version = 7, receipt = 'receipt-1', more = true) {
  return { items: [{
    batch_id: id, resource_version: version, receipt_count: 2,
    receipt_has_more: more, receipt_next_cursor: more ? receipt : null,
    items: [{ receipt_id: receipt }],
  }] };
}
async function settle() { for (let step = 0; step < 5; step++) await Promise.resolve(); }

beforeEach(() => vi.resetAllMocks());
afterEach(() => scopes.splice(0).forEach(scope => scope.stop()));

describe('商户归集收款分页', () => {
  it('按用户操作逐页查询，并将同一批次版本带到下一页和上一页', async () => {
    api.query.mockResolvedValueOnce(response()).mockResolvedValueOnce(response('batch-1', 7, 'receipt-2', false))
      .mockResolvedValueOnce(response());
    const { state } = fixture();
    await settle();
    expect(api.query).toHaveBeenCalledTimes(1);
    expect(api.query).toHaveBeenLastCalledWith('batches', { batch_id: 'batch-1', receipt_limit: 20 }, true);
    await state.next();
    expect(api.query).toHaveBeenLastCalledWith('batches', {
      batch_id: 'batch-1', receipt_limit: 20, receipt_after_id: 'receipt-1', expected_resource_version: '7',
    }, true);
    expect(state.batch.value?.items).toEqual([{ receipt_id: 'receipt-2' }]);
    expect(state.page.value).toBe(2);
    await state.next();
    expect(api.query).toHaveBeenCalledTimes(2);
    await state.previous();
    expect(api.query).toHaveBeenLastCalledWith('batches', {
      batch_id: 'batch-1', receipt_limit: 20, expected_resource_version: '7',
    }, true);
    expect(state.page.value).toBe(1);
  });

  it('版本变化不拼接明细，保留当前页并要求从首页重新获取', async () => {
    api.query.mockResolvedValueOnce(response()).mockResolvedValueOnce(response('batch-1', 8, 'receipt-2', false))
      .mockResolvedValueOnce(response('batch-1', 8));
    const { state } = fixture();
    await settle();
    await state.next();
    expect(state.failed.value).toBe(true);
    expect(state.page.value).toBe(1);
    expect(state.batch.value?.items).toEqual([{ receipt_id: 'receipt-1' }]);
    await state.next();
    expect(api.query).toHaveBeenCalledTimes(2);
    await state.reload();
    expect(api.query).toHaveBeenLastCalledWith('batches', { batch_id: 'batch-1', receipt_limit: 20 }, true);
    expect(state.failed.value).toBe(false);
    expect(state.batch.value?.resource_version).toBe(8);
  });

  it('翻页请求失败保留当前页且解除加载状态', async () => {
    api.query.mockResolvedValueOnce(response()).mockRejectedValueOnce(new Error('网络超时'));
    const { state } = fixture();
    await settle();
    await state.next();
    expect(state.failed.value).toBe(true);
    expect(state.loading.value).toBe(false);
    expect(state.page.value).toBe(1);
    expect(state.batch.value?.resource_version).toBe(7);
  });

  it('切换批次和关闭详情后忽略迟到响应', async () => {
    let finishFirst!: (value: ReturnType<typeof response>) => void;
    let finishSecond!: (value: ReturnType<typeof response>) => void;
    api.query.mockImplementationOnce(() => new Promise(resolve => { finishFirst = resolve; }))
      .mockImplementationOnce(() => new Promise(resolve => { finishSecond = resolve; }));
    const { state, batchId, scope } = fixture();
    batchId.value = 'batch-2';
    finishFirst(response());
    await settle();
    expect(state.batch.value).toBeUndefined();
    expect(state.loading.value).toBe(true);
    scope.stop();
    finishSecond(response('batch-2'));
    await settle();
    expect(state.batch.value).toBeUndefined();
  });
});
