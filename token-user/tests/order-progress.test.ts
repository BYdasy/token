import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, nextTick } from 'vue';
import { createMemoryHistory, createRouter, RouterView } from 'vue-router';
import type { BusinessRecord, PageResult } from '../src/api/types';
import messages from '../../docs/i18n/current-user-zh.json';
import { orderCycleProgress, utcInstantMillis } from '../src/utils/order-progress';

const state = vi.hoisted(() => ({ list: vi.fn() }));
function translate(key: string, params: Record<string, unknown> = {}) {
  let value: string = messages[key as keyof typeof messages] || key;
  for (const [name, replacement] of Object.entries(params)) value = value.replace(`{${name}}`, String(replacement));
  return value;
}
vi.mock('@/api', () => ({ api: { list: state.list } }));
vi.mock('@/locales', () => ({ i18n: { global: { t: (key: string) => translate(key) } } }));
vi.mock('vant', () => ({ showDialog: vi.fn() }));
vi.mock('@/utils/format', () => ({ date: (value: unknown) => String(value), money: (value: unknown) => String(value) }));
vi.mock('@/composables/useToken', () => ({ useToken: () => ({ tokenAmount: (value: unknown) => String(value), unitName: 'Token' }) }));
import OrdersView from '../src/views/OrdersView.vue';

const DAY = 86_400_000;
const startsAt = '2026-10-01T00:00:00Z';
const starts = Date.parse(startsAt);
const cleanups: Array<() => void> = [];
let monotonic = 0;
let hidden = false;
const flush = async () => { for (let index = 0; index < 8; index++) { await Promise.resolve(); await nextTick(); } };
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}
function order(overrides: Partial<BusinessRecord> = {}): BusinessRecord {
  return {
    id: 1, orderNo: 'PO-fixture', productName: '固定订单快照', productId: 7,
    productType: 'GPU', status: 'ACTIVE', currency: 'USDT', amount: '100',
    paidIncome: '0', settledDays: 0, startsAt, durationDays: 365,
    maturesAt: '2027-10-01T00:00:00Z', settlementMode: 'A', rateBasis: 'DAILY', dailyRate: '1',
    ...overrides,
  };
}
async function mount() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/orders', component: OrdersView }, { path: '/projects', component: { render: () => null } }] });
  await router.push('/orders');
  await router.isReady();
  const host = document.createElement('div');
  document.body.append(host);
  const app = createApp({ render: () => h(RouterView) });
  app.use(router);
  app.config.globalProperties.$t = translate;
  const container = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) });
  for (const name of ['van-tabs', 'van-tab', 'van-icon', 'van-loading', 'van-empty']) app.component(name, container);
  app.component('van-button', defineComponent({
    props: { disabled: Boolean },
    setup: (props, { slots }) => () => h('button', { disabled: props.disabled }, slots.default?.()),
  }));
  app.component('van-progress', defineComponent({
    props: { percentage: Number },
    setup: (props) => () => h('div', { 'data-percentage': props.percentage }),
  }));
  let disposed = false;
  const dispose = () => { if (!disposed) { disposed = true; app.unmount(); host.remove(); } };
  cleanups.push(dispose);
  app.mount(host);
  await flush();
  return { host, router, dispose };
}
function visibility(value: boolean) {
  hidden = value;
  document.dispatchEvent(new Event('visibilitychange'));
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
  monotonic = 100;
  hidden = false;
  vi.spyOn(performance, 'now').mockImplementation(() => monotonic);
  vi.spyOn(document, 'hidden', 'get').mockImplementation(() => hidden);
  // 设备日期故意远离订单日期，周期仍必须锚定接口时间。
  vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2040-01-01T00:00:00Z'));
  state.list.mockResolvedValue({ records: [order()], total: 1, serverTime: '2026-10-03T01:00:00Z' });
});
afterEach(() => {
  cleanups.splice(0).forEach((dispose) => dispose());
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe('订单周期按完整24小时展示', () => {
  it.each([
    { name: '尚未开始', elapsed: -DAY, days: 0 },
    { name: '未满一天', elapsed: DAY - 1, days: 0 },
    { name: '恰满一天', elapsed: DAY, days: 1 },
    { name: '已过49小时', elapsed: 49 * 60 * 60 * 1000, days: 2 },
    { name: '恰好到期', elapsed: 365 * DAY, days: 365 },
    { name: '超过周期', elapsed: 500 * DAY, days: 365 },
  ])('$name 保持0至原订单周期范围', ({ elapsed, days }) => {
    expect(orderCycleProgress(startsAt, 365, starts + elapsed)).toEqual({
      elapsedDays: days, durationDays: 365, percentage: days / 365 * 100,
    });
  });

  it('缺失、非法和无时区的时间或无效周期不能伪装为零进度', () => {
    for (const value of [undefined, null, '', 'invalid', '2026-10-01T00:00:00', '2026-02-30T00:00:00Z']) {
      expect(utcInstantMillis(value)).toBeNull();
      expect(orderCycleProgress(value, 365, starts + DAY)).toBeNull();
    }
    for (const duration of [undefined, null, '', true, 0, -1, 1.5, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
      expect(orderCycleProgress(startsAt, duration, starts + DAY)).toBeNull();
    }
    expect(orderCycleProgress(startsAt, 365, null)).toBeNull();
    expect(orderCycleProgress(startsAt, 365, NaN)).toBeNull();
    expect(utcInstantMillis('2026-10-01T00:00:00.123456789Z')).toBe(starts + 123);
  });
});

describe('订单实际页面周期与账务展示分离', () => {
  it('49小时显示2/365天，A/B未付收益保持0，C/D原结算天数和金额保留', async () => {
    const records = [
      order(), order({ id: 2, settlementMode: 'B' }),
      order({ id: 3, settlementMode: 'C', settledDays: 1, paidIncome: '0.75000001' }),
      order({ id: 4, settlementMode: 'D', settledDays: 2, paidIncome: '2.12345678' }),
    ].map((record) => Object.freeze(record));
    state.list.mockResolvedValue({ records, total: records.length, serverTime: '2026-10-03T01:00:00Z' });
    const { host } = await mount();
    const cards = [...host.querySelectorAll('.ui-order-card')];
    expect(cards).toHaveLength(4);
    for (const card of cards) expect(card.querySelector('.ui-order-progress')?.textContent).toContain('已运行 2 / 365 天');
    expect(cards[0]!.querySelector('.positive')?.textContent).toBe('0Token');
    expect(cards[1]!.querySelector('.positive')?.textContent).toBe('0Token');
    expect(cards[2]!.querySelector('.positive')?.textContent).toBe('0.75000001Token');
    expect(cards[3]!.querySelector('.positive')?.textContent).toBe('2.12345678Token');
    expect(cards[2]!.querySelector('.details-list')?.textContent).toContain('已结算天数1 天');
    expect(cards[3]!.querySelector('.details-list')?.textContent).toContain('已结算天数2 天');
    const desktopRows = [...host.querySelectorAll('.vora-desktop-orders tbody tr')];
    expect(desktopRows[0]!.querySelector('.vora-order-settled-days')).toBeNull();
    expect(desktopRows[1]!.querySelector('.vora-order-settled-days')).toBeNull();
    expect(desktopRows[2]!.querySelector('.vora-order-settled-days')?.textContent).toBe('已结算天数 1 天');
    expect(desktopRows[3]!.querySelector('.vora-order-settled-days')?.textContent).toBe('已结算天数 2 天');
    expect(records.map((record) => record.settledDays)).toEqual([0, 0, 1, 2]);
  });

  it.each([undefined, 'invalid', '2026-10-03T01:00:00'])('旧服务端时间 %s 不借用settledDays或设备时间，显示—且保留金额', async (serverTime) => {
    state.list.mockResolvedValue({ records: [order({ settledDays: 20, paidIncome: '12.34' })], total: 1, serverTime });
    const { host } = await mount();
    expect(host.querySelector('.ui-order-progress strong')?.textContent).toBe('—');
    expect(host.querySelector('[data-percentage]')).toBeNull();
    expect(host.querySelector('.ui-order-card .positive')?.textContent).toBe('12.34Token');
    expect(host.querySelector('.vora-desktop-orders .positive')?.textContent).toBe('12.34');
  });

  it('长驻页面每分钟本地推进至100%，不额外请求、不改变ACTIVE状态或收益', async () => {
    state.list.mockResolvedValue({ records: [order({ durationDays: 1 })], total: 1, serverTime: '2026-10-01T23:59:30Z' });
    const { host, dispose } = await mount();
    expect(host.querySelector('.ui-order-progress strong')?.textContent).toBe('已运行 0 / 1 天');
    monotonic += 60_000;
    await vi.advanceTimersByTimeAsync(60_000);
    await flush();
    expect(host.querySelector('.ui-order-progress strong')?.textContent).toBe('已运行 1 / 1 天');
    expect(host.querySelector('[data-percentage]')?.getAttribute('data-percentage')).toBe('100');
    expect(host.querySelector('.status-tag')?.textContent).toBe(messages['user.status.ACTIVE']);
    expect(host.querySelector('.ui-order-card .positive')?.textContent).toBe('0Token');
    expect(host.querySelector('.vora-desktop-orders .positive')?.textContent).toBe('0');
    monotonic += 400 * DAY;
    await vi.advanceTimersByTimeAsync(60_000);
    expect(host.querySelector('.ui-order-progress strong')?.textContent).toBe('已运行 1 / 1 天');
    expect(state.list).toHaveBeenCalledOnce();
    dispose();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('返回可见页校准当前筛选，迟到旧响应不能回填记录或覆盖新时间', async () => {
    const { host, router } = await mount();
    const old = deferred<PageResult>();
    state.list.mockReturnValueOnce(old.promise);
    state.list.mockResolvedValueOnce({ records: [order({ productName: '新筛选订单' })], total: 1, serverTime: '2026-10-05T00:00:00Z' });
    visibility(true);
    visibility(false);
    await flush();
    expect(state.list).toHaveBeenCalledTimes(2);
    await router.replace({ query: { status: 'COMPLETED' } });
    await flush();
    expect((state.list.mock.calls[1]![4] as AbortSignal).aborted).toBe(true);
    old.resolve({ records: [order({ productName: '迟到旧订单' })], total: 1, serverTime: '2030-01-01T00:00:00Z' });
    await flush();
    expect(state.list).toHaveBeenCalledTimes(3);
    expect(state.list.mock.calls[2]!.slice(0, 4)).toEqual(['orders', 1, 20, { status: 'COMPLETED' }]);
    expect(host.textContent).toContain('新筛选订单');
    expect(host.textContent).not.toContain('迟到旧订单');
    expect(host.querySelector('.ui-order-progress strong')?.textContent).toBe('已运行 4 / 365 天');
  });

  it('卸载取消在途请求、清理计时和可见性监听，排队读取不再发送', async () => {
    const old = deferred<PageResult>();
    state.list.mockReturnValueOnce(old.promise);
    const removed = vi.spyOn(document, 'removeEventListener');
    const { dispose } = await mount();
    visibility(true);
    visibility(false);
    await flush();
    dispose();
    expect((state.list.mock.calls[0]![4] as AbortSignal).aborted).toBe(true);
    expect(removed).toHaveBeenCalledWith('visibilitychange', expect.any(Function));
    expect(vi.getTimerCount()).toBe(0);
    old.resolve({ records: [order()], total: 1, serverTime: '2026-10-03T01:00:00Z' });
    await flush();
    visibility(true);
    visibility(false);
    await flush();
    expect(state.list).toHaveBeenCalledOnce();
  });
});
