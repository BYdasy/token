import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';
import type { Activity, ActivityParticipation } from '../src/api/activity';

const state = vi.hoisted(() => ({
  detail: vi.fn(), participate: vi.fn(), records: vi.fn(), push: vi.fn(),
  session: null as any, route: null as any, locale: null as any,
}));
vi.mock('@/api/activity', () => ({ activityApi: { detail: state.detail, participate: state.participate, records: state.records } }));
vi.mock('@/stores/session', () => ({ useSession: () => state.session }));
vi.mock('vue-router', () => ({ useRoute: () => state.route, useRouter: () => ({ push: state.push }) }));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key, locale: state.locale }) }));
vi.mock('@/locales', () => ({ i18n: { global: { t: (key: string) => key } } }));
vi.mock('vant', () => ({ showToast: vi.fn(), showDialog: vi.fn() }));
vi.mock('@/composables/useToken', () => ({ useToken: () => ({ tokenAmount: String, unitName: ref('Vora Points') }) }));
vi.mock('@/utils/format', () => ({ date: String, copyText: vi.fn() }));
import ActivityDetailView from '../src/views/ActivityDetailView.vue';

const apps: ReturnType<typeof createApp>[] = [];
const wheel = (): Activity => ({
  id: 91, name: '八奖位活动', description: '实际活动说明', type: 'WHEEL', status: 'OPEN', currency: 'USDT',
  startsAt: '2026-10-01T00:00:00Z', endsAt: '2026-12-01T00:00:00Z',
  minLevel: 0, minDeposit: '0', minActivePrincipal: '0', totalStock: 80, usedStock: 0, remainingStock: 80,
  totalBudget: '800', usedBudget: '0', remainingBudget: '800', perUserTotalLimit: 0, perUserDailyLimit: 0,
  packetMode: 'RANDOM', minReward: '1', maxReward: '20',
  myWheel: { availableCount: 2, pendingCount: 0, usedCount: 0 }, wheelConfig: { spinDurationSeconds: 3 },
  prizes: Array.from({ length: 8 }, (_, index) => ({ id: index + 1, name: `奖项${index + 1}`, amount: String(index + 1), stock: 10, usedStock: 0, probability: '12.5' })),
});
const receipt = (overrides: Partial<ActivityParticipation> = {}): ActivityParticipation => ({
  id: 19, activityId: 91, activityName: '八奖位活动', activityType: 'WHEEL', prizeId: 4, prizeName: '服务端选中奖项',
  amount: '6.25', currency: 'USDT', status: 'PAID', orderNo: 'activity-order-19', createdAt: '2026-10-14T01:00:00Z', ...overrides,
});
async function settle() { for (let index = 0; index < 5; index++) { await Promise.resolve(); await nextTick(); } }
async function mount() {
  const host = document.createElement('div');
  const app = createApp(ActivityDetailView);
  app.config.globalProperties.$t = (key: string) => key;
  const stub = (tag: string) => defineComponent({ setup: (_, { attrs, slots }) => () => h(tag, attrs, slots.default?.()) });
  app.component('van-popup', defineComponent({ props: ['show'], setup: (props, { attrs, slots }) => () => props.show ? h('section', attrs, slots.default?.()) : null }));
  app.component('van-button', stub('button'));
  app.component('van-loading', stub('i'));
  app.component('van-icon', stub('i'));
  app.component('van-empty', stub('p'));
  app.component('RouterLink', stub('a'));
  apps.push(app); app.mount(host); await settle(); return host;
}
beforeEach(() => {
  vi.resetAllMocks(); vi.useFakeTimers();
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => setTimeout(() => callback(0), 0));
  vi.stubGlobal('matchMedia', () => ({ matches: true }));
  state.session = reactive({ authenticated: true, token: 'activity-preview', member: { id: 915 }, loadMember: vi.fn().mockResolvedValue(undefined) });
  state.route = reactive({ params: { id: '91' }, fullPath: '/activities/91' }); state.locale = ref('zh');
  state.detail.mockResolvedValue(wheel()); state.participate.mockResolvedValue(receipt()); state.records.mockResolvedValue({ records: [receipt()], total: 1 });
});
afterEach(() => { apps.splice(0).forEach(app => app.unmount()); vi.useRealTimers(); vi.unstubAllGlobals(); });

describe('活动舞台与真实结果', () => {
  it('八个奖位保留；连续点击只提交一次，结果采用服务端金额和可配置展示单位', async () => {
    const host = await mount();
    expect(host.querySelectorAll('.wheel-label')).toHaveLength(8);
    expect(host.querySelector('.attempt-count')?.textContent).toContain('2');
    const button = host.querySelector<HTMLButtonElement>('.wheel-center')!;
    button.click(); button.click(); await settle();
    expect(state.participate).toHaveBeenCalledOnce();
    expect(state.participate.mock.calls[0][0]).toBe(91);
    expect(state.participate.mock.calls[0][1]).toMatch(/^[A-Za-z0-9_-]{8,80}$/);
    expect(button.disabled).toBe(true);
    await vi.runAllTimersAsync(); await settle();
    const popup = host.querySelector('.wheel-result-popup')!;
    expect(popup.textContent).toContain('服务端选中奖项');
    expect(popup.textContent).toContain('6.25 Vora Points');
    expect(popup.textContent).toContain('user.activityNext.credited');
    popup.querySelector<HTMLButtonElement>('.van-button, button:last-child')!.click(); await settle();
    expect(host.querySelector('.wheel-result-popup')).toBeNull();
  });

  it('待兑奖奖项保留兑换码，不误展示已到账金额', async () => {
    state.participate.mockResolvedValue(receipt({ status: 'PENDING_REDEMPTION', redemptionCode: 'VORA-REDEEM' }));
    const host = await mount(); host.querySelector<HTMLButtonElement>('.wheel-center')!.click(); await settle();
    await vi.runAllTimersAsync(); await settle();
    const popup = host.querySelector('.wheel-result-popup')!;
    expect(popup.textContent).toContain('VORA-REDEEM');
    expect(popup.querySelector('.wheel-result-amount')).toBeNull();
    expect(popup.textContent).not.toContain('user.activityNext.credited');
  });

  it('宝箱用原参与结果打开并展示实际到账，记录入口只读取该活动记录', async () => {
    state.detail.mockResolvedValue({ ...wheel(), type: 'CHEST', prizes: [] });
    state.participate.mockResolvedValue(receipt({ activityType: 'CHEST' }));
    const host = await mount();
    expect(host.querySelector('.treasure-scene.opened')).toBeNull();
    host.querySelector<HTMLButtonElement>('.chest-open-button')!.click(); await settle();
    await vi.runAllTimersAsync(); await settle();
    expect(host.querySelector('.treasure-scene.opened')).not.toBeNull();
    expect(host.querySelector('.treasure-amount')?.textContent).toBe('6.25Vora Points');
    expect(host.querySelector('.chest-receipt')?.textContent).toContain('activity-order-19');
    expect(host.querySelector('.wheel-result-popup')).toBeNull();
    host.querySelector<HTMLButtonElement>('.activity-records-button')!.click(); await settle();
    expect(state.records.mock.calls[0].slice(0, 2)).toEqual([1, 91]);
    expect(host.querySelector('.wheel-records-popup')?.textContent).toContain('activity-order-19');
    expect(state.participate).toHaveBeenCalledOnce();
  });
});
