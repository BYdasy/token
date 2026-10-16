import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';
import type { Component } from 'vue';
import type { Quote, WithdrawalBalance } from '../src/api/types';
import zh from '../../docs/i18n/current-user-zh.json';

const state = vi.hoisted(() => ({
  balance: vi.fn(), quote: vi.fn(), addresses: vi.fn(), withdraw: vi.fn(), confirm: vi.fn(), taskPrompt: vi.fn(),
  translate: vi.fn(), tokenAmount: vi.fn(), session: null as any, locale: null as any, channels: null as any,
  ApiError: class extends Error { constructor(message: string, public code = -1, public responseReceived = false) { super(message); } },
}));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: state.translate, te: () => false, locale: state.locale }) }));
vi.mock('vant', () => ({ showConfirmDialog: state.confirm, showSuccessToast: vi.fn(), showFailToast: vi.fn() }));
vi.mock('@/stores/session', () => ({ useSession: () => state.session }));
vi.mock('@/api', () => ({ api: { withdrawalBalance: state.balance, quote: state.quote, addresses: state.addresses, withdraw: state.withdraw }, ApiError: state.ApiError, requestId: () => crypto.randomUUID() }));
vi.mock('@/composables/useTaskPrompt', () => ({ showTaskPrompt: state.taskPrompt }));
vi.mock('@/composables/useCurrencies', async () => {
  const { ref } = await import('vue');
  return { useCurrencies: () => ({ channels: state.channels, task: { loading: ref(false), error: ref('') }, refresh: vi.fn() }) };
});
vi.mock('@/composables/useTask', async () => {
  const { ref } = await import('vue');
  return { useTask: () => {
    const loading = ref(false), error = ref('');
    return { loading, error, run: async (action: () => Promise<unknown>) => {
      loading.value = true; error.value = '';
      try { return await action(); }
      catch (cause) { error.value = (cause as Error).message; }
      finally { loading.value = false; }
    } };
  } };
});
vi.mock('@/composables/useToken', async () => { const { ref } = await import('vue'); return { useToken: () => ({ tokenAmount: state.tokenAmount, tokenTotal: () => '100', unitName: ref('token') }) }; });
vi.mock('@/utils/format', () => ({ money: (value: unknown) => String(value ?? '—'), date: () => '', copyText: vi.fn(), positiveAmount: (value: string) => Number(value) > 0, validPassword: (value: string) => value.length >= 6 }));
vi.mock('@/utils/currency', () => ({ currencyLabel: (value: unknown) => String(value || '') }));
vi.mock('@/components/wallet/PaymentPasswordField.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return { default: defineComponent({ props: ['modelValue', 'disabled'], emits: ['update:modelValue'], setup: (props, { emit }) => () => h('input', { class: 'payment-password', disabled: props.disabled, value: props.modelValue, onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value) }) }) };
});
vi.mock('@/components/wallet/AddressPicker.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return { default: defineComponent({ props: ['addresses'], emits: ['select'], setup: (props, { emit }) => () => h('nav', (props.addresses || []).map((address: any) => h('button', { class: `select-${address.currency}`, type: 'button', onClick: () => emit('select', address) }, address.currency))) }) };
});
vi.mock('@/components/wallet/ChannelDropdowns.vue', () => ({ default: { render: () => null } }));
vi.mock('@/components/wallet/ReceivingFields.vue', () => ({ default: { render: () => null } }));
import WithdrawalPanel from '../src/components/wallet/WithdrawalPanel.vue';
import BusinessRecords from '../src/components/BusinessRecords.vue';

const apps: ReturnType<typeof createApp>[] = [];
const trxBalance: WithdrawalBalance = { accountingCurrency: 'USD', accountingRate: '0.333333333333333333', availableUsd: '100', availableAmount: '300', quoteAt: '2026-09-22T00:00:00Z' };
const trxQuote: Quote = { ...trxBalance, channelId: 1, feeSource: 'CHANNEL', fee: '3', netAmount: '27', accountingAmount: '10', accountingFee: '1', accountingNetAmount: '9' };
function deferred<T>() {
  let resolve!: (value: T) => void; let reject!: (cause: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
async function flush(milliseconds = 0) { await vi.advanceTimersByTimeAsync(milliseconds); await nextTick(); }
async function mount(component: Component = WithdrawalPanel, props: Record<string, unknown> = { wallets: [{ type: 'EARNINGS', currency: 'TRX', available: '999' }] }) {
  const container = document.createElement('div'), app = createApp(component, props);
  app.config.globalProperties.$t = state.translate;
  app.component('van-icon', defineComponent({ render: () => h('span') }));
  app.component('van-loading', defineComponent({ render: () => h('span') }));
  app.component('RouterLink', defineComponent({ props: ['to'], setup: (props, { slots }) => () => h('a', { href: props.to }, slots.default?.()) }));
  app.component('van-form', defineComponent({ setup: (_, { slots }) => () => h('form', { onSubmit: (event: Event) => event.preventDefault() }, slots.default?.()) }));
  app.component('van-field', defineComponent({ props: ['modelValue', 'disabled'], emits: ['update:modelValue'], setup: (props, { emit, slots }) => () => h('label', [h('input', { class: 'amount-input', value: props.modelValue, disabled: props.disabled, onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value) }), slots['right-icon']?.()]) }));
  app.component('van-button', defineComponent({ props: ['disabled', 'loading'], setup: (props, { slots }) => () => h('button', { disabled: props.disabled || props.loading }, slots.default?.()) }));
  apps.push(app); app.mount(container); await flush(); return container;
}
async function input(container: HTMLElement, selector: string, value: string) {
  const field = container.querySelector<HTMLInputElement>(selector)!; field.value = value; field.dispatchEvent(new Event('input', { bubbles: true })); await flush();
}
function submit(container: HTMLElement) { container.querySelector<HTMLButtonElement>('.withdraw-quote > button')!.click(); }
beforeEach(() => {
  vi.useFakeTimers(); vi.resetAllMocks();
  state.session = reactive({ token: 'account-a', member: { hasPayPassword: true } }); state.locale = ref('zh');
  state.channels = ref([{ currency: 'TRX', network: 'TRON', assetType: 'CRYPTO', needMemo: false, requiredFields: [] }, { currency: 'BTC', network: 'BTC', assetType: 'CRYPTO', needMemo: false, requiredFields: [] }]);
  state.translate.mockImplementation((key: string, params: Record<string, unknown> = {}) => {
    const value = (zh as Record<string, string>)[key] || (key === 'user.v13.wallet.channelAvailable' ? '{amount} {currency}' : key);
    return Object.entries(params).reduce((text, [name, replacement]) => text.replaceAll(`{${name}}`, String(replacement)), value);
  });
  state.tokenAmount.mockReturnValue('LIVE-RATE-MUST-NOT-BE-USED');
  state.addresses.mockResolvedValue([{ id: 1, currency: 'TRX', network: 'TRON', address: 'tron-wallet', fields: {} }, { id: 2, currency: 'BTC', network: 'BTC', address: 'bitcoin-wallet', fields: {} }]);
  state.balance.mockResolvedValue(trxBalance); state.quote.mockResolvedValue(trxQuote);
  state.confirm.mockResolvedValue(undefined); state.taskPrompt.mockResolvedValue(true); state.withdraw.mockResolvedValue({ id: 1 });
});
afterEach(() => { apps.splice(0).forEach((app) => app.unmount()); vi.clearAllTimers(); vi.useRealTimers(); });

describe('统一 USD 计价的充提界面', () => {
  it('当前币种可用使用服务端换算的300TRX，并随确认提交原币及USD报价', async () => {
    const container = await mount(); await flush(121);
    expect(state.balance.mock.calls[0]![0]).toEqual({ currency: 'TRX', network: 'TRON' });
    expect(container.querySelector('.channel-balance')?.textContent).toBe('当前币种可用：300 TRX');
    expect(container.textContent).not.toContain('999');
    await input(container, '.amount-input', '30'); await input(container, '.payment-password', '123456'); await flush(351);
    expect(container.textContent).toContain('1 TRX = 0.333333333333333333 USD');
    expect(container.textContent).toContain('10 token');
    submit(container); await flush();
    expect(state.taskPrompt).toHaveBeenCalledWith('WITHDRAW', expect.any(AbortSignal));
    expect(state.withdraw).toHaveBeenCalledWith(expect.objectContaining({ amount: '30', currency: 'TRX', network: 'TRON', payPassword: '123456', expectedFee: '3', expectedNetAmount: '27', expectedAccountingRate: trxBalance.accountingRate, expectedAccountingAmount: '10' }));
  });

  it('没有USD换算报价时不可提交，任务限制仍阻止已完整报价的提款', async () => {
    state.quote.mockResolvedValueOnce({ fee: '3', netAmount: '27', channelId: 1, feeSource: 'CHANNEL' });
    const container = await mount(); await input(container, '.amount-input', '30'); await input(container, '.payment-password', '123456'); await flush(351);
    expect(container.textContent).toContain(zh['user.v22Accounting.quoteIncomplete']);
    expect(container.querySelector<HTMLButtonElement>('.withdraw-quote > button')!.disabled).toBe(true);
    await input(container, '.amount-input', '31'); await flush(351); state.taskPrompt.mockResolvedValueOnce(false);
    submit(container); await flush();
    expect(state.withdraw).not.toHaveBeenCalled(); expect(state.confirm).not.toHaveBeenCalled();
  });

  it('切换币种和语言立即作废旧额度响应，不覆盖新币种额度或错误状态', async () => {
    const oldCurrency = deferred<WithdrawalBalance>(), oldLanguage = deferred<WithdrawalBalance>();
    state.balance.mockImplementationOnce(() => oldCurrency.promise).mockImplementationOnce(() => oldLanguage.promise)
      .mockResolvedValueOnce({ ...trxBalance, accountingRate: '50000', availableAmount: '0.002' });
    const container = await mount(); await flush(121);
    container.querySelector<HTMLButtonElement>('.select-BTC')!.click();
    expect((state.balance.mock.calls[0]![1] as AbortSignal).aborted).toBe(true);
    await flush(121); state.locale.value = 'en';
    expect((state.balance.mock.calls[1]![1] as AbortSignal).aborted).toBe(true);
    await flush(121); oldCurrency.resolve({ ...trxBalance, availableAmount: '888' }); oldLanguage.reject(new Error('old-balance-error')); await flush();
    expect(container.querySelector('.channel-balance')?.textContent).toBe('当前币种可用：0.002 BTC');
    expect(container.textContent).not.toContain('888'); expect(container.textContent).not.toContain('old-balance-error');
  });

  it('未知结果经历语言、通道行情变化和离开重进后，仍锁定原号及完整USD请求；后续业务拒绝也不换号', async () => {
    state.withdraw.mockRejectedValueOnce(new state.ApiError('server unavailable', 503, false))
      .mockRejectedValueOnce(new state.ApiError('request conflict', -1, true)).mockResolvedValueOnce({ id: 1 });
    const container = await mount(); await input(container, '.amount-input', '30'); await input(container, '.payment-password', '123456'); await flush(351);
    const storage = vi.spyOn(Storage.prototype, 'setItem');
    submit(container); await flush();
    expect(container.querySelector<HTMLInputElement>('.amount-input')!.disabled).toBe(true);
    state.quote.mockResolvedValue({ ...trxQuote, accountingRate: '0.5', accountingAmount: '15' });
    state.locale.value = 'en'; state.channels.value = [...state.channels.value];
    container.querySelector<HTMLButtonElement>('.select-BTC')!.click(); await flush(500);
    expect(state.quote).toHaveBeenCalledTimes(1);
    apps.pop()!.unmount(); const restored = await mount(); await flush(500);
    expect(restored.querySelector<HTMLInputElement>('.amount-input')!.value).toBe('30');
    expect(restored.querySelector<HTMLInputElement>('.amount-input')!.disabled).toBe(true);
    expect(restored.textContent).toContain('0.333333333333333333');
    submit(restored); await flush();
    expect(restored.querySelector<HTMLInputElement>('.amount-input')!.disabled).toBe(true);
    submit(restored); await flush();
    expect(state.withdraw).toHaveBeenCalledTimes(3);
    expect(state.withdraw.mock.calls[0]![0]).toEqual(state.withdraw.mock.calls[1]![0]);
    expect(state.withdraw.mock.calls[0]![0]).toEqual(state.withdraw.mock.calls[2]![0]);
    expect(storage).not.toHaveBeenCalled(); storage.mockRestore();
  });

  it('退出时立即清理进程内未决请求和支付密码，新账号不能恢复上一人的提款', async () => {
    state.withdraw.mockRejectedValueOnce(new state.ApiError('timeout'));
    const container = await mount(); await input(container, '.amount-input', '30'); await input(container, '.payment-password', '123456'); await flush(351);
    submit(container); await flush(); apps.pop()!.unmount();
    state.session.token = 'account-b';
    const nextAccount = await mount(); await flush(351);
    expect(nextAccount.querySelector<HTMLInputElement>('.amount-input')!.value).toBe('');
    expect(nextAccount.querySelector<HTMLInputElement>('.payment-password')!.value).toBe('');
    expect(nextAccount.querySelector<HTMLInputElement>('.amount-input')!.disabled).toBe(false);
    expect(nextAccount.textContent).not.toContain(zh['user.v22Accounting.pendingWithdrawal']);
  });

  it('首次明确业务拒绝允许更正表单和重新报价，不把它误认成未决提款', async () => {
    state.withdraw.mockRejectedValueOnce(new state.ApiError('pay password invalid', -1, true));
    const container = await mount(); await input(container, '.amount-input', '30'); await input(container, '.payment-password', '123456'); await flush(351);
    submit(container); await flush(351);
    expect(container.querySelector<HTMLInputElement>('.amount-input')!.disabled).toBe(false);
    expect(container.textContent).not.toContain(zh['user.v22Accounting.pendingWithdrawal']);
    expect(state.quote).toHaveBeenCalledTimes(2);
  });

  it('提款记录主金额为历史USD净额，并附原币净额；精简后不展示汇率和扣款总额', async () => {
    const container = await mount(BusinessRecords, { kind: 'withdrawals', records: [
      { id: 1, currency: 'TRX', amount: '30', fee: '3', netAmount: '27', status: 'APPROVED', accountingCurrency: 'USD', accountingRate: '0.333333333333333333', accountingAmount: '10', accountingFee: '1', accountingNetAmount: '9' },
      { id: 2, currency: 'BTC', amount: '1', status: 'APPROVED' },
    ] });
    expect(container.textContent).toContain('27 TRX'); expect(container.textContent).toContain('9 token');
    expect(container.textContent).not.toContain('10 token');
    expect(container.textContent).not.toContain('0.333333333333333333');
    expect(container.textContent).toContain(zh['user.v22Accounting.unpriced']);
    expect(state.tokenAmount).not.toHaveBeenCalled();
  });

  it('尚未入账的充值保留原币，不把预计USD数额显示为实际入账', async () => {
    const container = await mount(BusinessRecords, { kind: 'deposits', records: [
      { id: 1, currency: 'TRX', amount: '30', status: 'PENDING', accountingStatus: 'PENDING', accountingCurrency: 'USD', accountingRate: '0.333333333333333333', accountingAmount: '777' },
    ] });
    expect(container.textContent).toContain('30 TRX'); expect(container.textContent).toContain(zh['user.v22Accounting.notPosted']);
    expect(container.textContent).not.toContain('777'); expect(state.tokenAmount).not.toHaveBeenCalled();
  });
});
