import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';
import type { ProductQuote } from '../src/api/product';
import type { Product } from '../src/api/types';

const state = vi.hoisted(() => ({
  wallets: vi.fn(), quote: vi.fn(), order: vi.fn(), requestId: vi.fn(),
  toast: vi.fn(), confirm: vi.fn(), dialog: vi.fn(), push: vi.fn(),
  session: null as any, locale: null as any,
  ApiError: class extends Error {
    constructor(message: string, public code = -1, public responseReceived = false) { super(message); }
  },
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: state.push }) }));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key, locale: state.locale }) }));
vi.mock('vant', () => ({ showSuccessToast: state.toast, showConfirmDialog: state.confirm, showDialog: state.dialog }));
vi.mock('@/api', () => ({ api: { wallets: state.wallets, order: state.order }, requestId: state.requestId, ApiError: state.ApiError }));
vi.mock('@/api/product', () => ({ quoteProduct: state.quote }));
vi.mock('@/stores/session', () => ({ useSession: () => state.session }));
// 格式化与汇率在本组只提供稳定显示；报价、输入及提交状态机使用真实组件。
vi.mock('@/utils/format', () => ({
  money: (value: unknown) => value === undefined || value === null ? '—' : String(value),
  positiveAmount: (value: string) => /^\d+(?:\.\d{1,8})?$/.test(value) && Number(value) > 0,
}));
vi.mock('@/composables/useToken', () => ({ useToken: () => ({
  unitName: ref('token'),
  withUnit: (value: string) => value.replace(/\bToken\b/g, 'token'),
  tokenAmount: (value: unknown) => value === undefined || value === null ? '—' : String(value),
  tokenValue: (value: unknown) => value === undefined || value === null ? null : Number(value),
}) }));
import ProductPurchase from '../src/components/ProductPurchase.vue';

const apps: ReturnType<typeof createApp>[] = [];
const product: Product = {
  id: 7, name: '按份数购买项目', type: 'GPU', currency: 'USDT', enabled: true,
  purchaseMode: 'QUANTITY', unitPrice: '10', minAmount: '1', maxAmount: '1000',
  maxPurchaseQuantity: 10, maxPurchaseAmount: '0', durationDays: 30,
  dailyRateMin: '1', dailyRateMax: '1', settlementMode: 'C',
  description: '项目说明',
};
function quoted(quoteId: string, quantity = 1, income = '1.23456789'): ProductQuote {
  return {
    quoteId, productId: product.id, purchaseMode: 'QUANTITY', quantity, unitPriceToken: '10',
    expiresAt: new Date(Date.now() + 60_000).toISOString(), quotedAt: new Date().toISOString(),
    amount: String(quantity * 10), priceToken: String(quantity * 10),
    debitToken: String(quantity * 10), debitAmount: String(quantity * 10),
    currency: 'USDT', durationDays: 30, dailyRateMin: '1', dailyRateMax: '1', settlementMode: 'C',
    expectedIncomeMinToken: income, expectedIncomeMaxToken: income,
    principalReturnToken: String(quantity * 10),
  };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((complete) => { resolve = complete; });
  return { promise, resolve };
}
async function flush(milliseconds = 0) {
  await vi.advanceTimersByTimeAsync(milliseconds);
  await nextTick();
}
function input(container: HTMLElement) { return container.querySelector<HTMLInputElement>('.van-field input')!; }
function stepButton(container: HTMLElement, direction: 'increase' | 'decrease') {
  return container.querySelector<HTMLButtonElement>(`button[aria-label="user.v20Product.${direction}"]`)!;
}
function submitButton(container: HTMLElement) { return container.querySelector<HTMLButtonElement>('button[type="submit"]')!; }
function estimate(container: HTMLElement) { return container.querySelector('.ui-purchase-estimate > strong')?.textContent; }
async function submit(container: HTMLElement) {
  container.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  await flush();
}
function expectNoConfirmation() {
  expect(state.confirm).not.toHaveBeenCalled();
  expect(state.dialog).not.toHaveBeenCalled();
}
function switchSession(memberId: number, token: string) {
  // 与真实 setSession 一样，先清旧身份，再依次写入凭据和会员。
  state.session.token = '';
  state.session.member = null;
  state.session.token = token;
  state.session.member = { id: memberId };
}
function pending(memberId: number) {
  const value = sessionStorage.getItem(`token-user-product-pending:${memberId}:${product.id}`);
  return value ? JSON.parse(value) : undefined;
}
async function mount(options: { product?: Partial<Product>; purchasable?: boolean } = {}) {
  const container = document.createElement('div');
  const app = createApp(ProductPurchase, { product: { ...product, ...options.product }, purchasable: options.purchasable });
  app.config.globalProperties.$t = (key: string) => key;
  app.component('van-form', defineComponent({
    emits: ['submit'],
    setup: (_, { emit, slots }) => () => h('form', { onSubmit: (event: Event) => {
      event.preventDefault();
      // Vant 会先执行字段规则，验证通过后才触发业务 submit。
      if (!(event.currentTarget as HTMLFormElement).querySelector('[data-valid="false"]')) emit('submit');
    } }, slots.default?.()),
  }));
  app.component('van-field', defineComponent({
    inheritAttrs: false, props: ['modelValue', 'disabled', 'label', 'rules'], emits: ['update:modelValue'],
    setup: (props, { emit, slots }) => () => h('label', {
      class: 'van-field',
      'data-valid': (props.rules || []).every((rule: { validator?: (value: string) => boolean }) => !rule.validator || rule.validator(props.modelValue)) ? 'true' : 'false',
    }, [
      h('span', props.label),
      h('input', { value: props.modelValue, disabled: props.disabled, onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value) }),
      slots.extra?.(),
    ]),
  }));
  app.component('van-button', defineComponent({
    inheritAttrs: false, props: ['nativeType', 'disabled', 'loading'],
    setup: (props, { slots }) => () => h('button', { type: props.nativeType || 'button', disabled: props.disabled || props.loading }, slots.default?.()),
  }));
  app.component('van-icon', defineComponent({ render: () => h('span') }));
  app.component('van-popup', defineComponent({ setup: (_, { slots }) => () => h('div', { 'data-popup': true }, slots.default?.()) }));
  app.component('RouterLink', defineComponent({ props: ['to'], setup: (props, { slots }) => () => h('a', { href: props.to }, slots.default?.()) }));
  apps.push(app);
  app.mount(container);
  await flush();
  return container;
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-09-22T00:00:00Z'));
  vi.resetAllMocks();
  sessionStorage.clear();
  let sequence = 0;
  state.requestId.mockImplementation(() => `purchase-request-${++sequence}`);
  state.session = reactive({ token: 'customer-a', member: { id: 1 }, config: {} });
  state.locale = ref('zh');
  state.wallets.mockResolvedValue([{ type: 'PROJECT', currency: 'USDT', available: '10000', frozen: '0' }]);
  state.quote.mockImplementation((_productId: number, request: { quantity: string }) => Promise.resolve(quoted('quote-default', Number(request.quantity))));
  state.order.mockResolvedValue({ id: 101 });
  state.push.mockResolvedValue(undefined);
  state.confirm.mockResolvedValue(undefined);
  state.dialog.mockResolvedValue(undefined);
});
afterEach(() => {
  apps.splice(0).forEach((app) => app.unmount());
  vi.clearAllTimers();
  vi.useRealTimers();
});

describe('内嵌项目购买与报价重试', () => {
  it('项目停用时不报价或提交新购买', async () => {
    const view = await mount({ product: { enabled: false }, purchasable: true });
    await flush(500);
    expect(submitButton(view).disabled).toBe(true);
    await submit(view);
    expect(state.quote).not.toHaveBeenCalled();
    expect(state.order).not.toHaveBeenCalled();
  });
  it('直接展示购买表单，份数加减重新取得服务端预期收益', async () => {
    state.quote.mockResolvedValueOnce(quoted('quote-one', 1, '1.23456789'))
      .mockResolvedValueOnce(quoted('quote-two', 2, '42.42424242'))
      .mockResolvedValueOnce(quoted('quote-one-again', 1, '7.76543210'));
    const container = await mount();
    expect(container.querySelector('section.ui-inline-purchase form')).not.toBeNull();
    expect(container.querySelector('[data-popup]')).toBeNull();
    expect(input(container).value).toBe('1');
    expect(stepButton(container, 'decrease').disabled).toBe(true);
    await flush(301);
    expect(estimate(container)).toContain('1.23456789');
    stepButton(container, 'increase').click();
    await nextTick();
    expect(input(container).value).toBe('2');
    expect(estimate(container)).toBeUndefined();
    await flush(301);
    expect(state.quote.mock.calls[1]?.slice(0, 2)).toEqual([7, { quantity: '2' }]);
    expect(estimate(container)).toContain('42.42424242');
    stepButton(container, 'decrease').click();
    await flush(301);
    expect(state.quote.mock.calls[2]?.slice(0, 2)).toEqual([7, { quantity: '1' }]);
    expect(estimate(container)).toContain('7.76543210');
    expect(state.order).not.toHaveBeenCalled();
    expectNoConfirmation();
  });

  it('旧输入、旧账号及卸载后迟到的报价均不能回填', async () => {
    const oldInput = deferred<ProductQuote>(), oldAccount = deferred<ProductQuote>(), afterUnmount = deferred<ProductQuote>();
    state.quote.mockImplementationOnce(() => oldInput.promise)
      .mockResolvedValueOnce(quoted('quote-input-new', 2, '22.22'))
      .mockImplementationOnce(() => oldAccount.promise)
      .mockImplementationOnce(() => Promise.resolve(quoted('quote-account-new', 1, '44.44')))
      .mockImplementationOnce(() => afterUnmount.promise);
    const container = await mount();
    await flush(301);
    stepButton(container, 'increase').click();
    expect((state.quote.mock.calls[0]?.[2] as AbortSignal).aborted).toBe(true);
    await flush(301);
    oldInput.resolve(quoted('quote-input-old', 1, '999.99'));
    await flush();
    expect(input(container).value).toBe('2');
    expect(estimate(container)).toContain('22.22');
    stepButton(container, 'increase').click();
    await flush(301);
    switchSession(2, 'customer-b');
    await flush(301);
    expect((state.quote.mock.calls[2]?.[2] as AbortSignal).aborted).toBe(true);
    oldAccount.resolve(quoted('quote-account-old', 3, '888.88'));
    await flush();
    expect(input(container).value).toBe('1');
    expect(estimate(container)).toContain('44.44');
    expect(container.textContent).not.toContain('888.88');
    expect(state.wallets).toHaveBeenCalledTimes(2);
    stepButton(container, 'increase').click();
    await flush(301);
    apps.pop()!.unmount();
    expect((state.quote.mock.calls[4]?.[2] as AbortSignal).aborted).toBe(true);
    afterUnmount.resolve(quoted('quote-after-unmount', 2, '777.77'));
    await flush();
    expect(container.querySelector('.ui-inline-purchase')).toBeNull();
    expect(state.order).not.toHaveBeenCalled();
    expect(state.toast).not.toHaveBeenCalled();
  });

  it('结果不明时即使原报价过期仍用原请求重试，成功只提示并进入订单', async () => {
    state.order.mockRejectedValueOnce(new state.ApiError('请求超时，结果未知', -1, false));
    const container = await mount();
    await flush(301);
    expect(submitButton(container).disabled).toBe(false);
    await submit(container);
    const original = { ...state.order.mock.calls[0]![0] };
    expect(original).toEqual({ requestId: expect.any(String), productId: 7, quoteId: 'quote-default' });
    expect(input(container).disabled).toBe(true);
    expect(stepButton(container, 'increase').disabled).toBe(true);
    expect(container.textContent).toContain('user.v20Product.retryHint');
    state.locale.value = 'en';
    await flush(61_000);
    expect(state.quote).toHaveBeenCalledTimes(1);
    expect(submitButton(container).disabled).toBe(false);
    await submit(container);
    expect(state.order).toHaveBeenCalledTimes(2);
    expect(state.order.mock.calls[1]![0]).toEqual(original);
    expect(state.toast).toHaveBeenCalledTimes(1);
    expect(state.toast).toHaveBeenCalledWith('user.product.buySuccess');
    expect(state.push).toHaveBeenCalledTimes(1);
    expect(state.push).toHaveBeenCalledWith('/orders');
    expect(submitButton(container).disabled).toBe(true);
    expectNoConfirmation();
    await submit(container);
    expect(state.order).toHaveBeenCalledTimes(2);
  });

  it('明确业务拒绝解除重试锁并允许刷新报价，新购买采用新报价与新请求号', async () => {
    state.quote.mockResolvedValueOnce(quoted('quote-rejected'))
      .mockImplementationOnce(() => Promise.resolve(quoted('quote-refreshed', 1, '6.54321098')));
    state.order.mockRejectedValueOnce(new state.ApiError('报价已变化', 409, true));
    const container = await mount();
    await flush(301);
    await submit(container);
    const original = { ...state.order.mock.calls[0]![0] };
    expect(submitButton(container).disabled).toBe(true);
    expect(input(container).disabled).toBe(false);
    expect(container.textContent).toContain('报价已变化');
    expect(container.textContent).not.toContain('user.v20Product.retryHint');
    container.querySelector<HTMLButtonElement>('.ui-purchase-note .text-button')!.click();
    await flush();
    expect(state.quote).toHaveBeenCalledTimes(2);
    expect(estimate(container)).toContain('6.54321098');
    expect(submitButton(container).disabled).toBe(false);
    await submit(container);
    expect(state.order.mock.calls[1]![0].quoteId).toBe('quote-refreshed');
    expect(state.order.mock.calls[1]![0].requestId).not.toBe(original.requestId);
    expect(state.toast).toHaveBeenCalledTimes(1);
    expectNoConfirmation();
  });

  it('离页重挂恢复原号，报价过期、资格变化及401后同账号新凭据均保留原号复核', async () => {
    state.order.mockRejectedValueOnce(new state.ApiError('结果未知', -1, false))
      .mockRejectedValueOnce(new state.ApiError('登录已过期', 401, true));
    const first = await mount();
    await flush(301);
    stepButton(first, 'increase').click();
    await flush(301);
    await submit(first);
    const original = { ...state.order.mock.calls[0]![0] };
    expect(pending(1)).toMatchObject({ requestId: original.requestId, amount: '2', quote: { quoteId: original.quoteId } });
    apps.pop()!.unmount();
    await flush(61_000);
    state.wallets.mockRejectedValueOnce(new Error('钱包读取失败'));
    const restored = await mount({ product: { maxPurchaseQuantity: 1 }, purchasable: false });
    await flush(301);
    expect(input(restored).value).toBe('2');
    expect(input(restored).disabled).toBe(true);
    expect(restored.querySelector('.van-field')?.getAttribute('data-valid')).toBe('true');
    expect(restored.textContent).toContain('钱包读取失败');
    expect(restored.textContent).toContain('user.v20Product.retryHint');
    expect(state.quote).toHaveBeenCalledTimes(2);
    expect(submitButton(restored).disabled).toBe(false);
    await submit(restored);
    expect(state.order.mock.calls[1]![0]).toEqual(original);
    expect(pending(1)?.requestId).toBe(original.requestId);
    expect(state.toast).not.toHaveBeenCalled();
    apps.pop()!.unmount();
    switchSession(1, 'customer-a-new-token');
    const signedInAgain = await mount({ product: { maxPurchaseQuantity: 1 }, purchasable: false });
    await flush(301);
    expect(input(signedInAgain).value).toBe('2');
    expect(submitButton(signedInAgain).disabled).toBe(false);
    expect(state.quote).toHaveBeenCalledTimes(2);
    await submit(signedInAgain);
    expect(state.order).toHaveBeenCalledTimes(3);
    expect(state.order.mock.calls[2]![0]).toEqual(original);
    expect(pending(1)).toBeUndefined();
    expect(state.toast).toHaveBeenCalledTimes(1);
    expect(state.push).toHaveBeenCalledWith('/orders');
    expectNoConfirmation();
  });

  it('不同账号不恢复他人的未决购买，旧账号迟到成功只清理原记录', async () => {
    const oldOrder = deferred<unknown>();
    state.order.mockImplementationOnce(() => oldOrder.promise)
      .mockRejectedValueOnce(new state.ApiError('新账号购买结果未知', -1, false));
    state.quote.mockResolvedValueOnce(quoted('quote-customer-a', 1, '11.11'))
      .mockResolvedValueOnce(quoted('quote-customer-b', 1, '22.22'));
    const first = await mount();
    await flush(301);
    await submit(first);
    const originalA = { ...state.order.mock.calls[0]![0] };
    expect(pending(1)?.requestId).toBe(originalA.requestId);
    apps.pop()!.unmount();
    switchSession(2, 'customer-b');
    const second = await mount();
    expect(second.textContent).not.toContain('user.v20Product.retryHint');
    expect(estimate(second)).toBeUndefined();
    expect(pending(2)).toBeUndefined();
    await flush(301);
    expect(estimate(second)).toContain('22.22');
    expect(state.quote).toHaveBeenCalledTimes(2);
    await submit(second);
    const originalB = { ...state.order.mock.calls[1]![0] };
    expect(originalB.quoteId).toBe('quote-customer-b');
    expect(originalB.requestId).not.toBe(originalA.requestId);
    const secondPending = pending(2);
    expect(secondPending.requestId).toBe(originalB.requestId);
    oldOrder.resolve({ id: 201 });
    await flush();
    expect(pending(1)).toBeUndefined();
    expect(pending(2)).toEqual(secondPending);
    expect(second.textContent).toContain('user.v20Product.retryHint');
    expect(state.toast).not.toHaveBeenCalled();
    expect(state.push).not.toHaveBeenCalled();
    await submit(second);
    expect(state.order.mock.calls[2]![0]).toEqual(originalB);
    expect(pending(2)).toBeUndefined();
    expect(state.toast).toHaveBeenCalledTimes(1);
    expect(state.push).toHaveBeenCalledTimes(1);
    expectNoConfirmation();
  });
});
