import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';
import type { Address, Quote } from '../src/api/types';
import { usePendingWithdrawal } from '../src/utils/withdrawal-pending';
import messages from '../../docs/i18n/current-user-zh.json';

const state = vi.hoisted(() => ({
  addresses: vi.fn(), balance: vi.fn(), quote: vi.fn(), withdraw: vi.fn(), confirm: vi.fn(), taskPrompt: vi.fn(),
  session: null as any, channels: null as any, locale: null as any,
  ApiError: class extends Error { constructor(message: string, public code = -1, public responseReceived = false) { super(message); } },
}));
function translate(key: string) { return messages[key as keyof typeof messages] || key; }
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: translate, te: () => false, locale: state.locale }) }));
vi.mock('@/locales', () => ({ i18n: { global: { t: translate } } }));
vi.mock('@/stores/session', () => ({ useSession: () => state.session }));
vi.mock('@/api', () => ({
  api: { addresses: state.addresses, withdrawalBalance: state.balance, quote: state.quote, withdraw: state.withdraw },
  ApiError: state.ApiError, requestId: () => crypto.randomUUID(),
}));
vi.mock('vant', () => ({ showConfirmDialog: state.confirm, showSuccessToast: vi.fn(), showDialog: vi.fn() }));
vi.mock('@/composables/useCurrencies', async () => {
  const { ref } = await import('vue');
  return { useCurrencies: () => ({ channels: state.channels, task: { loading: ref(false), error: ref('') }, refresh: vi.fn() }) };
});
vi.mock('@/composables/useTaskPrompt', () => ({ showTaskPrompt: state.taskPrompt }));
vi.mock('@/utils/format', () => ({ money: (value: unknown) => String(value ?? '—'), positiveAmount: (value: string) => Number(value) > 0, validPassword: (value: string) => value.length >= 6 }));
vi.mock('@/components/wallet/CurrencyIcon.vue', () => ({ default: defineComponent({ render: () => h('i') }) }));
vi.mock('@/components/wallet/PaymentPasswordField.vue', () => ({ default: defineComponent({
  props: ['modelValue', 'disabled'], emits: ['update:modelValue'],
  setup: (props, { emit }) => () => h('input', { class: 'pay-password', value: props.modelValue, disabled: props.disabled, onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value) }),
}) }));
import WithdrawalPanel from '../src/components/wallet/WithdrawalPanel.vue';

const address: Address = { id: 1, enabled: true, label: '已绑定钱包一', currency: 'USDT', network: 'TRON', address: 'bound-address-one', fields: {} };
const quote: Quote = { channelId: 1, feeSource: 'CHANNEL', fee: '1', netAmount: '9', accountingCurrency: 'USD', accountingRate: '1', accountingAmount: '10', availableUsd: '100', availableAmount: '100' };
const cleanup: Array<() => void> = [];
async function flush(milliseconds = 0) { await vi.advanceTimersByTimeAsync(milliseconds); for (let i = 0; i < 6; i++) { await Promise.resolve(); await nextTick(); } }
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}
async function mount() {
  const host = document.createElement('div');
  document.body.append(host);
  const changed = vi.fn(), manage = vi.fn();
  const app = createApp(WithdrawalPanel, { wallets: [{ type: 'EARNINGS', currency: 'USDT', available: '100' }], onChanged: changed, onAddresses: manage });
  app.config.globalProperties.$t = translate;
  app.component('van-form', defineComponent({ emits: ['submit'], setup: (_, { emit, slots }) => () => h('form', { onSubmit: (event: Event) => { event.preventDefault(); emit('submit'); } }, slots.default?.()) }));
  app.component('van-field', defineComponent({ props: ['modelValue', 'disabled'], emits: ['update:modelValue'], setup: (props, { emit }) => () => h('input', { class: 'amount', disabled: props.disabled, value: props.modelValue, onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value) }) }));
  app.component('van-button', defineComponent({ props: ['disabled', 'loading'], setup: (props, { slots }) => () => h('button', { disabled: props.disabled || props.loading }, slots.default?.()) }));
  app.component('van-popup', defineComponent({ props: ['show'], setup: (props, { slots }) => () => props.show ? h('section', { 'data-picker': '' }, slots.default?.()) : null }));
  app.component('van-empty', defineComponent({ props: ['description'], setup: (props) => () => h('p', props.description) }));
  for (const name of ['van-icon', 'van-loading', 'van-search']) app.component(name, defineComponent({ render: () => h('i') }));
  app.component('RouterLink', defineComponent({ props: ['to'], setup: (props, { slots }) => () => h('a', { href: props.to }, slots.default?.()) }));
  app.mount(host);
  let disposed = false;
  const dispose = () => { if (!disposed) { disposed = true; app.unmount(); host.remove(); } };
  cleanup.push(dispose);
  await flush();
  return { host, changed, manage, dispose };
}
async function fill(host: HTMLElement) {
  for (const [selector, value] of [['.amount', '10'], ['.pay-password', '123456']]) {
    const input = host.querySelector<HTMLInputElement>(selector!)!;
    input.value = value!;
    input.dispatchEvent(new Event('input', { bubbles: true }));
  }
  await flush(351);
}
function submit(host: HTMLElement) { return host.querySelector<HTMLButtonElement>('.withdraw-quote > button')!; }
async function openPicker(host: HTMLElement) { host.querySelector<HTMLButtonElement>('.ui-channel-button')!.click(); await flush(); }
function legacyAttempt() {
  return {
    payload: { requestId: 'original-unresolved-request', amount: '10', currency: 'USDT', network: 'TRON', address: 'legacy-unbound-address', memo: '', fields: {}, payPassword: '123456', expectedFee: '1', expectedNetAmount: '9', expectedAccountingRate: '1', expectedAccountingAmount: '10' },
    quote, uncertain: true,
  };
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.useFakeTimers();
  state.session = reactive({ token: 'member-one', member: { hasPayPassword: true } });
  state.locale = ref('zh');
  state.channels = ref([{ currency: 'USDT', network: 'TRON', withdrawEnabled: true, assetType: 'CRYPTO', needMemo: false, requiredFields: [] }]);
  state.addresses.mockResolvedValue([address]);
  state.balance.mockResolvedValue({ accountingCurrency: 'USD', accountingRate: '1', availableUsd: '100', availableAmount: '100' });
  state.quote.mockResolvedValue(quote);
  state.withdraw.mockResolvedValue({ id: 1 });
  state.confirm.mockResolvedValue(undefined);
  state.taskPrompt.mockResolvedValue(true);
});
afterEach(() => { cleanup.splice(0).forEach((dispose) => dispose()); vi.clearAllTimers(); vi.useRealTimers(); });

describe('仅已绑定钱包可发起新提款', () => {
  it.each(['empty', 'unavailable'] as const)('%s 地址列表不出现手填入口或请求报价，提供绑定入口', async (kind) => {
    state.addresses.mockResolvedValue(kind === 'empty' ? [] : [
      { ...address, enabled: false }, { ...address, id: 2, network: 'BTC' }, { ...address, id: 0 },
    ]);
    const { host, manage } = await mount();
    await flush(500);
    expect(host.textContent).toContain(messages['user.v46.withdrawal.boundRequired']);
    expect(host.textContent).not.toContain('user.ui2.selectors.manualWallet');
    expect(host.querySelector('.receiving-fields')).toBeNull();
    expect(submit(host).disabled).toBe(true);
    expect(state.quote).not.toHaveBeenCalled();
    expect(state.balance).not.toHaveBeenCalled();
    await openPicker(host);
    expect(host.querySelectorAll('[role=option]')).toHaveLength(0);
    [...host.querySelectorAll<HTMLButtonElement>('button')].find((button) => button.textContent === messages['user.v46.withdrawal.bindWallet'])!.click();
    expect(manage).toHaveBeenCalledOnce();
    host.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await flush();
    expect(state.withdraw).not.toHaveBeenCalled();
  });

  it('真实选择器只给启用兼容地址，新提交固定addressId和对应地址快照', async () => {
    const second = { ...address, id: 2, label: '已绑定钱包二', address: 'bound-address-two', memo: 'stored-memo' };
    state.addresses.mockResolvedValue([{ ...address, id: 8, enabled: false }, address, second, { ...address, id: 9, network: 'BTC' }]);
    const { host } = await mount();
    await openPicker(host);
    const options = host.querySelectorAll<HTMLButtonElement>('[role=option]');
    expect(options).toHaveLength(2);
    options[1]!.click();
    await flush();
    expect(host.querySelector('.withdraw-address-summary')?.textContent).toContain(second.address);
    expect(host.querySelector('form')!.querySelectorAll('input')).toHaveLength(2);
    await fill(host);
    submit(host).click();
    await flush();
    expect(state.withdraw).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ addressId: 2, address: second.address, memo: 'stored-memo', currency: 'USDT', network: 'TRON', amount: '10', expectedAccountingAmount: '10' }));
  });

  it('地址读取失败保持不可提交，不回退手填，重试成功后才能使用绑定地址', async () => {
    state.addresses.mockRejectedValueOnce(new Error('addresses-failed'));
    const { host } = await mount();
    expect(submit(host).disabled).toBe(true);
    expect(host.querySelector<HTMLButtonElement>('.ui-channel-button')!.disabled).toBe(true);
    expect(host.textContent).toContain('addresses-failed');
    expect(host.textContent).not.toContain('user.ui2.selectors.manualWallet');
    host.querySelector<HTMLButtonElement>('[role=alert] button')!.click();
    await flush();
    await fill(host);
    submit(host).click();
    await flush();
    expect(state.addresses).toHaveBeenCalledTimes(2);
    expect(state.withdraw).toHaveBeenCalledWith(expect.objectContaining({ addressId: 1 }));
  });

  it('结果不明的历史裸地址收到绑定要求仍保留原号原报价，随后可幂等读回旧成功', async () => {
    const attempt = legacyAttempt();
    const pending = usePendingWithdrawal(state.session);
    pending.save(attempt);
    state.addresses.mockResolvedValue([]);
    state.withdraw.mockRejectedValueOnce(new state.ApiError('server.compute.boundWithdrawalAddressRequired', -1, true));
    const { host, changed } = await mount();
    expect(host.querySelector('.withdraw-address-summary')?.textContent).toContain(attempt.payload.address);
    expect(submit(host).disabled).toBe(false);
    submit(host).click();
    await flush();
    expect(pending.pending.value?.payload).toEqual(attempt.payload);
    expect(host.querySelector<HTMLInputElement>('.amount')!.disabled).toBe(true);
    expect(state.quote).not.toHaveBeenCalled();
    submit(host).click();
    await flush();
    expect(state.withdraw).toHaveBeenCalledTimes(2);
    expect(state.withdraw.mock.calls[0]![0]).toEqual(attempt.payload);
    expect(state.withdraw.mock.calls[1]![0]).toEqual(attempt.payload);
    expect(pending.pending.value).toBeUndefined();
    expect(changed).toHaveBeenCalledOnce();
  });

  it('首次明确绑定业务拒绝可解除本次尝试，但不会自动再提款', async () => {
    state.withdraw.mockRejectedValueOnce(new state.ApiError('server.compute.boundWithdrawalAddressRequired', -1, true));
    const { host } = await mount();
    await fill(host);
    submit(host).click();
    await flush(500);
    expect(usePendingWithdrawal(state.session).pending.value).toBeUndefined();
    expect(state.withdraw).toHaveBeenCalledOnce();
    expect(host.querySelector<HTMLInputElement>('.amount')!.disabled).toBe(false);
  });

  it('报价期间通道停用会撤销旧报价且不自动切换目的钱包', async () => {
    const oldQuote = deferred<Quote>();
    state.quote.mockReturnValueOnce(oldQuote.promise);
    const { host } = await mount();
    await fill(host);
    const signal = state.quote.mock.calls[0]![1] as AbortSignal;
    state.channels.value = [];
    await flush();
    expect(signal.aborted).toBe(true);
    oldQuote.resolve(quote);
    await flush();
    expect(submit(host).disabled).toBe(true);
    expect(host.querySelector('.withdraw-address-summary')).toBeNull();
    expect(state.withdraw).not.toHaveBeenCalled();
  });

  it('法币使用已绑定字段只读快照，不出现可改收款字段', async () => {
    state.channels.value = [{ currency: 'USD', network: 'BANK', withdrawEnabled: true, assetType: 'FIAT', needMemo: false, requiredFields: ['accountNumber', 'accountName'] }];
    state.addresses.mockResolvedValue([{ ...address, id: 3, currency: 'USD', network: 'BANK', address: '', fields: { accountNumber: '12345678', accountName: 'Bound Recipient' } }]);
    const { host } = await mount();
    expect(host.querySelector('.withdraw-address-summary')?.textContent).toContain('Bound Recipient');
    expect(host.querySelector('form')!.querySelectorAll('input')).toHaveLength(2);
    await fill(host);
    submit(host).click();
    await flush();
    expect(state.withdraw).toHaveBeenCalledWith(expect.objectContaining({ addressId: 3, fields: { accountNumber: '12345678', accountName: 'Bound Recipient' } }));
  });

  it('换账号作废旧绑定地址响应，卸载后排队的新读取也不发送', async () => {
    const old = deferred<Address[]>();
    state.addresses.mockReturnValueOnce(old.promise);
    const { host, dispose } = await mount();
    state.session.token = 'member-two';
    await flush();
    expect((state.addresses.mock.calls[0]![0] as AbortSignal).aborted).toBe(true);
    expect(submit(host).disabled).toBe(true);
    dispose();
    old.resolve([address]);
    await flush(500);
    expect(state.addresses).toHaveBeenCalledOnce();
    expect(state.quote).not.toHaveBeenCalled();
    expect(state.withdraw).not.toHaveBeenCalled();
  });
});
