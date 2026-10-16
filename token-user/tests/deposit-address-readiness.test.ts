import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

const state = vi.hoisted(() => ({
  depositAddress: vi.fn(),
  channels: null as any,
  session: null as any,
}));
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }));
vi.mock('vant', () => ({ showConfirmDialog: vi.fn(), showSuccessToast: vi.fn() }));
// 地址状态用例不执行手工金额提交或剪贴板，隔离其全局多语言初始化依赖。
vi.mock('@/utils/format', () => ({ copyText: vi.fn(), positiveAmount: vi.fn(() => false) }));
vi.mock('@/api', () => ({ api: { depositAddress: state.depositAddress }, requestId: () => 'isolated-address-request' }));
vi.mock('@/stores/session', () => ({ useSession: () => state.session }));
vi.mock('@/composables/useChannelLabels', () => ({ useChannelLabels: () => ({ network: (channel: any) => channel.network, depositHint: () => '' }) }));
vi.mock('@/composables/useCurrencies', async () => {
  const { ref } = await import('vue');
  return { useCurrencies: () => ({ availableChannels: state.channels, task: { loading: ref(false), error: ref('') }, refresh: vi.fn() }) };
});
vi.mock('@/composables/useTask', async () => {
  const { ref } = await import('vue');
  return { useTask: () => ({ loading: ref(false), error: ref(''), run: async (work: () => Promise<unknown>) => work() }) };
});
vi.mock('@/components/QrCode.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return { default: defineComponent({ props: ['value'], setup: (props) => () => h('div', { 'data-qr': props.value }) }) };
});
vi.mock('../src/components/wallet/ChannelDropdowns.vue', () => ({ default: { render: () => null } }));
import DepositPanel from '../src/components/wallet/DepositPanel.vue';

const apps: ReturnType<typeof createApp>[] = [];
const address = '0x1111111111111111111111111111111111111111';
const channel = { currency: 'USDT', network: 'BSC', assetType: 'CRYPTO', depositMode: 'MCAG', supportAccountTypes: 'COMPUTE,PROJECT' };
const base = { currency: 'USDT', network: 'BSC', walletType: 'COMPUTE', address, memo: '不应显示的备注', addressId: 'isolated-address', monitoringReady: false };

beforeEach(() => {
  vi.useFakeTimers();
  vi.clearAllMocks();
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false);
  state.channels = ref([]);
  state.session = reactive({ token: 'isolated-session' });
});
afterEach(() => {
  apps.splice(0).forEach((app) => app.unmount());
  vi.restoreAllMocks();
  vi.useRealTimers();
});

async function mount() {
  const container = document.createElement('div');
  const app = createApp(DepositPanel);
  app.config.globalProperties.$t = (key: string) => key;
  for (const [name, tag] of [['van-button', 'button'], ['van-icon', 'span'], ['van-loading', 'span'], ['van-form', 'form'], ['van-field', 'input']]) {
    app.component(name!, defineComponent({ inheritAttrs: false, setup: (_, { attrs, slots }) => () => h(tag!, attrs, slots.default?.()) }));
  }
  apps.push(app);
  app.mount(container);
  // 复现通道异步返回，再获取当前币网的充值地址。
  state.channels.value = [{ ...channel }];
  await nextTick();
  await vi.advanceTimersByTimeAsync(121);
  await nextTick();
  return container;
}
function expectHidden(container: HTMLElement) {
  expect(container.textContent).toContain('user.payment.addressUnavailable');
  expect(container.textContent).not.toContain('user.v15.addressPreparing');
  expect(container.textContent).not.toContain(address);
  expect(container.textContent).not.toContain(base.memo);
  expect(container.querySelector('[data-qr]')).toBeNull();
  expect(container.querySelector('.deposit-address-result')).toBeNull();
}
function expectReady(container: HTMLElement) {
  expect(container.querySelector('[data-qr]')?.getAttribute('data-qr')).toBe(address);
  expect(container.textContent).toContain(address);
  expect(container.textContent).not.toContain('user.payment.addressUnavailable');
}

describe('充值地址业务状态与网络监听就绪', () => {
  it('新地址仅凭业务启用和监听就绪即可直接展示，不等待历史初始化', async () => {
    state.depositAddress.mockResolvedValue({ ...base, status: 'ACTIVE', monitoringReady: true });
    const container = await mount();
    expectReady(container);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(state.depositAddress).toHaveBeenCalledTimes(1);
  });

  it.each([
    { status: 'DISABLED', monitoringReady: true },
    { status: 'UNAVAILABLE', monitoringReady: false },
  ])('业务不可用不展示地址、不自动重试：$status', async (status) => {
    state.depositAddress.mockResolvedValue({ ...base, ...status });
    const container = await mount();
    expectHidden(container);
    await vi.advanceTimersByTimeAsync(60_000);
    document.dispatchEvent(new Event('visibilitychange'));
    await nextTick();
    expect(state.depositAddress).toHaveBeenCalledTimes(1);
    expectHidden(container);
  });

  it('监听未就绪保持地址隐藏，有限刷新获得就绪后展示原地址', async () => {
    state.depositAddress.mockResolvedValueOnce({ ...base, status: 'ACTIVE' });
    state.depositAddress.mockResolvedValue({ ...base, status: 'ACTIVE', monitoringReady: true });
    const container = await mount();
    expectHidden(container);
    await vi.advanceTimersByTimeAsync(4_000);
    await nextTick();
    expectReady(container);
    expect(state.depositAddress).toHaveBeenCalledTimes(2);
    expect(state.depositAddress.mock.calls[1]?.[2]).toBe(true);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(state.depositAddress).toHaveBeenCalledTimes(2);
  });

  it('人工刷新监听就绪状态恢复地址及二维码', async () => {
    state.depositAddress.mockResolvedValueOnce({ ...base, status: 'ACTIVE' });
    state.depositAddress.mockResolvedValue({ ...base, status: 'ACTIVE', monitoringReady: true });
    const container = await mount();
    expectHidden(container);
    container.querySelector<HTMLButtonElement>('.deposit-address-state button')!.click();
    await vi.advanceTimersByTimeAsync(0);
    await nextTick();
    expect(state.depositAddress).toHaveBeenCalledTimes(2);
    expect(state.depositAddress.mock.calls[1]?.[2]).toBe(false);
    expectReady(container);
  });

  it('切链后迟到的BSC未就绪响应不能覆盖同地址ETH的就绪结果', async () => {
    let completeBsc!: (value: unknown) => void;
    state.depositAddress.mockImplementationOnce(() => new Promise((resolve) => { completeBsc = resolve; }));
    state.depositAddress.mockResolvedValue({ ...base, network: 'ETH', status: 'ACTIVE', monitoringReady: true });
    const container = await mount();
    state.channels.value = [{ ...channel, network: 'ETH' }];
    await nextTick();
    await vi.advanceTimersByTimeAsync(121);
    await nextTick();
    expect(state.depositAddress.mock.calls.at(-1)?.[0].network).toBe('ETH');
    expectReady(container);
    completeBsc({ ...base, status: 'ACTIVE', monitoringReady: false });
    await vi.advanceTimersByTimeAsync(0);
    await nextTick();
    expectReady(container);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(state.depositAddress).toHaveBeenCalledTimes(2);
  });

  it('持续未就绪在既定次数停止自动刷新，隐藏地址并保留手工刷新', async () => {
    state.depositAddress.mockResolvedValue({ ...base, status: 'ACTIVE' });
    const container = await mount();
    await vi.advanceTimersByTimeAsync(500_000);
    await nextTick();
    expect(state.depositAddress).toHaveBeenCalledTimes(30);
    expectHidden(container);
    expect(container.textContent).toContain('user.v15.addressRetryPaused');
    expect(container.querySelector('.deposit-address-state button')).not.toBeNull();
    document.dispatchEvent(new Event('visibilitychange'));
    await vi.advanceTimersByTimeAsync(60_000);
    expect(state.depositAddress).toHaveBeenCalledTimes(30);
  });
});