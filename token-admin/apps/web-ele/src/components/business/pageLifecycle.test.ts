import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { computed, createApp, defineComponent, h, KeepAlive, nextTick, onMounted, reactive } from 'vue';
import { createMemoryHistory, createRouter, RouterView } from 'vue-router';

const fetchPage = vi.hoisted(() => vi.fn());
const access = vi.hoisted(() => ({ accessToken: 'test-token' }));
vi.mock('#/api/compute', () => ({ fetchComputePage: fetchPage }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => access }));
vi.mock('#/components/business/shared', () => ({ toUtc: (value?: string) => value }));
import { usePaged } from './usePaged';
import { pageFilters } from './pageState';
import { useFilterRecovery } from './useFilterRecovery';

let app: ReturnType<typeof createApp> | undefined;
let state: ReturnType<typeof usePaged>;
async function mountList() {
  const list = defineComponent({ name: 'MemberList', setup() {
    state = usePaged(computed(() => 'members' as const), () => true);
    onMounted(() => void state.load());
    return () => h('div', state.rows.value.map(row => row.username).join(','));
  } });
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/members', component: list }, { path: '/other', component: { render: () => h('div') } },
  ] });
  await router.push('/members'); await router.isReady();
  app = createApp({ render: () => h(RouterView, {}, { default: ({ Component }: any) => h(KeepAlive, null, [h(Component)]) }) });
  app.use(router).mount(document.createElement('div'));
  await nextTick(); await nextTick();
  return router;
}
beforeEach(() => { pageFilters.clear(); pageFilters.setOwner('account-1'); access.accessToken = 'test-token'; fetchPage.mockReset().mockResolvedValue({ records: [{ username: 'visible-result' }], total: 1 }); });
afterEach(() => { app?.unmount(); app = undefined; });

describe('真实组件列表生命周期', () => {
  it('标签往返保留筛选和结果，不额外请求；刷新重建后只恢复筛选并重新查询', async () => {
    const router = await mountList();
    state.query.username = 'exact-member'; state.query.usernameMatchMode = 'exact'; await state.search();
    const calls = fetchPage.mock.calls.length;
    await router.push('/other'); await nextTick(); await router.push('/members'); await nextTick();
    expect(fetchPage).toHaveBeenCalledTimes(calls);
    expect(state.query.username).toBe('exact-member'); expect(state.rows.value[0]?.username).toBe('visible-result');
    app!.unmount(); app = undefined; await mountList();
    expect(fetchPage).toHaveBeenCalledTimes(calls + 1);
    expect(fetchPage.mock.lastCall?.[1]).toEqual(expect.objectContaining({ username: 'exact-member', usernameMatchMode: 'exact' }));
  });
  it('财务五项筛选的草稿和已提交值分别恢复，日期组件清为null后可保存并彻底重置', async () => {
    await mountList();
    const fields = { attentionIgnored: 'true', gatewayReference: 'gateway', receiverAddress: 'address', txHash: 'hash', detailType: 'WITHDRAW' };
    Object.assign(state.query, fields); state.dates.value = ['start', 'end']; await state.search();
    state.query.gatewayReference = 'unsent-draft'; state.pager.page = 3; await nextTick();
    app!.unmount(); app = undefined; await mountList();
    expect(state.query).toMatchObject({ ...fields, gatewayReference: 'unsent-draft' });
    expect(fetchPage.mock.lastCall?.[1]).toMatchObject({ ...fields, page: 3 });
    state.dates.value = null; await nextTick();
    expect(pageFilters.get(JSON.stringify(['/members', 'members', {}]))?.dates).toEqual([]);
    await state.reset();
    for (const key of Object.keys(fields)) expect(fetchPage.mock.lastCall?.[1][key]).toBeUndefined();
    expect(fetchPage.mock.lastCall?.[1]).toMatchObject({ page: 1, startTime: undefined, endTime: undefined });
    expect(state.dates.value).toEqual([]);
  });
  it('关闭标签清理筛选；主动在线入口仍能清除恢复的所有条件', async () => {
    await mountList(); state.query.username = 'old'; state.dates.value = ['a', 'b']; await state.search();
    pageFilters.close(['/members']); app!.unmount(); app = undefined; await mountList();
    expect(state.query.username).toBe(''); expect(state.dates.value).toEqual([]);
    state.query.username = 'old'; state.query.status = 'PENDING'; state.query.usernameMatchMode = 'exact';
    state.resetFilters(); state.query.online = 'true'; await state.search();
    expect(fetchPage.mock.lastCall?.[1]).toEqual(expect.objectContaining({ online: 'true', username: undefined, status: undefined, usernameMatchMode: 'contains' }));
  });
  it('已卸载页面的迟到请求不会回填', async () => {
    let resolve!: (value: unknown) => void;
    fetchPage.mockImplementationOnce(() => new Promise(done => { resolve = done; }));
    await mountList(); const previous = state;
    app!.unmount(); app = undefined;
    resolve({ records: [{ username: 'late-result' }], total: 1 }); await nextTick(); await nextTick();
    expect(previous.rows.value).toEqual([]);
  });
  it('不同内部分类的筛选独立，刷新后能恢复但不包含列表结果', async () => {
    const tab = reactive({ name: 'first' }); const query = reactive({ keyword: '' });
    const component = defineComponent({ setup() { useFilterRecovery(() => tab.name, { query }); return () => h('div'); } });
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component }] });
    await router.push('/'); await router.isReady(); app = createApp(RouterView); app.use(router).mount(document.createElement('div'));
    query.keyword = 'first-query'; await nextTick(); tab.name = 'second'; await nextTick();
    expect(query.keyword).toBe(''); query.keyword = 'second-query'; await nextTick(); tab.name = 'first'; await nextTick();
    expect(query.keyword).toBe('first-query');
    expect(pageFilters.get(JSON.stringify(['/', 'filters', 'second']))?.query).toEqual({ query: { keyword: 'second-query' } });
  });
});
