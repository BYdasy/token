import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, inject, nextTick, provide, reactive, ref } from 'vue';
const state = vi.hoisted(() => ({ list: vi.fn(), route: null as any, session: null as any, locale: null as any }));
vi.mock('@/api', () => ({ api: { list: state.list } }));
vi.mock('@/stores/session', () => ({ useSession: () => state.session }));
vi.mock('vue-router', () => ({ useRoute: () => state.route }));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ locale: state.locale, t: (key: string) => key }) }));
vi.mock('@/components/PageState.vue', () => ({ default: defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) }) }));
vi.mock('@/components/BusinessRecords.vue', () => ({ default: defineComponent({ props: ['records'], setup: props => () => h('div', { 'data-records': '' }, JSON.stringify(props.records)) }) }));
import RecordsView from '../src/views/RecordsView.vue';
const apps: ReturnType<typeof createApp>[] = [];
async function flush() { for (let i = 0; i < 5; i++) { await nextTick(); await Promise.resolve(); } }
async function mount() {
  const host = document.createElement('div');
  const app = createApp(RecordsView);
  app.config.globalProperties.$t = (key: string) => key;
  app.component('van-icon', defineComponent({ render: () => h('span') }));
  app.component('van-popup', defineComponent({ props: ['show', 'position'], setup: (props, { slots }) => () => props.show ? h('section', { 'data-position': props.position }, slots.default?.()) : null }));
  app.component('van-tabs', defineComponent({ emits: ['update:active'], setup: (_, { emit, slots }) => {
    provide('selectTab', (value: string) => emit('update:active', value)); return () => h('nav', slots.default?.());
  } }));
  app.component('van-tab', defineComponent({ props: ['name', 'title'], setup: props => {
    const select = inject<(value: string) => void>('selectTab')!;
    return () => h('button', { 'data-tab': props.name, onClick: () => select(props.name) }, props.title);
  } }));
  app.mount(host); apps.push(app); await flush(); return host;
}
async function select(host: HTMLElement, label: string, value: string) {
  const trigger = host.querySelector<HTMLButtonElement>(`button[aria-label^="${label}: "]`)!;
  trigger.click(); await flush();
  expect(host.querySelector('section[data-position]')).toBeNull();
  expect(host.querySelector('.record-filter-field [role="listbox"]')).not.toBeNull();
  if (label === 'user.common.status') expect([...host.querySelectorAll('[data-filter-value]')].map(item => item.getAttribute('data-filter-value'))).toEqual(['', 'SUCCESS', 'NOT_SUCCESS']);
  const option = host.querySelector<HTMLButtonElement>(`[role="option"][data-filter-value="${value}"]`)!;
  const selectedLabel = option.querySelector('span')!.textContent;
  option.click(); await flush();
  expect(trigger.getAttribute('aria-label')).toBe(`${label}: ${selectedLabel}`);
}
beforeEach(() => {
  vi.resetAllMocks();
  state.route = reactive({ query: { tab: 'ledger', walletType: 'PROJECT' } });
  state.session = reactive({ token: 'member-a' }); state.locale = ref('zh');
  state.list.mockResolvedValue({ records: [{ id: 1, amount: '2' }], total: 45 });
});
afterEach(() => apps.splice(0).forEach(app => app.unmount()));
describe('用户资金记录分页筛选', () => {
  it('钱包直达和收入支出均传给服务端，分页保留筛选且变更条件回第一页', async () => {
    const host = await mount();
    expect(state.list).toHaveBeenLastCalledWith('ledger', 1, 20, { walletType: 'PROJECT' }, expect.any(AbortSignal), false);
    await select(host, 'user.refine.records.direction', 'OUT');
    expect(state.list).toHaveBeenLastCalledWith('ledger', 1, 20, { walletType: 'PROJECT', direction: 'OUT' }, expect.any(AbortSignal), false);
    host.querySelectorAll<HTMLButtonElement>('.vora-pagination button')[1]!.click(); await flush();
    expect(state.list).toHaveBeenLastCalledWith('ledger', 2, 20, { walletType: 'PROJECT', direction: 'OUT' }, expect.any(AbortSignal), false);
    await select(host, 'user.wallet.walletType', 'EARNINGS');
    expect(state.list).toHaveBeenLastCalledWith('ledger', 1, 20, { walletType: 'EARNINGS', direction: 'OUT' }, expect.any(AbortSignal), false);
    expect(state.route.query).toEqual({ tab: 'ledger', walletType: 'PROJECT' });
  });
  it('充值提款状态在查询中生效，不把流水条件带到支付接口，旧响应不能覆盖新状态', async () => {
    const host = await mount();
    host.querySelector<HTMLButtonElement>('[data-tab="withdrawals"]')!.click(); await flush();
    let resolveOld!: (data: unknown) => void;
    state.list.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }));
    await select(host, 'user.common.status', 'NOT_SUCCESS');
    expect(state.list).toHaveBeenLastCalledWith('withdrawals', 1, 20, { statusGroup: 'NOT_SUCCESS' }, expect.any(AbortSignal), false);
    const oldSignal = state.list.mock.lastCall![4] as AbortSignal;
    state.list.mockResolvedValueOnce({ records: [{ id: 2, status: 'APPROVED' }], total: 1 });
    await select(host, 'user.common.status', 'SUCCESS');
    expect(oldSignal.aborted).toBe(true);
    expect(state.list).toHaveBeenLastCalledWith('withdrawals', 1, 20, { statusGroup: 'SUCCESS' }, expect.any(AbortSignal), false);
    resolveOld({ records: [{ id: 99, status: 'PENDING' }], total: 99 }); await flush();
    expect(host.querySelector('[data-records]')!.textContent).toContain('APPROVED');
    expect(host.querySelector('[data-records]')!.textContent).not.toContain('99');
  });
});
