import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, nextTick } from 'vue';

const words = vi.hoisted(() => ({
  'user.payment.state.REVIEW': 'Waiting for transaction verification and review',
  'user.payment.state.PAID': '已到账',
  'user.common.network': 'Network',
} as Record<string, string>));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => words[key] || key, te: (key: string) => key in words }) }));
vi.mock('vant', () => ({ showSuccessToast: vi.fn(), showFailToast: vi.fn() }));
vi.mock('@/composables/useToken', () => ({ useToken: () => ({ unitName: { value: 'token' } }) }));
vi.mock('@/utils/format', () => ({ copyText: vi.fn(), date: () => '2026-10-08 10:30', money: (value: unknown) => String(value ?? '—') }));
import BusinessRecords from '../src/components/BusinessRecords.vue';

const apps: ReturnType<typeof createApp>[] = [];
async function mount(kind: string, record: Record<string, unknown>) {
  const container = document.createElement('div');
  const app = createApp(BusinessRecords, { kind, records: [{ id: 1, ...record }] });
  app.config.globalProperties.$t = (key: string) => words[key] || key;
  app.component('van-icon', defineComponent({ render: () => h('span') }));
  apps.push(app); app.mount(container); await nextTick(); return container;
}
afterEach(() => apps.splice(0).forEach(app => app.unmount()));

describe('充提记录网络与状态', () => {
  it.each(['deposits', 'withdrawals'])('%s 将完整文字状态放在网络行，不占金额列', async (kind) => {
    const view = await mount(kind, { currency: 'USDT', network: 'Arbitrum One', amount: '18', netAmount: '17', accountingCurrency: 'USD', accountingAmount: '18', accountingNetAmount: '17', status: 'PROCESSING', paymentStatus: 'MCAG_REVIEW' });
    expect(view.querySelector('.record-network-details .pill')?.textContent).toBe('Arbitrum One');
    expect(view.querySelector('.record-network-details .status-tag.review')?.textContent).toBe(words['user.payment.state.REVIEW']);
    expect(view.querySelector('.record-amount .status-tag')).toBeNull();
    expect(view.querySelector('.record-amount strong')?.textContent).toBe(kind === 'withdrawals' ? '17 token' : 'user.v22Accounting.notPosted');
    expect(view.querySelector('.record-heading')?.textContent).not.toContain('USDT');
    expect(view.querySelector('.details-list')?.textContent).toContain(kind === 'withdrawals' ? '17 USDT' : '18 USDT');
  });

  it('旧记录缺网络时仍显示状态和网络缺值，不丢失结果', async () => {
    const view = await mount('withdrawals', { currency: 'USDT', status: 'APPROVED', paymentStatus: 'MCAG_SUCCEEDED' });
    expect(view.querySelector('.record-network-details')?.textContent).toBe('—已到账');
    expect(view.querySelectorAll('.status-tag')).toHaveLength(1);
  });

  it('历史充提使用易读的 Base 名称，测试网仍保留准确编号', async () => {
    const base = await mount('deposits', { currency: 'USDC', network: 'BASE_MAINNET', status: 'APPROVED' });
    const testnet = await mount('withdrawals', { currency: 'USDC', network: 'BASE_SEPOLIA', status: 'APPROVED' });
    expect(base.querySelector('.record-network-details .pill')?.textContent).toBe('Base');
    expect(testnet.querySelector('.record-network-details .pill')?.textContent).toBe('BASE_SEPOLIA');
  });

  it('资金流水保持钱包行、独立流水号和金额，既不添加网络行也不添加状态', async () => {
    const view = await mount('ledger', { amount: '3', currency: 'USDT', walletType: 'EARNINGS', ledgerNo: 'LG-legacy', kind: 'ORDER_INCOME', status: 'APPROVED', network: 'TRON' });
    expect(view.querySelector('.record-network-row')).toBeNull();
    expect(view.querySelector('.status-tag')).toBeNull();
    expect(view.querySelector('.record-amount strong')?.textContent).toBe('3 token');
    expect(view.textContent).toContain('user.wallet.EARNINGS');
    expect(view.textContent).toContain('LG-legacy');
  });
});
