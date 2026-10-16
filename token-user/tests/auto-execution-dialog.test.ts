import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';
import type { AutoExecution } from '../src/api/auto';

vi.mock('@/composables/useToken', () => ({ useToken: () => ({ tokenAmount: String, unitName: ref('token') }) }));
vi.mock('@/composables/useResponsivePopup', () => ({ useResponsivePopup: () => ({ popupPosition: ref('center') }) }));
vi.mock('@/utils/format', () => ({ date: String, money: String, tokenMillions: (value: unknown) => `${Number(value) / 1_000_000} M` }));
import AutoExecutionDialog from '../src/components/AutoExecutionDialog.vue';

const apps: ReturnType<typeof createApp>[] = [];
afterEach(() => apps.splice(0).forEach(app => app.unmount()));
const starts = Date.parse('2026-10-14T01:00:00Z');
function execution(): AutoExecution {
  return {
    id: 1, orderNo: 'compute-1', status: 'COMPLETED', currency: 'USDT', paidIncome: '1.25',
    createdAt: new Date(starts).toISOString(), executionFinishedAt: new Date(starts).toISOString(),
    animationEndsAt: new Date(starts + 10_000).toISOString(), detailsStatus: 'PENDING',
  };
}
function mount(selected: AutoExecution, now = starts) {
  const props = reactive({ show: true, selected, now, onRefresh: vi.fn() });
  const host = document.createElement('div');
  const app = createApp({ render: () => h(AutoExecutionDialog, props) });
  app.config.globalProperties.$t = (key: string) => key;
  const stub = (tag: string) => defineComponent({ setup: (_, { attrs, slots }) => () => h(tag, attrs, slots.default?.()) });
  app.component('van-popup', stub('section'));
  app.component('van-button', stub('button'));
  app.component('van-loading', stub('i'));
  app.component('van-icon', stub('i'));
  app.component('van-progress', defineComponent({ props: ['percentage'], setup: props => () => h('progress', { value: props.percentage }) }));
  app.mount(host);
  apps.push(app);
  return { props, host };
}

describe('Token 即时到账与本地动画', () => {
  it('无需旧引擎字段也能展示动画；动画结束后显示原已到账金额和待生成明细', async () => {
    const row = Object.freeze(execution());
    const { props, host } = mount(row);
    expect(host.textContent).toContain('user.v9.animationRunning');
    expect(host.querySelector('progress')).not.toBeNull();
    props.now = starts + 10_001;
    await nextTick();
    expect(host.textContent).toContain('+1.25 token');
    expect(host.textContent).toContain('user.v10.detailsPending');
    expect(host.querySelector('progress')).toBeNull();
    host.querySelector('button')!.click();
    expect(props.onRefresh).toHaveBeenCalledOnce();
    expect(row.paidIncome).toBe('1.25');
  });

  it('明细就绪展示持久模型用量，同时保留已到账收益', () => {
    const row: AutoExecution = { ...execution(), detailsStatus: 'READY', executionResult: {
      models: [{ name: 'model-a', provider: 'provider-a', allocationPercent: '100', tokenUsage: '1000000' }],
      tokenUsage: '1000000', inputTokens: '600000', outputTokens: '400000', executionState: 'SUCCEEDED',
      settledIncome: '1.25', expectedIncome: '1.25', currency: 'USDT',
    } };
    const { host } = mount(row, starts + 11_000);
    expect(host.textContent).toContain('model-a');
    expect(host.textContent).toContain('provider-a');
    expect(host.textContent).toContain('1 M Tokens');
    expect(host.textContent).toContain('+1.25 token');
    expect(host.textContent).not.toContain('user.v10.detailsPending');
  });
});
