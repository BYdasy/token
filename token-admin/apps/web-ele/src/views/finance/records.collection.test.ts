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
import Records from './records.vue';

const cleanup: Array<() => void> = [];
const flush = async () => { for (let index = 0; index < 10; index++) { await Promise.resolve(); await nextTick(); } };
beforeEach(() => {
  vi.resetAllMocks();pageFilters.clear();pageFilters.setOwner('finance-collection-test');
  state.access=reactive({ accessToken: 'admin' });state.locale=ref('zh');
  state.page.mockResolvedValue({ records: [{ id: 1, uid: 101, username: 'member', amount: '1', currency: 'USDT', network: 'TRON', recordSource: 'MCAG',
    status: 'APPROVED', paymentStatus: 'MCAG_VERIFIED', collectionState: 'NOT_COLLECTED', collectionStatus: 'WAITING', collectionReason: 'INSUFFICIENT_NATIVE_GAS' }], total: 1 });
  state.dictionaries.mockResolvedValue({ scope: { global: false }, currencies: [], groups: [] });
});
afterEach(() => {cleanup.splice(0).forEach(dispose=>dispose());pageFilters.clear();});

async function mount(section: 'deposits' | 'withdrawals') {
  const path=`/finance/${section}`;
  const router=createRouter({ history:createMemoryHistory(), routes:[{path,component:Records,props:{section}}] });
  await router.push(path);await router.isReady();
  const host=document.createElement('div');document.body.append(host);
  const app=createApp({render:()=>h(RouterView)});app.use(router);app.directive('loading',{});app.mount(host);
  cleanup.push(()=>{app.unmount();host.remove();});await flush();return host;
}

describe('充值列表归集状态', () => {
  it('在审核列现有两个状态标签旁显示归集标签与对应原因，无额外网关查询', async () => {
    const host=await mount('deposits');
    const group=host.querySelector('.el-table__body .finance-review-tags');
    expect(group?.querySelectorAll('.el-tag')).toHaveLength(4);
    expect(group?.textContent).toContain('Not collected');
    expect(group?.textContent).toContain('MCAG_VERIFIED');
    const reason=group?.querySelector('.finance-collection-reason-tag');
    expect(reason?.classList.contains('el-tag')).toBe(true);
    expect(reason?.textContent).toContain('Awaiting collection');
    expect(reason?.textContent).toContain('Fee funding wallet has insufficient available balance');
    expect(reason?.textContent).not.toContain('threshold');
    expect(state.page).toHaveBeenCalledTimes(1);expect(state.dictionaries).toHaveBeenCalledTimes(1);
  });
  it('已归集且无原因时只显示一个归集标签，详情仍保留完整状态', async () => {
    state.page.mockResolvedValue({ records: [{ id: 1, uid: 101, username: 'member', amount: '1', currency: 'USDT', network: 'TRON', recordSource: 'MCAG',
      status: 'APPROVED', paymentStatus: 'MCAG_VERIFIED', collectionState: 'COLLECTED', collectionStatus: 'SUCCEEDED', collectionReason: '' }], total: 1 });
    const host=await mount('deposits');
    const group=host.querySelector('.el-table__body .finance-review-tags');
    expect(group?.querySelectorAll('.el-tag')).toHaveLength(3);
    expect(group?.textContent).toContain('Collected');
    expect(group?.textContent).not.toContain('Collection completed');
    expect(group?.querySelector('.finance-collection-reason-tag')).toBeNull();
    const button=[...host.querySelectorAll<HTMLButtonElement>('.el-table__body button')].find(item=>item.textContent?.includes('common.details'));
    expect(button).toBeDefined();button!.click();await flush();
    expect(document.body.querySelector('.el-dialog')?.textContent).toContain('Collection completed');
  });
  it('同一充值详情展示相同归集事实和原因', async () => {
    const host=await mount('deposits');
    const button=[...host.querySelectorAll<HTMLButtonElement>('.el-table__body button')].find(item=>item.textContent?.includes('common.details'));
    expect(button).toBeDefined();button!.click();await flush();
    const dialog=document.body.querySelector('.el-dialog');
    expect(dialog?.textContent).toContain('Collection status');
    expect(dialog?.textContent).toContain('Not collected');
    expect(dialog?.textContent).toContain('Fee funding wallet has insufficient available balance');
  });
  it('已入账但未达归集金额的充值在列表和详情展示权威原因', async () => {
    state.page.mockResolvedValue({ records: [{ id: 1, uid: 101, username: 'member', amount: '3.5', currency: 'USDT', network: 'TRON', recordSource: 'MCAG',
      status: 'APPROVED', paymentStatus: 'MCAG_VERIFIED', collectionState: 'NOT_COLLECTED', collectionStatus: null, collectionReason: 'BELOW_SWEEP_THRESHOLD' }], total: 1 });
    const host=await mount('deposits');
    const group=host.querySelector('.el-table__body .finance-review-tags');
    expect(group?.textContent).toContain('MCAG_VERIFIED');
    expect(group?.textContent).toContain('Not collected');
    expect(group?.querySelector('.finance-collection-reason-tag')?.textContent).toBe('Minimum collection amount not reached');
    const button=[...host.querySelectorAll<HTMLButtonElement>('.el-table__body button')].find(item=>item.textContent?.includes('common.details'))!;
    button.click();await flush();
    expect(document.body.querySelector('.el-dialog')?.textContent).toContain('Minimum collection amount not reached');
    expect(state.page).toHaveBeenCalledTimes(1);
  });
  it('提款沿用原状态，不展示充值归集标签', async () => {
    const host=await mount('withdrawals');
    expect(host.querySelector('.finance-collection-reason')).toBeNull();
    expect(host.querySelector('.finance-collection-reason-tag')).toBeNull();
    expect(host.querySelector('.finance-review-tags')?.textContent).not.toContain('Not collected');
    expect(host.querySelector('.finance-review-tags--vertical')).not.toBeNull();
    expect(host.querySelector('[aria-label="v51Notifications.collection.status"]')).toBeNull();
  });
  it.each(['COLLECTED', 'NOT_COLLECTED'])('归集筛选%s仅搜索时提交，并随恢复快照保留', async value => {
    const host=await mount('deposits');
    const input=host.querySelector<HTMLInputElement>('input[aria-label="v51Notifications.collection.status"]')!;
    expect(input).not.toBeNull();input.click();await flush();
    const key=value==='COLLECTED'?'collected':'notCollected';
    const option=[...document.querySelectorAll<HTMLElement>('.el-select-dropdown__item')].find(item=>item.textContent===`v51Notifications.collection.${key}`)!;
    expect(option).toBeDefined();option.click();await flush();
    expect(state.page).toHaveBeenCalledTimes(1);
    [...host.querySelectorAll<HTMLButtonElement>('button')].find(item=>item.textContent?.trim()==='common.search')!.click();await flush();
    expect(state.page).toHaveBeenCalledTimes(2);
    expect(state.page.mock.lastCall![0]).toBe('deposits');
    expect(state.page.mock.lastCall![1]).toMatchObject({collectionState:value,page:1});
    const stateKey=JSON.stringify(['/finance/deposits','deposits',{}]);
    expect(pageFilters.get(stateKey)?.applied.collectionState).toBe(value);
    cleanup.pop()!();const restored=await mount('deposits');
    expect(restored.textContent).toContain(`v51Notifications.collection.${key}`);
    expect(state.page.mock.lastCall![1].collectionState).toBe(value);
  });
});
