import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';
import type { RewardCenter, RewardRecord, VipRewardRule } from '../src/api/rewards';
import zh from '../../docs/i18n/current-user-zh.json';
import en from '../../docs/i18n/current-user-en.json';

const state = vi.hoisted(() => ({
  center: vi.fn(), records: vi.fn(), translate: vi.fn(),
  session: null as any, locale: null as any, unit: null as any,
}));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: state.translate, locale: state.locale }) }));
vi.mock('@/api/rewards', () => ({ fetchRewardCenter: state.center, fetchRewardRecords: state.records }));
vi.mock('@/stores/session', () => ({ useSession: () => state.session }));
vi.mock('@/utils/format', () => ({
  money: (value: unknown) => String(value ?? '—'), date: (value: unknown) => String(value),
}));
vi.mock('@/composables/useToken', () => ({ useToken: () => ({
  tokenAmount: (value: unknown) => String(value ?? '—'),
  tokenTotal: (rows: { amount: string }[]) => rows.reduce((total, row) => total + Number(row.amount), 0),
  unitName: state.unit,
  withUnit: (text: string) => text.replace(/\bToken\b/g, () => state.unit.value),
}) }));
import InvitationRewardCenter from '../src/components/InvitationRewardCenter.vue';

const apps: ReturnType<typeof createApp>[] = [];
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function rule(depth: number, overrides: Partial<VipRewardRule> = {}): VipRewardRule {
  return {
    depth, firstDepositEnabled: false, firstDepositRate: '15',
    depositEnabled: false, depositRate: '4', vipEnabled: false, vipRate: '3',
    productEnabled: false, productRate: '2', ...overrides,
  };
}
function record(orderNo: string): RewardRecord {
  return { orderNo, sourceKind: 'VIP_FIRST_DEPOSIT', currency: 'USDT', amount: '15', depth: 1, createdAt: '2026-09-22T00:00:00Z', sourceUsername: orderNo, isVirtual: false };
}
function center(name: string, overrides: Partial<RewardCenter> = {}): RewardCenter {
  return {
    depositRules: [], effectiveRules: [], vipRules: [rule(1, { firstDepositEnabled: true })],
    level: 1, levelName: name, teamDepth: 5, isTest: false,
    totals: [], records: { records: [], total: 0 }, ...overrides,
  };
}
async function flush() {
  await Promise.resolve();
  await nextTick();
  await Promise.resolve();
  await nextTick();
}
async function mount() {
  const container = document.createElement('div');
  const app = createApp(InvitationRewardCenter);
  app.config.globalProperties.$t = state.translate;
  app.component('van-icon', defineComponent({ render: () => h('span') }));
  app.component('van-loading', defineComponent({ setup: (_, { slots }) => () => h('div', { 'data-loading': true }, slots.default?.()) }));
  app.component('van-empty', defineComponent({ props: ['description'], setup: (props) => () => h('div', { 'data-error': true }, props.description) }));
  app.component('van-button', defineComponent({ setup: (_, { slots }) => () => h('button', slots.default?.()) }));
  app.component('van-progress', defineComponent({ render: () => h('div') }));
  app.component('RouterLink', defineComponent({ setup: (_, { slots }) => () => h('a', slots.default?.()) }));
  apps.push(app); app.mount(container); await flush();
  return container;
}


beforeEach(() => {
  vi.resetAllMocks();
  state.session = reactive({ token: 'account-a' });
  state.locale = ref('zh');
  state.unit = ref('Token');
  state.translate.mockImplementation((key: string, params: Record<string, unknown> = {}) => {
    const messages: Record<string, string> = state.locale.value === 'zh' ? zh : en;
    return Object.entries(params).reduce((value, [name, replacement]) => value.replaceAll(`{${name}}`, String(replacement)), messages[key] || key);
  });
  state.center.mockResolvedValue(center('current-level'));
  state.records.mockResolvedValue({ records: [], total: 0 });
});
afterEach(() => { apps.splice(0).forEach((app) => app.unmount()); });

describe('邀请返点展示与请求隔离', () => {
  it('显示单位变化只替换文案，不改变到账金额或返点百分比', async () => {
    state.unit.value = 'VORA';
    state.center.mockResolvedValue(center('VIP1', {
      vipRules: [rule(1, { vipEnabled: true, requireEffectiveMember: true, effectiveDepositThreshold: '10' })],
      totals: [{ currency: 'USDT', amount: '12.5' }],
    }));
    const container = await mount();
    expect(container.textContent).toContain('VORA 收益');
    expect(container.textContent).toContain('达到 10 VORA');
    expect(container.querySelector('.reward-totals')?.textContent).toContain('12.5 VORA');
    expect(container.querySelector('.vip-rebate-values dd')?.textContent).toBe('3%');
  });
  it('只展示开启的四类返点，各项条件只出现在适用来源，底部不再展示奖励记录', async () => {
    state.center.mockResolvedValue(center('VIP1', {
      vipRules: [
        rule(1, { firstDepositEnabled: true, depositCapByCompute: true, requireEffectiveMember: true, effectiveDepositThreshold: '10' }),
        rule(2, { depositEnabled: true, depositCapByCompute: true }),
        rule(3, { vipEnabled: true, vipRequireComputeBalance: true }),
        rule(4, { productEnabled: true, depositCapByCompute: true, vipRequireComputeBalance: true }),
        rule(5),
      ],
      records: { records: [record('first-deposit-record')], total: 1 },
    }));
    const container = await mount();
    const cards = [...container.querySelectorAll('.vip-depth-card')];
    expect(cards).toHaveLength(4);
    expect(cards.map((card) => card.querySelector('.vip-rebate-values dt')?.textContent)).toEqual(['首次充值', '累计充值', 'Token 收益', '项目收益']);
    expect(cards.every((card) => card.querySelectorAll('.vip-rebate-values dt').length === 1)).toBe(true);
    expect(cards[0]!.textContent).toContain('达到 10 Token');
    expect(cards[0]!.textContent).toContain(zh['user.v21Rewards.depositCap']);
    expect(cards[1]!.textContent).toContain(zh['user.v21Rewards.depositHelp']);
    expect(cards[2]!.textContent).toContain(zh['user.v21Rewards.vipBalance']);
    expect(cards[2]!.textContent).not.toContain(zh['user.v21Rewards.depositCap']);
    expect(cards[3]!.querySelector('.vip-conditions')).toBeNull();
    expect(container.querySelector('.reward-history')).toBeNull();
    expect(container.textContent).not.toContain('first-deposit-record');
    expect(state.records).not.toHaveBeenCalled();
  });

  it('切换账号与语言立即撤销旧中心请求，迟到成功或失败不能回填或停止新加载', async () => {
    const oldAccount = deferred<RewardCenter>(), oldLanguage = deferred<RewardCenter>(), latest = deferred<RewardCenter>();
    state.center.mockImplementationOnce(() => oldAccount.promise)
      .mockImplementationOnce(() => oldLanguage.promise)
      .mockImplementationOnce(() => latest.promise);
    const container = await mount();
    state.session.token = 'account-b';
    expect((state.center.mock.calls[0]![0] as AbortSignal).aborted).toBe(true);
    expect(state.center).toHaveBeenCalledTimes(2);
    state.locale.value = 'en';
    expect((state.center.mock.calls[1]![0] as AbortSignal).aborted).toBe(true);
    expect(state.center).toHaveBeenCalledTimes(3);
    oldAccount.resolve(center('old-account-data'));
    oldLanguage.reject(new Error('old-language-error'));
    await flush();
    expect(container.textContent).not.toContain('old-account-data');
    expect(container.textContent).not.toContain('old-language-error');
    expect(container.querySelector('[data-loading]')).not.toBeNull();
    latest.resolve(center('new-account-english'));
    await flush();
    expect(container.textContent).toContain('new-account-english');
    expect(container.textContent).toContain('First deposit');
    expect(container.querySelector('[data-loading]')).toBeNull();
  });

  it('奖励中心有历史记录时也不发送专属记录分页请求，切换账号语言只刷新规则', async () => {
    state.center.mockResolvedValue(center('account-a', { records: { records: [record('history-hidden')], total: 40 } }));
    const container = await mount();
    expect(container.querySelector('.reward-history')).toBeNull();
    state.session.token = 'account-b'; await flush();
    state.locale.value = 'en'; await flush();
    expect(state.center).toHaveBeenCalledTimes(3);
    expect(state.records).not.toHaveBeenCalled();
    expect(container.textContent).not.toContain('history-hidden');
    expect(container.textContent).toContain('First deposit');
  });

  it('退出与卸载后迟到的中心响应不能恢复奖励内容', async () => {
    const afterLogout = deferred<RewardCenter>(), afterUnmount = deferred<RewardCenter>();
    state.center.mockImplementationOnce(() => afterLogout.promise).mockImplementationOnce(() => afterUnmount.promise);
    const container = await mount();
    state.session.token = '';
    expect((state.center.mock.calls[0]![0] as AbortSignal).aborted).toBe(true);
    afterLogout.reject(new Error('logged-out-error'));
    await flush();
    expect(container.textContent).not.toContain('logged-out-error');
    expect(container.querySelector('[data-loading]')).toBeNull();
    state.session.token = 'account-b';
    apps.pop()!.unmount();
    expect((state.center.mock.calls[1]![0] as AbortSignal).aborted).toBe(true);
    afterUnmount.resolve(center('unmounted-data'));
    await flush();
    expect(container.textContent).not.toContain('unmounted-data');
  });
});
