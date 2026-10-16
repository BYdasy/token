import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { effectScope, nextTick, reactive, ref } from 'vue';
import type { DepositReviewRow } from '#/api/compute';

const state = vi.hoisted(() => ({ fetch: vi.fn(), reconcile: vi.fn(), access: null as any, locale: null as any }));
vi.mock('#/api/compute', () => ({ fetchComputeGatewayStatus: state.fetch, reconcileComputePayment: state.reconcile }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => state.access }));
vi.mock('@vben/locales', () => ({ useI18n: () => ({ locale: state.locale }) }));
import { useDepositInvestigation } from './useDepositInvestigation';

const row: DepositReviewRow = { gatewayReference: 'deposit-1', resourceType: 'DEPOSIT_ORDER', canReconcile: true, canRecover: true, missingLocalOrder: true, amountText: '0.000000000000000000000000000001' };
const scopes: ReturnType<typeof effectScope>[] = [];
function setup() {
  const scope = effectScope(); scopes.push(scope);
  const allowed = ref(true), operator = ref(true), saved = vi.fn();
  return { ...scope.run(() => useDepositInvestigation(() => allowed.value, () => operator.value, saved))!, allowed, operator, saved };
}
async function flush() { await Promise.resolve(); await nextTick(); await Promise.resolve(); await nextTick(); }
function prepare(view: ReturnType<typeof setup>) { view.select(row); view.verification.remark = '核对原订单'; view.verification.code = '123456'; }
beforeEach(() => {
  vi.resetAllMocks(); state.access = reactive({ accessToken: 'admin-a' }); state.locale = ref('zh');
  state.fetch.mockResolvedValue({ review: [row], page: 1, pageSize: 20, total: 1, environment: 'LIVE' });
  state.reconcile.mockResolvedValue({ resourceType: 'DEPOSIT_ORDER', state: 'CREDITED', reason: null, localOrderNo: 'local-1', credited: true, alreadyCredited: false, orderCreated: true });
});
afterEach(() => scopes.splice(0).forEach(scope => scope.stop()));

describe('充值异常分页与权威核验', () => {
  it('按服务端总数请求真正的分页并原样保留高精度金额', async () => {
    state.fetch.mockResolvedValue({ review: [row], total: 221, environment: 'LIVE' });
    const view = setup(); view.show(); await flush(); view.changePage(2); await flush();
    expect(state.fetch.mock.lastCall).toEqual(['deposits', { page: 2, pageSize: 20 }]);
    expect(view.rows.value[0]?.amountText).toBe('0.000000000000000000000000000001');
    expect(view.pager.total).toBe(221); expect(view.environment.value).toBe('LIVE');
    view.changeSize(100); await flush();
    expect(state.fetch.mock.lastCall).toEqual(['deposits', { page: 1, pageSize: 100 }]);
  });

  it('旧分页和关闭后的响应不覆盖当前结果，末页减少后返回有效页', async () => {
    let resolveOld!: (value: unknown) => void;
    state.fetch.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }))
      .mockResolvedValueOnce({ review: [{ ...row, gatewayReference: 'new' }], total: 80 });
    const view = setup(); view.show(); view.changePage(2); await flush();
    resolveOld({ review: [row], total: 99 }); await flush(); expect(view.rows.value[0]?.gatewayReference).toBe('new');
    state.fetch.mockResolvedValueOnce({ review: [], total: 20 }).mockResolvedValueOnce({ review: [row], total: 20 });
    await view.load(); expect(view.pager.page).toBe(1); expect(view.rows.value[0]?.gatewayReference).toBe('deposit-1');
    state.fetch.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }));
    void view.load(); view.open.value = false;
    resolveOld({ review: [], total: 0 }); await flush(); expect(view.rows.value).toHaveLength(1); expect(view.loading.value).toBe(false);
  });

  it('只读权限、未知类型和不可核验行不能发操作；无查看权限不加载', async () => {
    const view = setup(); view.operator.value = false; view.show(); await flush();
    prepare(view); await view.submit(); expect(state.reconcile).not.toHaveBeenCalled();
    view.operator.value = true;
    view.select({ ...row, resourceType: 'UNKNOWN' }); expect(view.verificationOpen.value).toBe(false);
    view.select({ ...row, canReconcile: false }); expect(view.verificationOpen.value).toBe(false);
    const authorizedQueries = state.fetch.mock.calls.length;
    view.allowed.value = false; view.show(); await flush();
    expect(view.open.value).toBe(false); expect(view.rows.value).toEqual([]); expect(state.fetch).toHaveBeenCalledTimes(authorizedQueries);
  });

  it('提交原引用和逐次TOTP，不接受手工金额；入账结果保留供展示并刷新列表', async () => {
    const view = setup(); view.show(); await flush(); view.select(row);
    expect(await view.submit()).toBe('remark'); view.verification.remark = '核对原订单';
    expect(await view.submit()).toBe('code'); view.verification.code = '123456';
    await view.submit();
    expect(state.reconcile).toHaveBeenCalledExactlyOnceWith('DEPOSIT', 'deposit-1', '核对原订单', '123456');
    expect(view.result.value).toMatchObject({ credited: true, alreadyCredited: false, localOrderNo: 'local-1' });
    expect(view.verification.code).toBe(''); expect(view.saving.value).toBe(false); expect(view.saved).toHaveBeenCalledTimes(1);
    view.select(row); view.verification.remark = '再次核对'; expect(await view.submit()).toBe('code');
  });

  it('核验失败保留原订单但清空TOTP，新验证码才能安全重试', async () => {
    state.reconcile.mockRejectedValueOnce(new Error('timeout'));
    const view = setup(); view.show(); await flush(); prepare(view); await view.submit();
    expect(view.submissionFailed.value).toBe(true); expect(view.result.value).toBeNull(); expect(view.verification.code).toBe('');
    expect(await view.submit()).toBe('code'); expect(state.reconcile).toHaveBeenCalledTimes(1);
    view.verification.code = '654321'; await view.submit();
    expect(state.reconcile.mock.lastCall).toEqual(['DEPOSIT', 'deposit-1', '核对原订单', '654321']);
  });

  it.each(['close', 'language', 'account', 'permission', 'operationPermission', 'dispose'])('%s变化使迟到核验结果无效且结束loading', async change => {
    let resolve!: (value: unknown) => void;
    state.reconcile.mockImplementationOnce(() => new Promise(done => { resolve = done; }));
    const view = setup(); view.show(); await flush(); prepare(view); const request = view.submit();
    if (change === 'close') view.open.value = false;
    if (change === 'language') state.locale.value = 'en';
    if (change === 'account') state.access.accessToken = 'admin-b';
    if (change === 'permission') view.allowed.value = false;
    if (change === 'operationPermission') view.operator.value = false;
    if (change === 'dispose') scopes[scopes.length - 1]!.stop();
    resolve({ credited: true, state: 'CREDITED', localOrderNo: 'late' }); await request; await flush();
    expect(view.result.value).toBeNull(); expect(view.saved).not.toHaveBeenCalled(); expect(view.saving.value).toBe(false); expect(view.verification.code).toBe('');
  });

  it('语言和账号变化使旧列表响应失效', async () => {
    let resolve!: (value: unknown) => void;
    state.fetch.mockImplementationOnce(() => new Promise(done => { resolve = done; }));
    const view = setup(); view.show(); state.locale.value = 'en'; await flush();
    resolve({ review: [{ ...row, gatewayReference: 'old-language' }], total: 99 }); await flush();
    expect(view.rows.value[0]?.gatewayReference).toBe('deposit-1'); expect(view.pager.total).toBe(1);
    state.access.accessToken = 'admin-b'; await flush(); expect(view.open.value).toBe(false); expect(view.rows.value).toEqual([]);
  });
});
