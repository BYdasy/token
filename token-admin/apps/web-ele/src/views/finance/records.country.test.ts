import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, reactive, ref } from 'vue';
import { createMemoryHistory, createRouter, RouterView } from 'vue-router';
import { pageFilters } from '#/components/business/pageState';

const state = vi.hoisted(() => ({ page: vi.fn(), dictionaries: vi.fn(), access: {} as { accessToken: string }, locale: {} as { value: string } }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => state.access }));
vi.mock('@vben/access', () => ({ useAccess: () => ({ hasAccessByCodes: () => false, hasAccessByRoles: () => false }) }));
vi.mock('@vben/locales', () => ({ useI18n: () => ({ locale: state.locale, t: (key: string) => key }) }));
vi.mock('#/api/compute', () => ({ fetchComputePage: state.page, fetchComputeDictionaries: state.dictionaries }));
vi.mock('#/api/withdrawal-attention', () => ({ setWithdrawalAttention: vi.fn() }));
vi.mock('#/components/business/shared', () => ({
  P: { depositRead: 'read', withdrawalRead: 'read', ledgerRead: 'read', orderRead: 'read' },
  useCompute: () => ({ c: (key: string) => key, can: (...codes: string[]) => codes.includes('read'), enumLabel: String, date: String }),
  toUtc: (value: string | undefined) => value,
}));
vi.mock('#/components/business/BusinessListPage.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return { default: defineComponent({ setup: (_, { slots }) => () => h('main', slots.default?.()) }) };
});
vi.mock('./ManualFunds.vue', () => ({ default: { render: () => null } }));
vi.mock('./DepositHashRecheck.vue', () => ({ default: { render: () => null } }));
vi.mock('./DepositInvestigation.vue', () => ({ default: { render: () => null } }));
// 保留真实财务页、ElementPlus 表格及国家展示方法，只隔离接口和无关操作弹窗。
import Records from './records.vue';

const cleanup: Array<() => void> = [];
const flush = async () => { for (let index = 0; index < 10; index++) { await Promise.resolve(); await nextTick(); } };
beforeEach(() => {
  vi.resetAllMocks(); pageFilters.clear(); pageFilters.setOwner('finance-country-test');
  state.access = reactive({ accessToken: 'admin' }); state.locale = ref('zh');
  state.page.mockResolvedValue({ records: [
    { id: 1, uid: 101, username: 'country-member', lastLoginCountry: ' cn ', registrationCountry: 'US', amount: '1', currency: 'USDT', network: 'TRON', status: 'PENDING' },
    { id: 2, uid: 102, username: 'missing-country-member', lastLoginCountry: null, registrationCountry: 'US', amount: '1', currency: 'USDT', network: 'TRON', status: 'PENDING' },
  ], total: 2 });
  state.dictionaries.mockResolvedValue({ scope: { global: false }, currencies: [], groups: [] });
});
afterEach(() => { cleanup.splice(0).forEach(dispose => dispose()); pageFilters.clear(); });

describe('充提记录登录国家', () => {
  it.each(['deposits', 'withdrawals'] as const)('%s 在用户与代理单元格显示保存的登录国家，缺失值不使用注册国家', async (section) => {
    const path = `/finance/${section}`;
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path, component: Records, props: { section } }] });
    await router.push(path); await router.isReady();
    const host = document.createElement('div'); document.body.append(host);
    const app = createApp({ render: () => h(RouterView) }); app.use(router); app.directive('loading', {}); app.mount(host);
    cleanup.push(() => { app.unmount(); host.remove(); }); await flush();
    const cells = [...host.querySelectorAll<HTMLElement>('.el-table__body .cell')];
    const countryCell = cells.find(cell => cell.textContent?.includes('UID: 101'));
    const missingCell = cells.find(cell => cell.textContent?.includes('UID: 102'));
    expect(countryCell?.textContent).toContain('country-member');
    expect(countryCell?.textContent).toContain('v8.lastLoginCountry: 中国');
    expect(countryCell?.textContent).toContain('v5.directSuperior');
    expect(missingCell?.textContent).toContain('v8.lastLoginCountry: —');
    expect(missingCell?.textContent).not.toContain('美国');
    expect(state.page).toHaveBeenCalledTimes(1);
    expect(state.dictionaries).toHaveBeenCalledTimes(1);
  });
});
