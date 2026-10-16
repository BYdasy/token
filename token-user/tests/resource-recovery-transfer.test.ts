import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import { createResourceRecovery } from '../src/utils/resource-recovery';

const state = vi.hoisted(() => ({ transfer: vi.fn(), options: vi.fn(), quote: vi.fn(), session: null as any, locale: null as any }));
vi.mock('@/api', () => ({ api: { transfer: state.transfer }, ApiError: class extends Error {}, requestId: () => crypto.randomUUID() }));
vi.mock('@/api/transfers', () => ({ fetchTransferOptions: state.options, fetchTransferQuote: state.quote }));
vi.mock('@/stores/session', () => ({ useSession: () => state.session }));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key, locale: state.locale }) }));
vi.mock('@/locales', () => ({ i18n: { global: { t: (key: string) => key } } }));
vi.mock('vant', () => ({ showSuccessToast: vi.fn(), showDialog: vi.fn() }));
vi.mock('@/utils/format', () => ({ money: String, positiveAmount: (value: string) => Number(value) > 0, validPassword: (value: string) => value.length >= 6 }));
vi.mock('@/components/wallet/WalletSelector.vue', () => ({ default: { render: () => null } }));
vi.mock('@/components/wallet/PaymentPasswordField.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return { default: defineComponent({ props: ['modelValue'], emits: ['update:modelValue'], setup: (props, { emit }) => () => h('input', { 'data-password': '', value: props.modelValue, onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value) }) }) };
});
import TransferPanel from '../src/components/wallet/TransferPanel.vue';

const cleanup: Array<() => void> = [];
beforeEach(() => {
  vi.useFakeTimers(); vi.resetAllMocks(); sessionStorage.clear();
  state.session = reactive({ token: 'account-one', member: { hasPayPassword: true } }); state.locale = ref('zh');
  state.options.mockResolvedValue({ currency: 'USDT', assetRate: '1', directions: [{ fromWallet: 'EARNINGS', toWallet: 'PROJECT' }] });
  state.quote.mockResolvedValue({ fee: '1', netAmount: '9' });
});
afterEach(() => { cleanup.splice(0).forEach(dispose => dispose()); vi.clearAllTimers(); vi.useRealTimers(); });
async function flush(ms = 1) { await vi.advanceTimersByTimeAsync(ms); await nextTick(); }

it('真实划转组件提交中/未知结果均阻止自动刷新，重试保持原号和报价；完成及卸载解除阻止', async () => {
  const events = new EventTarget(), replace = vi.fn();
  const host = {
    location: { href: 'https://site.test/transfer', origin: 'https://site.test', replace },
    history: { state: {}, replaceState: vi.fn() }, navigator: { onLine: true },
    sessionStorage: { getItem: () => null, setItem() {} }, caches: { keys: async () => [] },
    addEventListener: events.addEventListener.bind(events), removeEventListener: events.removeEventListener.bind(events),
  } as unknown as Window;
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/transfer', component: { render: () => null } }] });
  cleanup.push(createResourceRecovery(host).attach(router)); await router.push('/transfer');
  const failure = () => {
    const event = new Event('vite:preloadError', { cancelable: true });
    Object.assign(event, { payload: new TypeError('Importing a module script failed.') }); events.dispatchEvent(event);
  };
  const container = document.createElement('div');
  const app = createApp(TransferPanel, { wallets: [{ type: 'EARNINGS', currency: 'USDT', available: '100' }] });
  app.config.globalProperties.$t = (key: string) => key;
  for (const name of ['van-icon', 'van-loading', 'van-empty']) app.component(name, defineComponent({ render: () => null }));
  app.component('van-form', defineComponent({ emits: ['submit'], setup: (_, { slots, emit }) => () => h('form', { onSubmit: (event: Event) => { event.preventDefault(); emit('submit'); } }, slots.default?.()) }));
  app.component('van-field', defineComponent({ props: ['modelValue'], emits: ['update:modelValue'], setup: (props, { emit }) => () => h('input', { 'data-amount': '', value: props.modelValue, onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value) }) }));
  app.component('van-button', defineComponent({ setup: (_, { slots }) => () => h('button', slots.default?.()) }));
  app.mount(container); cleanup.push(() => app.unmount()); await flush();
  for (const [selector, value] of [['[data-amount]', '10'], ['[data-password]', 'payment-secret']] as const) {
    const field = container.querySelector<HTMLInputElement>(selector)!; field.value = value; field.dispatchEvent(new Event('input', { bubbles: true }));
  }
  await flush(251);
  let reject!: (cause: Error) => void;
  state.transfer.mockImplementationOnce(() => new Promise((_yes, no) => { reject = no; }));
  const submit = () => container.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  submit(); await flush(); expect(state.transfer).toHaveBeenCalledOnce();
  const original = { ...state.transfer.mock.calls[0]![0] };
  failure(); await flush(); expect(replace).not.toHaveBeenCalled();
  reject(new TypeError('network result unknown')); await flush();
  expect(container.textContent).toContain('user.v24Finance.pendingTransfer');
  failure(); await flush(); expect(replace).not.toHaveBeenCalled();
  state.transfer.mockResolvedValueOnce({}); submit(); await flush();
  expect(state.transfer).toHaveBeenLastCalledWith(original);
  expect(original).toMatchObject({ amount: '10', expectedFee: '1', expectedNetAmount: '9', payPassword: 'payment-secret' });
  app.unmount(); cleanup.pop();
  failure(); await flush(); expect(replace).toHaveBeenCalledOnce();
});
