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
vi.mock('#/adapter/table', () => ({ ElTable: { render: () => null } }));
vi.mock('./ManualFunds.vue', () => ({ default: { render: () => null } }));
vi.mock('./DepositHashRecheck.vue', () => ({ default: { render: () => null } }));
vi.mock('./DepositInvestigation.vue', () => ({ default: { render: () => null } }));
// 真实财务页/usePaged/BusinessSearchFields/ElementPlus筛选与日期控件；不触发审批、补查或资金接口。
import Records from './records.vue';

const cleanup: Array<() => void> = [];
const flush = async () => { for (let index = 0; index < 8; index++) { await Promise.resolve(); await nextTick(); } };
async function mount(section: 'withdrawals' | 'ledger' | 'deposits') {
  const path = `/finance/${section}`;
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path, component: Records, props: { section } }] });
  await router.push(path); await router.isReady();
  const host = document.createElement('div'); document.body.append(host);
  const app = createApp({ render: () => h(RouterView) }); app.use(router); app.directive('loading', {}); app.mount(host);
  cleanup.push(() => { app.unmount(); host.remove(); }); await flush(); return host;
}
function button(host: HTMLElement, key: string) {
  const element = [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.trim() === key);
  expect(element).toBeDefined(); return element!;
}
async function click(element: HTMLElement) {
  element.dispatchEvent(new MouseEvent('mousedown', { bubbles: true })); element.focus();
  element.dispatchEvent(new MouseEvent('mouseup', { bubbles: true })); element.click(); await flush();
}
beforeEach(() => {
  vi.resetAllMocks(); pageFilters.clear(); pageFilters.setOwner('finance-reset-test');
  state.access = reactive({ accessToken: 'admin' }); state.locale = ref('zh');
  state.page.mockResolvedValue({ records: [], total: 200 });
  state.dictionaries.mockResolvedValue({ scope: { global: false }, currencies: [{ currency: 'USDT', network: 'TRON' }], groups: [{ id: 1, name: 'group-one' }] });
});
afterEach(() => { cleanup.splice(0).forEach(dispose => dispose()); pageFilters.clear(); });

describe('财务记录实际筛选重置', () => {
  it.each(['withdrawals', 'ledger', 'deposits'] as const)('%s恢复旧筛选后可清空选择/输入/日期，重置快照在重挂后不反弹', async (section) => {
    const query = {
      username: 'saved-member', orderNo: 'saved-order', groupId: 1, currency: 'USDT', minAmount: '10',
      ...(section === 'withdrawals' ? { attentionIgnored: 'true', gatewayReference: 'saved-gateway', receiverAddress: 'saved-address', txHash: 'saved-hash' }
        : section === 'ledger' ? { detailType: 'WITHDRAW_APPROVED', direction: 'OUT' }
          : { txHash: 'saved-hash', firstDeposit: 'true', collectionState: 'NOT_COLLECTED' }),
    };
    const key = JSON.stringify([`/finance/${section}`, section, {}]);
    pageFilters.save(key, { query, applied: { ...query, startTime: '2026-10-01T00:00:00', endTime: '2026-10-06T23:59:59' }, dates: ['2026-10-01T00:00:00', '2026-10-06T23:59:59'], page: 3, size: 50 });
    const host = await mount(section);
    expect(state.page.mock.calls[0]![1]).toMatchObject({ ...query, page: 3, size: 50 });
    expect(host.querySelector<HTMLInputElement>('input[placeholder="field.username"]')!.value).toBe('saved-member');
    if (section === 'withdrawals') {
      expect(host.querySelector<HTMLInputElement>('input[placeholder="v11.payment.gatewayReference"]')!.value).toBe('saved-gateway');
      expect(host.textContent).toContain('v27.ignored');
    }
    if (section === 'ledger') expect(host.textContent).toContain('v35Ledger.type.WITHDRAW_APPROVED');
    if (section === 'deposits') expect(host.textContent).toContain('v51Notifications.collection.notCollected');
    // ElementPlus真实日期clear事件把v-model置null，持久化不能因此抛错。
    const clearDate = host.querySelector<HTMLElement>('.el-range__close-icon');
    expect(clearDate).not.toBeNull(); await click(clearDate!);
    expect(pageFilters.get(key)!.dates).toEqual([]);
    const beforeReset = state.page.mock.calls.length;
    await click(button(host, 'common.reset'));
    expect(state.page).toHaveBeenCalledTimes(beforeReset + 1);
    const lastQuery = state.page.mock.lastCall![1];
    for (const field of Object.keys(query)) expect(lastQuery[field]).toBeUndefined();
    expect(lastQuery).toMatchObject({ page: 1, size: 50, startTime: undefined, endTime: undefined });
    expect(host.querySelector<HTMLInputElement>('input[placeholder="field.username"]')!.value).toBe('');
    for (const field of Object.keys(query)) expect(pageFilters.get(key)!.applied[field]).toBeUndefined();
    cleanup.pop()!(); await mount(section);
    for (const field of Object.keys(query)) expect(state.page.mock.lastCall![1][field]).toBeUndefined();
  });

  it('真实ElSelect未选定检索文字随点击重置失焦关闭而清空，无需额外重置广播', async () => {
    const host = await mount('ledger');
    const selectRoot = [...host.querySelectorAll<HTMLElement>('.el-select')].find(item => item.textContent?.includes('v35Ledger.detailType'));
    const select = selectRoot!.querySelector<HTMLInputElement>('input')!;
    select.focus(); select.click(); select.value = 'WITHDRAW'; select.dispatchEvent(new Event('input', { bubbles: true })); await flush();
    expect(select.value).toBe('WITHDRAW');
    await click(button(host, 'common.reset'));
    expect(select.value).toBe('');
    expect(state.page.mock.lastCall![1].detailType).toBeUndefined();
    // 重新展开可看到之前检索之外的选项，组件内部filter也已清除。
    select.focus(); select.click(); await flush();
    const option = [...document.querySelectorAll<HTMLElement>('.el-select-dropdown__item')].find(item => item.textContent === 'v35Ledger.type.TOKEN_INCOME');
    expect(option).toBeDefined(); expect(option!.style.display).not.toBe('none');
  });
});
