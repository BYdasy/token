import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, inject, nextTick, provide, reactive, ref } from 'vue';
import type { Component } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import userZh from '../../docs/i18n/current-user-zh.json';

const state = vi.hoisted(() => ({
  list: vi.fn(), overview: vi.fn(), addresses: vi.fn(), policy: vi.fn(), remove: vi.fn(), confirm: vi.fn(),
  session: null as any, locale: null as any, channels: null as any,
}));
const translate = (key: string, params: Record<string, unknown> = {}) => Object.entries(params)
  .reduce((text, [name, value]) => text.replaceAll(`{${name}}`, String(value)), (userZh as Record<string, string>)[key] || key);
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string, params?: Record<string, unknown>) => translate(key, params), te: () => false, locale: state.locale }) }));
vi.mock('@/stores/session', () => ({ useSession: () => state.session }));
vi.mock('@/locales', () => ({ i18n: { global: { t: (key: string) => translate(key) } } }));
vi.mock('@/api', () => ({ api: { list: state.list, addresses: state.addresses, addressPolicy: state.policy, deleteAddress: state.remove } }));
vi.mock('@/api/team', () => ({ fetchTeamOverview: state.overview }));
vi.mock('vant', () => ({ showConfirmDialog: state.confirm, showSuccessToast: vi.fn(), showFailToast: vi.fn() }));
vi.mock('@/utils/format', () => ({ money: (value: unknown) => value == null ? '—' : String(value), date: (value: unknown) => String(value || '—'), copyText: vi.fn(), validPassword: () => true }));
vi.mock('@/composables/useCurrencies', () => ({ useCurrencies: () => ({ channels: state.channels, task: { loading: ref(false), error: ref('') }, refresh: vi.fn() }) }));
vi.mock('@/components/VipAvatar.vue', () => ({ default: defineComponent({ render: () => h('i') }) }));
vi.mock('@/components/InlineCopyButton.vue', () => ({ default: defineComponent({ render: () => h('i') }) }));
import RecordsView from '../src/views/RecordsView.vue';
import TeamOverviewPanel from '../src/components/TeamOverviewPanel.vue';
import AddressPanel from '../src/components/wallet/AddressPanel.vue';
import BusinessRecords from '../src/components/BusinessRecords.vue';
import { parentPage } from '../src/router/navigation';

const apps: ReturnType<typeof createApp>[] = [];
async function settle() { for (let i = 0; i < 7; i++) { await Promise.resolve(); await nextTick(); } }
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(yes => { resolve = yes; }); return { promise, resolve }; }
async function mount(component: Component, path = '/', props = {}) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }] });
  await router.push(path); await router.isReady();
  const view = document.createElement('div'); document.body.append(view);
  const app = createApp(component, props); app.use(router); app.config.globalProperties.$t = translate;
  app.component('van-popup', defineComponent({ props: ['show'], setup: (props, { slots }) => () => props.show ? h('section', slots.default?.()) : null }));
  app.component('van-popover', defineComponent({ props: ['show', 'actions'], emits: ['update:show', 'select'], setup: (props, { slots, emit }) => () => h('div', [h('span', { onClick: () => emit('update:show', !props.show) }, slots.reference?.()), props.show ? h('div', { role: 'listbox' }, props.actions.map((action: any) => h('button', { role: 'option', onClick: () => emit('select', action) }, action.text))) : null]) }));
  app.component('van-button', defineComponent({ props: ['loading', 'disabled'], setup: (props, { slots }) => () => h('button', { disabled: props.loading || props.disabled }, slots.default?.()) }));
  app.component('van-tabs', defineComponent({ props: ['active'], emits: ['update:active'], setup: (_, { slots, emit }) => { provide('select-review-tab', (value: string) => emit('update:active', value)); return () => h('nav', slots.default?.()); } }));
  app.component('van-tab', defineComponent({ props: ['name', 'title'], setup: props => { const select = inject<(value: string) => void>('select-review-tab')!; return () => h('button', { 'data-tab': props.name, onClick: () => select(props.name) }, props.title); } }));
  app.component('van-empty', defineComponent({ props: ['description'], setup: props => () => h('p', props.description) }));
  for (const name of ['van-icon', 'van-loading', 'van-progress', 'van-pagination']) app.component(name, defineComponent({ render: () => h('i') }));
  apps.push(app); app.mount(view); await settle(); return { view, router };
}
function team(depth = 3, selected: number | null = null, complete = true) {
  return {
    supportedDepth: depth, timeZone: 'UTC', fromDate: '2026-10-01', toDate: '2026-10-06',
    rewardValuation: { complete, unpricedCount: complete ? 0 : 1 },
    summary: { memberCount: 3, directCount: 2, totalInvites: 3, todayNewMembers: 1, periodNewMembers: 2, totalRewardToken: '12.34567890', todayRewardToken: '1.25', periodRewardToken: '4.5678', totalInvestmentToken: '99999' },
    structure: [{ depth: 1, memberCount: 2 }, { depth: 2, memberCount: 1 }],
    trend: [{ day: '2026-10-06', newMembers: 1, rewardToken: '4.5678', investmentToken: '99999' }],
    members: { records: [], total: 0, page: 1, pageSize: 10, depth: selected },
  };
}
beforeEach(() => {
  vi.resetAllMocks(); state.locale = ref('zh'); state.session = reactive({ token: 'account-a', member: { hasPayPassword: true }, config: { systemTimeZone: 'UTC', defaultCurrency: 'USDT' } });
  state.list.mockResolvedValue({ records: [], total: 0 }); state.overview.mockResolvedValue(team());
  state.addresses.mockResolvedValue([{ id: 1, label: '收款地址', address: 'local-fixture-address', currency: 'USDT', network: 'TRC20' }]);
  state.policy.mockResolvedValue({ withdrawalAddressDeleteEnabled: true }); state.remove.mockResolvedValue(undefined); state.confirm.mockResolvedValue(undefined);
  state.channels = ref([{ currency: 'USDT', network: 'TRC20', assetType: 'CRYPTO', requiredFields: [] }]);
});
afterEach(() => { apps.splice(0).forEach(app => app.unmount()); document.body.innerHTML = ''; });

describe('确认稿用户端展示与请求边界', () => {
  it('旧收益记录链接归并到流水，只加载一次且不带收入分类，三栏仍在', async () => {
    const { view, router } = await mount(RecordsView, '/records?tab=income');
    expect(router.currentRoute.value.query.tab).toBe('income');
    expect(state.list).toHaveBeenCalledTimes(1);
    expect(state.list.mock.calls[0]!.slice(0, 4)).toEqual(['ledger', 1, 20, {}]);
    expect([...view.querySelectorAll('[data-tab]')].map(el => el.getAttribute('data-tab'))).toEqual(['deposits', 'withdrawals', 'ledger']);
    expect(view.querySelectorAll('.record-filter')).toHaveLength(2);
  });

  it('紧凑钱包就地下拉保留全部及三钱包，每次筛选只发一次请求，充提不携带流水钱包筛选', async () => {
    const { view } = await mount(RecordsView, '/records');
    view.querySelector<HTMLButtonElement>('[data-record-filter="wallet"]')!.click(); await settle();
    expect(view.querySelectorAll('.record-filter-options [role=option]')).toHaveLength(4);
    view.querySelector<HTMLButtonElement>('[data-filter-value="PROJECT"]')!.click(); await settle();
    expect(state.list).toHaveBeenCalledTimes(2);
    expect(state.list.mock.calls[1]!.slice(0, 4)).toEqual(['ledger', 1, 20, { walletType: 'PROJECT' }]);
    view.querySelector<HTMLButtonElement>('[data-tab=deposits]')!.click(); await settle();
    expect(state.list).toHaveBeenCalledTimes(3);
    expect(state.list.mock.calls[2]!.slice(0, 4)).toEqual(['deposits', 1, 20, {}]);
    expect(view.querySelector('[data-record-filter="status"]')!.getAttribute('aria-label')).toBe(userZh['user.common.status']);
  });

  it('钱包切换撤销旧流水请求，迟到响应不会进入新钱包', async () => {
    const old = deferred<any>(); state.list.mockReturnValueOnce(old.promise);
    const { view } = await mount(RecordsView, '/records');
    view.querySelector<HTMLButtonElement>('[data-record-filter="wallet"]')!.click(); await settle();
    view.querySelector<HTMLButtonElement>('[data-filter-value="EARNINGS"]')!.click(); await settle();
    expect((state.list.mock.calls[0]![4] as AbortSignal).aborted).toBe(true);
    old.resolve({ records: [{ id: 9, remark: '旧钱包记录', amount: '888', currency: 'USDT' }], total: 1 }); await settle();
    expect(view.textContent).not.toContain('旧钱包记录');
  });

  it('团队使用本人返点新字段，1至N含零人数层级，不展示业绩或4+合并项', async () => {
    const { view } = await mount(TeamOverviewPanel);
    expect(view.textContent).toContain('累计收益'); expect(view.textContent).toContain('12.34567890');
    expect(view.textContent).not.toContain('99999'); expect(view.querySelector('.ui2-team-performance')).toBeNull();
    expect(view.textContent).toContain('1.25');
    expect(view.querySelectorAll('.vora-team-level-counts > div')).toHaveLength(3);
    expect(view.querySelector('.vora-team-level-counts')?.textContent).toContain('3 级成员');
    expect(view.querySelector('input[type=date]')).toBeNull();
    expect(view.querySelectorAll('.vora-team-periods button')).toHaveLength(3);
    view.querySelector<HTMLButtonElement>('.vora-team-depth')!.click(); await settle();
    expect([...view.querySelectorAll('[role=option]')].map(el => el.textContent)).toEqual([userZh['user.ui2.team.allLevels'], '1 级成员', '2 级成员', '3 级成员']);
    state.overview.mockResolvedValueOnce(team(2, null));
    view.querySelectorAll<HTMLButtonElement>('[role=option]')[3]!.click(); await settle();
    expect(state.overview).toHaveBeenCalledTimes(2); expect(state.overview.mock.calls[1]![0].depth).toBe(3);
    expect(view.querySelector('.vora-team-depth')?.textContent).toContain(userZh['user.ui2.team.allLevels']);
    expect(view.querySelectorAll('.vora-team-level-counts > div')).toHaveLength(2);
  });

  it('历史返点缺固定计价时隐藏不完整收益金额，人数趋势及当前层级仍可查看', async () => {
    state.overview.mockResolvedValue(team(5, null, false));
    const { view } = await mount(TeamOverviewPanel);
    expect(view.textContent).toContain(userZh['user.v44.team.unpricedRewards']);
    expect(view.querySelector('.vora-team-rewards')?.textContent).not.toContain('12.34567890');
    expect(view.querySelector('.vora-team-chart')).not.toBeNull();
    expect(view.querySelectorAll('.vora-team-level-counts > div')).toHaveLength(5);
    view.querySelector<HTMLButtonElement>('button[data-trend=rewards]')!.click(); await settle();
    expect(view.querySelector('.vora-team-chart')).toBeNull();
    expect(view.querySelector('.vora-team-chart-unpriced')?.textContent).toContain(userZh['user.v44.team.unpricedRewards']);
    view.querySelector<HTMLButtonElement>('button[data-trend=invites]')!.click(); await settle();
    expect(view.querySelector('.vora-team-chart')).not.toBeNull();
  });

  it('团队收益趋势读取真实返点，切换类型不发请求且共享7/30/90天区间', async () => {
    const { view } = await mount(TeamOverviewPanel);
    expect(view.querySelector('.vora-team-chart rect title')?.textContent).toContain('1');
    view.querySelector<HTMLButtonElement>('button[data-trend=rewards]')!.click(); await settle();
    expect(state.overview).toHaveBeenCalledTimes(1);
    expect(view.querySelector('.vora-team-chart')?.getAttribute('data-trend')).toBe('rewards');
    expect(view.querySelector('.vora-team-chart polyline')).not.toBeNull();
    expect(view.querySelector('.vora-team-chart circle title')?.textContent).toContain('4.5678 token');
    expect(view.querySelector('.vora-team-chart')?.textContent).not.toContain('99999');
    const periods = view.querySelectorAll<HTMLButtonElement>('.vora-team-periods button');
    for (const [index, days] of [[1, 30], [2, 90]]) {
      periods[index!]!.click(); await settle();
      const query = state.overview.mock.calls.at(-1)![0];
      expect((Date.parse(query.toDate) - Date.parse(query.fromDate)) / 86400000).toBe(days! - 1);
      expect(view.querySelector('.vora-team-chart')?.getAttribute('data-trend')).toBe('rewards');
    }
    expect(state.overview).toHaveBeenCalledTimes(3);
  });

  it('团队收益明细缺失不补零，真实零收益正常绘制', async () => {
    const incomplete = team(); incomplete.trend[0]!.rewardToken = '';
    state.overview.mockResolvedValue(incomplete);
    const { view } = await mount(TeamOverviewPanel);
    view.querySelector<HTMLButtonElement>('button[data-trend=rewards]')!.click(); await settle();
    expect(view.querySelector('.vora-team-chart')).toBeNull();
    const zero = team(); zero.trend[0]!.rewardToken = '0';
    state.overview.mockResolvedValue(zero);
    view.querySelectorAll<HTMLButtonElement>('.vora-team-periods button')[1]!.click(); await settle();
    expect(view.querySelector('.vora-team-chart circle title')?.textContent).toContain('0 token');
    expect(view.querySelector('.vora-team-chart-unpriced')).toBeNull();
  });

  it('地址页并行读实时开关，关闭时隐藏删除并保留复制，缺字段兼容开启', async () => {
    state.policy.mockResolvedValueOnce({ withdrawalAddressDeleteEnabled: false });
    const { view } = await mount(AddressPanel);
    expect(state.addresses).toHaveBeenCalledTimes(1); expect(state.policy).toHaveBeenCalledTimes(1);
    expect(view.querySelector('.danger')).toBeNull(); expect(view.querySelector('.address-copy')?.getAttribute('aria-label')).toBe(userZh['user.common.copy']);
    state.policy.mockResolvedValueOnce({}); state.session.token = 'account-b'; await settle();
    expect(view.querySelector('.danger')).not.toBeNull();
  });

  it('删除确认未完成时切账号，不发送旧账号的删除请求', async () => {
    const confirm = deferred<void>(); state.confirm.mockReturnValueOnce(confirm.promise);
    const { view } = await mount(AddressPanel); view.querySelector<HTMLButtonElement>('.danger')!.click(); await settle();
    state.session.token = 'account-b'; await settle(); confirm.resolve(); await settle();
    expect(state.remove).not.toHaveBeenCalled();
  });

  it('流水保持固定总变化和可靠业务单号，奖励取已匹配凭据，未知LG明确称流水号', async () => {
    const { view } = await mount(BusinessRecords, '/', { kind: 'ledger', records: [
      { id: 1, amount: '0', businessAmount: '-30', currency: 'TRX', accountingBusinessAmount: '-10', businessOrderNo: 'WD-verified', ledgerNo: 'LG-1', walletType: 'EARNINGS' },
      { id: 2, amount: '3', currency: 'USDT', detailStatus: 'MATCHED', detailFacts: { rewardOrderNo: 'RW-reward', sourceOrderNo: 'LG-must-not-be-order' }, ledgerNo: 'LG-2' },
      { id: 3, amount: '5', currency: 'BTC', ledgerNo: 'LG-history' },
    ] });
    expect(view.textContent).toContain('-10 token'); expect(view.textContent).toContain('WD-verified');
    expect(view.textContent).toContain('RW-reward'); expect(view.textContent).not.toContain('LG-must-not-be-order');
    expect(view.textContent).toContain('流水号'); expect(view.textContent).toContain('LG-history');
    expect(view.textContent).toContain(userZh['user.v22Accounting.unpriced']);
    expect(view.querySelectorAll('details')).toHaveLength(3);
  });

  it('直接进入公司详情的导航返回上级目录，其余已有返回路径保持', () => {
    expect(parentPage('/company-files/12')).toBe('/company-files');
    expect(parentPage('/announcements/12')).toBe('/announcements');
    expect(parentPage('/auto-records')).toBe('/token');
  });
});
