import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick } from 'vue';
import MerchantDetails from './MerchantDetails.vue';

vi.mock('#/components/business/shared', async () => {
  const { default: baseline } = await import('../../../../../../docs/i18n/current-admin-zh.json');
  const messages: Record<string, string> = baseline;
  return { useCompute: () => ({ c: (key: string) => messages[`admin.compute.${key}`] || key, date: String }) };
});

const cleanup: Array<() => void> = [];
const reason = { reason_code: 'MERCHANT_POOL_UNAVAILABLE' };
const collectionLabel = '等待可用且足额的补费钱包';
const payoutLabel = '商户打款钱包余额不足或暂不可用';

afterEach(() => cleanup.splice(0).forEach(dispose => dispose()));
async function mount(value: Record<string, unknown>, resource?: string) {
  const host = document.createElement('div');document.body.append(host);
  const app = createApp({ render: () => h(MerchantDetails, { value, resource }) });app.mount(host);
  cleanup.push(() => { app.unmount();host.remove(); });
  await nextTick();return host;
}

describe('商户详情原因上下文', () => {
  it('账单、代付和无上下文详情不从相同字段猜归集语义', async () => {
    for (const resource of ['bills', 'payouts', undefined]) {
      const host = await mount({ ...reason, display_status: 'QUEUED', business_status: 'READY' }, resource);
      expect(host.textContent).toContain(payoutLabel);
      expect(host.textContent).not.toContain(collectionLabel);
      expect(host.querySelector('.collection-status')).toBeNull();
    }
  });

  it('归集批次与执行单的详情均展示补费原因，排队不改写为终态', async () => {
    for (const resource of ['batches', 'sweeps']) {
      const value = Object.freeze({ ...reason, display_status: 'QUEUED', business_status: 'READY', sweep_status: 'QUEUED' });
      const host = await mount(value, resource);
      expect(host.textContent).toContain(collectionLabel);
      expect(host.textContent).toContain('已排队');
      expect(host.querySelectorAll('.collection-status')).toHaveLength(4);
      expect(value.sweep_status).toBe('QUEUED');
    }
  });

  it('批次的收款、任意载荷和订单不继承归集补费语义，嵌套 collection 明确继承', async () => {
    const host = await mount({ ...reason, items: [{ ...reason }], collection: { ...reason }, payload: { ...reason }, order: { ...reason } }, 'batches');
    const sections = [...host.querySelectorAll('.merchant-details')].map(item => item.textContent);
    expect(sections).toHaveLength(5);
    expect(sections[0]).toContain(collectionLabel);
    expect(sections[1]).toContain(payoutLabel);
    expect(sections[2]).toContain(collectionLabel);
    expect(sections[3]).toContain(payoutLabel);
    expect(sections[4]).toContain(payoutLabel);
  });

  it('其他详情中的明确 collection 子对象独立显示补费原因', async () => {
    const host = await mount({ ...reason, collection: { ...reason } }, 'bills');
    const sections = [...host.querySelectorAll('.merchant-details')].map(item => item.textContent);
    expect(sections[0]).toContain(payoutLabel);
    expect(sections[1]).toContain(collectionLabel);
  });
});
