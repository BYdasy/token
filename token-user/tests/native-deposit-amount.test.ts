import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, nextTick } from 'vue';
import { nativeAmount } from '../src/utils/nativeAmount';

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key, te: () => false }) }));
vi.mock('vant', () => ({ showSuccessToast: vi.fn(), showFailToast: vi.fn() }));
vi.mock('@/composables/useToken', () => ({ useToken: () => ({ unitName: { value: 'token' } }) }));
vi.mock('@/utils/format', () => ({ copyText: vi.fn(), date: () => '', money: (value: unknown) => new Intl.NumberFormat('en', { maximumFractionDigits: 8 }).format(Number(value)) }));
vi.mock('@/utils/currency', () => ({ currencyLabel: (value: unknown) => String(value) }));
import BusinessRecords from '../src/components/BusinessRecords.vue';

const apps: ReturnType<typeof createApp>[] = [];
async function mount(kind: string, record: Record<string, unknown>) {
  const container = document.createElement('div');
  const app = createApp(BusinessRecords, { kind, records: [{ id: 1, ...record }] });
  app.config.globalProperties.$t = (key: string) => key;
  app.component('van-icon', defineComponent({ render: () => h('span') }));
  apps.push(app); app.mount(container); await nextTick(); return container;
}
afterEach(() => apps.splice(0).forEach(app => app.unmount()));

describe('逐笔充值的原币精确展示', () => {
  it('保留十八位小数和大整数，不经过Number；只移除无意义的末尾零', () => {
    expect(nativeAmount('11.696130139510566704')).toBe('11.696130139510566704');
    expect(nativeAmount('99999999999999999999.123456789012345678')).toBe('99999999999999999999.123456789012345678');
    expect(nativeAmount('0.000000000000000001')).toBe('0.000000000000000001');
    expect(nativeAmount('12.340000000000000000')).toBe('12.34');
    expect(nativeAmount('0.000000000000000000')).toBe('0');
    for (const value of [undefined, null, '', '1e-18', '<script>', NaN]) expect(nativeAmount(value)).toBe('—');
  });

  it('同一充值同时展示完整原币事实和服务端固定八位USD入账额', async () => {
    const view = await mount('deposits', { accountingModel: 'SINGLE_RECEIPT', amount: '11.696130139510566704', currency: 'USDT', status: 'APPROVED', accountingStatus: 'POSTED', accountingCurrency: 'USD', accountingAmount: '11.69613013' });
    expect(view.querySelector('.record-original-amount')?.textContent).toBe('11.696130139510566704 USDT');
    expect(view.querySelector('.record-amount strong')?.textContent).toBe('11.69613013 token');
  });

  it('极小的未入账原币不显示为零，不把原币数字冒充Token', async () => {
    const view = await mount('deposits', { accountingModel: 'SINGLE_RECEIPT', amount: '0.000000000000000001', currency: 'ETH', status: 'PENDING', accountingCurrency: 'USD', accountingStatus: 'AMOUNT_PRECISION' });
    expect(view.querySelector('.record-original-amount')?.textContent).toBe('0.000000000000000001 ETH');
    expect(view.querySelector('.record-amount strong')?.textContent).toBe('user.v22Accounting.notPosted');
  });

  it('旧协议充值和提款仍使用既有格式及提款净额', async () => {
    const legacy = await mount('deposits', { accountingModel: 'COLLECTION_BATCH', amount: '12.12345678', currency: 'USDT', status: 'APPROVED' });
    const withdrawal = await mount('withdrawals', { amount: '15', netAmount: '12.12345678', currency: 'USDT', status: 'APPROVED' });
    expect(legacy.querySelector('.record-original-amount')?.textContent).toBe('12.12345678 USDT');
    expect(withdrawal.querySelector('.record-original-amount')?.textContent).toBe('12.12345678 USDT');
  });
});
