import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, ref } from 'vue';

const state = vi.hoisted(() => ({ progress: undefined as unknown }));
vi.mock('@/stores/session', () => ({ useSession: () => ({ member: { level: 1 } }) }));
vi.mock('@/composables/useToken', () => ({ useToken: () => ({ tokenAmount: String, unitName: ref('Vora') }) }));
vi.mock('@/composables/useProductCatalog', () => ({ useProductCatalog: () => ({
  catalog: ref({ levels: [{ id: 1, name: 'Starter' }, { id: 2, name: 'Explorer' }] }),
  task: { loading: ref(false) }, visibleError: ref(''), load: vi.fn(),
}) }));
vi.mock('@/composables/useVipAccount', () => ({ useVipAccount: () => ({
  presentation: ref(state.progress ? { vipProgress: state.progress } : undefined),
  loading: ref(false), error: ref(''), loadAccount: vi.fn(),
}) }));
vi.mock('@/components/VipLevelList.vue', () => ({ default: { render: () => null } }));
vi.mock('@/components/PageState.vue', () => ({ default: defineComponent({ setup: (_, { slots }) => () => slots.default?.() }) }));
import LevelsView from '../src/views/LevelsView.vue';

const apps: ReturnType<typeof createApp>[] = [];
afterEach(() => apps.splice(0).forEach(app => app.unmount()));
function mount(progress: unknown) {
  state.progress = progress;
  const host = document.createElement('div');
  const app = createApp(LevelsView);
  app.config.globalProperties.$t = (key: string) => key;
  app.component('RouterLink', defineComponent({ setup: (_, { slots }) => () => h('a', slots.default?.()) }));
  app.component('van-icon', { render: () => h('i') });
  app.component('van-loading', { render: () => h('i') });
  app.mount(host);
  apps.push(app);
  return host;
}
const snapshot = {
  currentLevel: 1, nextLevel: 2, availableBalance: '750', threshold: '1000', remaining: '250',
  currency: 'USDT', percent: '0', requiredDirectMembers: 4, qualifiedDirectMembers: 0,
  remainingDirectMembers: 4, directMemberRequirementMet: false,
};

describe('等级中心升级条件展示', () => {
  it('成员不足时仍呈现真实的余额进度，不将两个条件的最小值当作余额进度', () => {
    const host = mount(snapshot);
    const bars = [...host.querySelectorAll('progress')];
    expect(bars.map(bar => bar.value)).toEqual([75, 0]);
    expect(host.textContent).toContain('750 / 1000 Vora');
  });
  it('余额超过门槛时不超过100%，有效直推另按持久人数展示', () => {
    const host = mount({ ...snapshot, availableBalance: '1500', remaining: '0', qualifiedDirectMembers: 2 });
    expect([...host.querySelectorAll('progress')].map(bar => bar.value)).toEqual([100, 50]);
  });
  it('尚未取得账户数据时不伪造已完成进度', () => {
    const host = mount(undefined);
    expect(host.querySelector('progress')).toBeNull();
    expect(host.textContent).not.toContain('user.ui.products.highestLevel');
  });
});
