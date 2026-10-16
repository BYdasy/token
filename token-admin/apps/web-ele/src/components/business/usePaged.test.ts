import { beforeEach, describe, expect, it, vi } from 'vitest';
import { computed, ref } from 'vue';

const fetchPage = vi.hoisted(() => vi.fn());
vi.mock('#/api/compute', () => ({ fetchComputePage: fetchPage }));
vi.mock('#/components/business/shared', () => ({ toUtc: (value?: string) => value }));
import { usePaged } from './usePaged';

beforeEach(() => {
  fetchPage.mockReset();
  fetchPage.mockResolvedValue({ records: [], total: 0 });
});

describe('业务列表固定分类边界', () => {
  it('首次加载、搜索和重置均保留文章分类，不因通用空kind混入用户信息', async () => {
    const state = usePaged(computed(() => 'content' as const), () => true, () => ({ kind: 'ARTICLE' }));
    await state.load();
    state.query.keyword = '关于我们';
    await state.search();
    await state.reset();
    expect(fetchPage).toHaveBeenCalledTimes(3);
    for (const call of fetchPage.mock.calls) expect(call[1].kind).toBe('ARTICLE');
    expect(fetchPage.mock.calls[1]?.[1].keyword).toBe('关于我们');
    expect(fetchPage.mock.calls[2]?.[1].keyword).toBeUndefined();
  });

  it('切换为用户任务后分页与显式额外参数也不能覆盖页面的固定分类', async () => {
    const kind = ref('ARTICLE');
    const state = usePaged(computed(() => 'content' as const), () => true, () => ({ kind: kind.value }));
    await state.load();
    kind.value = 'TASK';
    await state.reset();
    state.pager.page = 2;
    await state.load({ kind: 'MESSAGE' });
    expect(fetchPage.mock.lastCall?.[1]).toEqual(expect.objectContaining({ kind: 'TASK', page: 2 }));
  });

  it('重置清除页面后加字段、已提交条件、日期和页码，保留每页数量及当前固有分类', async () => {
    const state = usePaged(computed(() => 'activity-participations' as const), () => true, () => ({ type: 'WHEEL' }));
    Object.assign(state.query, { businessOrderNo: 'DP-old', ledgerNo: 'LG-old', operatorUsername: 'operator', attentionIgnored: 'true', gatewayReference: 'gateway', receiverAddress: 'address', txHash: 'hash', detailType: 'WITHDRAW', orderNo: 'WD-old' });
    state.dates.value = ['start', 'end']; state.pager.size = 50;
    await state.search(); state.pager.page = 4;
    const beforeReset = fetchPage.mock.calls.length;
    state.resetFilters();
    expect(fetchPage).toHaveBeenCalledTimes(beforeReset);
    expect(state.pager).toMatchObject({ page: 1, size: 50 }); expect(state.dates.value).toEqual([]);
    for (const key of ['businessOrderNo', 'ledgerNo', 'operatorUsername']) expect(Object.hasOwn(state.query, key)).toBe(false);
    for (const key of ['attentionIgnored', 'gatewayReference', 'receiverAddress', 'txHash', 'detailType', 'orderNo']) expect((state.query as Record<string, unknown>)[key]).toBe('');
    await state.load();
    expect(fetchPage.mock.lastCall?.[1]).toEqual({ type: 'WHEEL', page: 1, size: 50 });
    await state.exportPage(1, 100);
    expect(fetchPage.mock.lastCall?.[1]).toEqual({ type: 'WHEEL', page: 1, size: 100 });
  });
});
