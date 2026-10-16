import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { effectScope, reactive, ref } from 'vue';
const state = vi.hoisted(() => ({ query: vi.fn(), access: null as any, locale: null as any }));
vi.mock('#/api/merchant', () => ({ merchantQuery: state.query }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => state.access }));
vi.mock('@vben/locales', () => ({ useI18n: () => ({ locale: state.locale }) }));
import { useMerchantEnergyAccount } from './useMerchantEnergyAccount';
const scopes: ReturnType<typeof effectScope>[] = [];
async function settle() { for (let step = 0; step < 5; step++) await Promise.resolve(); }
function fixture(permitted = true) { const allowed = ref(permitted), scope = effectScope(); scopes.push(scope); return { allowed, scope, ...scope.run(() => useMerchantEnergyAccount(() => allowed.value))! }; }
beforeEach(() => { vi.resetAllMocks(); state.access = reactive({ accessToken: 'admin-one' }); state.locale = ref('zh'); });
afterEach(() => scopes.splice(0).forEach(scope => scope.stop()));
describe('供应商余额只读快照', () => {
  it('只查询快照且保留空余额，刷新不会发同步或采购', async () => {
    state.query.mockResolvedValue({ status: 'UNINITIALIZED', balance_trx: null });
    const view = fixture(); await settle(); expect(view.account.value?.balance_trx).toBeNull();
    await view.load(); expect(state.query).toHaveBeenCalledTimes(2); expect(state.query).toHaveBeenLastCalledWith('energy-account', {}, true);
  });
  it('无权限不查询，撤销权限后迟到响应不能回填', async () => {
    let finish!: (value: any) => void; state.query.mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    const view = fixture(false); expect(state.query).not.toHaveBeenCalled();
    view.allowed.value = true; view.allowed.value = false; finish({ name: 'old' }); await settle(); expect(view.account.value).toBeUndefined(); expect(view.loading.value).toBe(false);
  });
  it.each(['account', 'language', 'dispose'])('%s 改变使旧响应失效', async change => {
    let finish!: (value: any) => void;
    state.query.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; })).mockResolvedValue({ name: 'current' });
    const view = fixture();
    if (change === 'account') state.access.accessToken = 'admin-two';
    else if (change === 'language') state.locale.value = 'en'; else view.scope.stop();
    finish({ name: 'old' }); await settle(); expect(view.account.value?.name).not.toBe('old');
  });
  it('失败不伪造零余额且可明确重试', async () => {
    state.query.mockRejectedValueOnce(new Error('unavailable')).mockResolvedValue({ status: 'READY', balance_trx: '0' });
    const view = fixture(); await settle(); expect(view.failed.value).toBe(true); expect(view.account.value).toBeUndefined();
    await view.load(); expect(view.failed.value).toBe(false); expect(view.account.value?.balance_trx).toBe('0');
  });
});
