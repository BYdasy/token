import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { effectScope, nextTick, reactive, ref } from 'vue';

const state = vi.hoisted(() => ({ fetch: vi.fn(), access: null as any, locale: null as any }));
vi.mock('#/api/compute', () => ({ fetchComputeGatewayStatus: state.fetch }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => state.access }));
vi.mock('@vben/locales', () => ({ useI18n: () => ({ locale: state.locale }) }));
import { usePayoutInvestigation } from './usePayoutInvestigation';

const scopes: ReturnType<typeof effectScope>[] = [];
function setup(allowed = ref(true)) {
  const scope = effectScope(); scopes.push(scope);
  return { ...scope.run(() => usePayoutInvestigation(() => allowed.value))!, allowed };
}
async function flush() { await Promise.resolve(); await nextTick(); await Promise.resolve(); }
beforeEach(() => {
  vi.resetAllMocks(); state.access = reactive({ accessToken: 'admin-a' }); state.locale = ref('zh');
  state.fetch.mockResolvedValue({ payoutReview: [], page: 1, pageSize: 20, total: 0 });
});
afterEach(() => scopes.splice(0).forEach(scope => scope.stop()));

describe('提款异常核查实际分页', () => {
  it('按服务端total分页，切页传page/pageSize，缩小页容量从第一页读取', async () => {
    state.fetch.mockResolvedValue({ payoutReview: [{ gatewayReference: 'second-page' }], page: 2, pageSize: 20, total: 221 });
    const view = setup(); view.show(); await flush(); view.changePage(2); await flush();
    expect(state.fetch.mock.lastCall).toEqual(['withdrawals', { page: 2, pageSize: 20 }]);
    expect(view.pager.total).toBe(221); expect(view.rows.value[0]?.gatewayReference).toBe('second-page');
    view.changeSize(50); await flush();
    expect(state.fetch.mock.lastCall).toEqual(['withdrawals', { page: 1, pageSize: 50 }]);
  });

  it('较旧分页响应不会覆盖新页，关闭弹窗的迟到响应不回填且结束loading', async () => {
    let resolveOld!: (value: unknown) => void, resolveLatest!: (value: unknown) => void;
    state.fetch.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }))
      .mockImplementationOnce(() => new Promise(resolve => { resolveLatest = resolve; }));
    const view = setup(); view.show(); view.changePage(2);
    resolveLatest({ payoutReview: [{ gatewayReference: 'latest' }], total: 80 }); await flush();
    resolveOld({ payoutReview: [{ gatewayReference: 'old' }], total: 90 }); await flush();
    expect(view.rows.value[0]?.gatewayReference).toBe('latest'); expect(view.pager.total).toBe(80);
    state.fetch.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }));
    void view.load(); view.open.value = false;
    resolveOld({ payoutReview: [{ gatewayReference: 'closed' }], total: 5 }); await flush();
    expect(view.rows.value[0]?.gatewayReference).toBe('latest'); expect(view.loading.value).toBe(false);
  });

  it('最后一页记录减少后返回有效末页，核查操作后不留空的越界页', async () => {
    state.fetch.mockResolvedValueOnce({ payoutReview: [], total: 41 })
      .mockResolvedValueOnce({ payoutReview: [], total: 40 })
      .mockResolvedValueOnce({ payoutReview: [{ gatewayReference: 'last-valid' }], total: 40 });
    const view = setup(); view.show(); await flush(); view.changePage(3); await flush();
    expect(view.pager.page).toBe(2); expect(state.fetch.mock.lastCall?.[1]).toEqual({ page: 2, pageSize: 20 });
    expect(view.rows.value[0]?.gatewayReference).toBe('last-valid'); expect(view.loading.value).toBe(false);
  });

  it('身份或权限变化立即清理旧结果，语言变化忽略旧响应并按当前页重查', async () => {
    let resolveOld!: (value: unknown) => void;
    state.fetch.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }));
    const view = setup(); view.show(); state.locale.value = 'en'; await flush();
    resolveOld({ payoutReview: [{ gatewayReference: 'old-language' }], total: 99 }); await flush();
    expect(view.rows.value).toEqual([]); expect(view.pager.total).toBe(0);
    state.access.accessToken = 'admin-b'; await flush(); expect(view.open.value).toBe(false);
    view.allowed.value = false; view.show(); await flush(); expect(state.fetch).toHaveBeenCalledTimes(2);
  });
});
