import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, reactive, ref } from 'vue';
import { createMemoryHistory, createRouter, RouterView } from 'vue-router';
import { pageFilters } from '#/components/business/pageState';

const state = vi.hoisted(() => ({ query: vi.fn(), context: vi.fn(), access: {} as any, locale: {} as any, allowed: {} as any }));
vi.mock('#/api/merchant', () => ({ merchantQuery: state.query, merchantContext: state.context, merchantRecheck: vi.fn(), merchantSynchronize: vi.fn(), merchantConnectionCheck: vi.fn() }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => state.access }));
vi.mock('@vben/access', () => ({ useAccess: () => ({ hasAccessByCodes: () => state.allowed.value, hasAccessByRoles: () => false }) }));
vi.mock('@vben/locales', () => ({ useI18n: () => ({ locale: state.locale }) }));
vi.mock('#/components/business/shared', () => ({ useCompute: () => ({ c: (key: string) => key, date: String }), toUtc: (value: string) => value }));
vi.mock('@vben/common-ui', async () => { const { defineComponent, h } = await import('vue'); return { Page: defineComponent({ setup: (_, { slots }) => () => h('main', slots.default?.()) }) }; });
vi.mock('./MerchantEnergyAccount.vue', () => ({ default: { render: () => null } }));
import Merchant from './index.vue';

const cleanup: Array<() => void> = [];
async function settle() { for (let index = 0; index < 12; index++) { await Promise.resolve(); await nextTick(); } }
function response(resource: string, page: number) {
  return { ...(resource === 'resources' ? { view: 'CURRENT' } : {}), items: [{ bill_id: `bill-page-${page}`, request_id: `request-page-${page}`, batch_id: `batch-page-${page}`, operation_id: `operation-page-${page}`, entry_id: `entry-page-${page}`, event_type: `event-page-${page}` }], total: 65, page };
}
beforeEach(() => {
  vi.resetAllMocks();pageFilters.clear();pageFilters.setOwner('pagination-admin');
  state.access = reactive({ accessToken: 'admin-one' });state.locale = ref('zh');state.allowed = ref(true);
  state.context.mockResolvedValue({ global: true, enabled: true });
  state.query.mockImplementation((resource: string, params: any) => Promise.resolve(resource === 'filter-options' ? { items: [] } : response(resource, params.page || 1)));
});
afterEach(() => { cleanup.splice(0).forEach(dispose => dispose());pageFilters.clear(); });
async function mount(page: string, tab: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/merchant/test', component: Merchant, props: { page } }] });
  await router.push({ path: '/merchant/test', query: { merchantTab: tab } });await router.isReady();
  const host = document.createElement('div');document.body.append(host);
  const app = createApp({ render: () => h(RouterView) });app.use(router);app.directive('loading', {});app.mount(host);
  cleanup.push(() => { app.unmount();host.remove(); });await settle();return { host, router };
}
function button(host: HTMLElement, selector: string) { const found = host.querySelector<HTMLButtonElement>(selector);expect(found).not.toBeNull();return found!; }
function pageCalls(resource: string) { return state.query.mock.calls.filter(call => call[0] === resource); }
function shownSize(host: HTMLElement) { return host.querySelector('.el-pagination__sizes .el-select__wrapper')?.textContent?.trim(); }
async function chooseSize(host: HTMLElement, size: number) {
  const select = host.querySelector<HTMLElement>('.el-pagination__sizes .el-select__wrapper');
  expect(select).not.toBeNull();select!.click();await settle();
  const control = host.querySelector<HTMLInputElement>('.el-pagination__sizes input')!;
  const list = document.getElementById(control.getAttribute('aria-controls')!);
  expect(list).not.toBeNull();
  const options = [...list!.querySelectorAll<HTMLElement>('[role="option"]')];
  expect(options.map(option => Number.parseInt(option.textContent!, 10))).toEqual([10, 20, 50, 100]);
  const option = options.find(item => Number.parseInt(item.textContent!, 10) === size);
  expect(option).toBeDefined();option!.click();await settle();
}

describe('商户真实分页交互', () => {
  it('失败后标签往返保留成功结果及失败目标，详情查询失败不隐藏原列表分页', async () => {
    const { host, router } = await mount('collections', 'batches');
    button(host, '.el-pagination .btn-next').click();await settle();
    const batch = host.querySelector<HTMLInputElement>('input[aria-label="merchant.field.batch_id"]')!;
    batch.value = 'pending-detail';batch.dispatchEvent(new Event('input', { bubbles: true }));await settle();
    state.query.mockRejectedValueOnce(new Error('detail unavailable'));
    [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.includes('merchant.search'))!.click();await settle();
    expect(host.querySelector('.compute-pagination')).not.toBeNull();
    expect(host.textContent).toContain('batch-page-2');
    const calls = pageCalls('batches').length;
    await router.replace({ query: { merchantTab: 'sweeps' } });await settle();
    await router.replace({ query: { merchantTab: 'batches' } });await settle();
    expect(pageCalls('batches')).toHaveLength(calls);
    expect(host.textContent).toContain('batch-page-2');
    expect(host.textContent).toContain('merchant.loadFailed');
    expect(host.querySelector('.compute-pagination')).not.toBeNull();
    [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.includes('v12merchant.refresh'))!.click();await settle();
    expect(pageCalls('batches').at(-1)![1]).toMatchObject({ batch_id: 'pending-detail', page: 1, limit: 20 });
    expect(host.querySelector('.compute-pagination')).toBeNull();
  });

  it.each([['logs', 'bills'], ['logs', 'fee-ledger'], ['logs', 'notifications'], ['energy', 'resources'], ['collections', 'batches'], ['collections', 'sweeps']])('%s/%s 每页条数切换后请求真实size并回到第一页', async (page, tab) => {
    const { host } = await mount(page, tab);
    expect(host.querySelector('.business-list-page .compute-page .compute-list-card .compute-pagination')).not.toBeNull();
    expect(shownSize(host)).toContain('20');
    button(host, '.el-pagination .btn-next').click();await settle();
    await chooseSize(host, 50);
    expect(pageCalls(tab).at(-1)![1]).toMatchObject({ page: 1, limit: 50, page_size: 50 });
    expect(shownSize(host)).toContain('50');
    expect(host.querySelector('.el-pager .is-active')?.textContent?.trim()).toBe('1');
    button(host, '.el-pagination .btn-next').click();await settle();
    expect(pageCalls(tab).at(-1)![1]).toMatchObject({ page: 2, limit: 50, page_size: 50 });
    expect(button(host, '.el-pagination .btn-next').disabled).toBe(true);
  });

  it('10条与100条边界准确计算末页，切换不产生额外旧页请求', async () => {
    const { host } = await mount('collections', 'sweeps');
    await chooseSize(host, 10);
    const jump = host.querySelector<HTMLInputElement>('.el-pagination__jump input')!;
    jump.value = '7';jump.dispatchEvent(new Event('input', { bubbles: true }));jump.dispatchEvent(new Event('change', { bubbles: true }));await settle();
    expect(pageCalls('sweeps').at(-1)![1]).toMatchObject({ page: 7, limit: 10, page_size: 10 });
    expect(button(host, '.el-pagination .btn-next').disabled).toBe(true);
    const before = pageCalls('sweeps').length;
    await chooseSize(host, 100);
    expect(pageCalls('sweeps')).toHaveLength(before + 1);
    expect(pageCalls('sweeps').at(-1)![1]).toMatchObject({ page: 1, limit: 100, page_size: 100 });
    expect(shownSize(host)).toContain('100');expect(button(host, '.el-pagination .btn-next').disabled).toBe(true);
  });

  it('修改每页条数失败保留旧页和条数，刷新重试目标size', async () => {
    const { host } = await mount('logs', 'bills');
    button(host, '.el-pagination .btn-next').click();await settle();
    state.query.mockRejectedValueOnce(new Error('response too large'));
    await chooseSize(host, 100);
    expect(pageCalls('bills').at(-1)![1]).toMatchObject({ page: 1, limit: 100 });
    expect(shownSize(host)).toContain('20');expect(host.textContent).toContain('bill-page-2');
    expect(host.querySelector('.el-pager .is-active')?.textContent).toBe('2');
    [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.includes('v12merchant.refresh'))!.click();await settle();
    expect(pageCalls('bills').at(-1)![1]).toMatchObject({ page: 1, limit: 100, page_size: 100 });
    expect(shownSize(host)).toContain('100');expect(host.textContent).toContain('bill-page-1');
  });

  it('大页失败后仍可选择更小size恢复，查询也可用已展示size恢复', async () => {
    const { host } = await mount('energy', 'resources');
    state.query.mockRejectedValueOnce(new Error('response too large'));
    await chooseSize(host, 100);
    await chooseSize(host, 10);
    expect(pageCalls('resources').at(-1)![1]).toMatchObject({ page: 1, limit: 10 });
    expect(shownSize(host)).toContain('10');expect(host.textContent).not.toContain('merchant.loadFailed');
    state.query.mockRejectedValueOnce(new Error('response too large'));
    await chooseSize(host, 100);
    [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.includes('merchant.search'))!.click();await settle();
    expect(pageCalls('resources').at(-1)![1]).toMatchObject({ page: 1, limit: 10 });
    expect(shownSize(host)).toContain('10');expect(host.textContent).not.toContain('merchant.loadFailed');
  });

  it('100条请求失败后可直接选第2页，使用已展示20条和已提交筛选', async () => {
    const { host } = await mount('logs', 'bills');
    state.query.mockRejectedValueOnce(new Error('response too large'));
    await chooseSize(host, 100);
    const address = host.querySelector<HTMLInputElement>('input[aria-label="merchant.field.address"]')!;
    address.value = 'unsent-draft';address.dispatchEvent(new Event('input', { bubbles: true }));await settle();
    const second = [...host.querySelectorAll<HTMLElement>('.el-pager .number')].find(item => item.textContent?.trim() === '2')!;
    second.click();await settle();
    expect(pageCalls('bills').at(-1)![1]).toMatchObject({ page: 2, limit: 20, page_size: 20 });
    expect(pageCalls('bills').at(-1)![1]).not.toHaveProperty('address');
    expect(shownSize(host)).toContain('20');expect(host.textContent).toContain('bill-page-2');
    expect(host.textContent).not.toContain('merchant.loadFailed');
  });

  it('大页失败后重新进入仍重试目标size，查询可从默认20条恢复', async () => {
    const { host } = await mount('logs', 'bills');
    state.query.mockRejectedValueOnce(new Error('response too large'));
    await chooseSize(host, 100);
    cleanup.splice(0).forEach(dispose => dispose());
    state.query.mockImplementation((resource: string, params: any) => resource !== 'filter-options' && params.limit === 100
      ? Promise.reject(new Error('response too large')) : Promise.resolve(resource === 'filter-options' ? { items: [] } : response(resource, params.page || 1)));
    const restored = await mount('logs', 'bills');
    expect(pageCalls('bills').at(-1)![1]).toMatchObject({ page: 1, limit: 100, page_size: 100 });
    [...restored.host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.includes('merchant.search'))!.click();await settle();
    expect(pageCalls('bills').at(-1)![1]).toMatchObject({ page: 1, limit: 20, page_size: 20 });
    expect(shownSize(restored.host)).toContain('20');expect(restored.host.textContent).not.toContain('merchant.loadFailed');
  });

  it('各Tab缓存独立的size与页码，重新挂载恢复已请求参数', async () => {
    const { host, router } = await mount('logs', 'bills');
    await chooseSize(host, 50);button(host, '.el-pagination .btn-next').click();await settle();
    await router.replace({ query: { merchantTab: 'fee-ledger' } });await settle();
    expect(pageCalls('fee-ledger').at(-1)![1]).toMatchObject({ page: 1, limit: 20 });
    await chooseSize(host, 10);button(host, '.el-pagination .btn-next').click();await settle();
    await router.replace({ query: { merchantTab: 'bills' } });await settle();
    expect(pageCalls('bills')).toHaveLength(3);
    expect(shownSize(host)).toContain('50');expect(host.querySelector('.el-pager .is-active')?.textContent).toBe('2');
    await router.replace({ query: { merchantTab: 'fee-ledger' } });await settle();
    expect(pageCalls('fee-ledger')).toHaveLength(3);
    expect(shownSize(host)).toContain('10');expect(host.querySelector('.el-pager .is-active')?.textContent).toBe('2');
    cleanup.splice(0).forEach(dispose => dispose());
    const restored = await mount('logs', 'bills');
    expect(pageCalls('bills').at(-1)![1]).toMatchObject({ page: 2, limit: 50 });expect(shownSize(restored.host)).toContain('50');
  });

  it('调整条数只使用已提交筛选，迟到的大页响应不能覆盖重置后的页面', async () => {
    const { host } = await mount('logs', 'bills');
    const address = host.querySelector<HTMLInputElement>('input[aria-label="merchant.field.address"]')!;
    address.value = 'draft-address';address.dispatchEvent(new Event('input', { bubbles: true }));await settle();
    let finish!: (value: any) => void;state.query.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    await chooseSize(host, 100);
    expect(pageCalls('bills').at(-1)![1]).not.toHaveProperty('address');
    [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.includes('merchant.reset'))!.click();await settle();
    expect(pageCalls('bills').at(-1)![1]).toMatchObject({ page: 1, limit: 20 });
    finish({ ...response('bills', 1), items: [{ bill_id: 'late-large-page' }] });await settle();
    expect(shownSize(host)).toContain('20');expect(host.textContent).not.toContain('late-large-page');
  });

  it.each([['logs', 'bills'], ['logs', 'fee-ledger'], ['logs', 'notifications'], ['energy', 'resources'], ['collections', 'batches'], ['collections', 'sweeps']])('%s/%s 点击下一页和页码按接口读取实际页面', async (page, tab) => {
    const { host } = await mount(page, tab);
    button(host, '.el-pagination .btn-next').click();await settle();
    expect(pageCalls(tab)).toHaveLength(2);expect(pageCalls(tab)[1]![1]).toMatchObject({ page: 2, limit: 20 });
    expect(pageCalls(tab)[1]![1]).not.toHaveProperty('before_id');
    expect(host.querySelector('.el-pager .is-active')?.textContent).toBe('2');
    const fourth = [...host.querySelectorAll<HTMLElement>('.el-pager .number')].find(item => item.textContent === '4');
    expect(fourth).toBeDefined();fourth!.click();await settle();
    expect(pageCalls(tab).at(-1)![1].page).toBe(4);expect(host.querySelector('.el-pager .is-active')?.textContent).toBe('4');
  });

  it('下一页失败保留上一成功页和总数，可重试同一目标页，不自动跳回第一页', async () => {
    const { host } = await mount('energy', 'resources');
    state.query.mockRejectedValueOnce(new Error('remote unavailable'));
    button(host, '.el-pagination .btn-next').click();await settle();
    expect(pageCalls('resources')).toHaveLength(2);
    expect(host.textContent).toContain('request-page-1');expect(host.textContent).toContain('merchant.loadFailed');
    expect(host.querySelector('.el-pager .is-active')?.textContent?.trim()).toBe('1');
    const retry = [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.includes('v12merchant.refresh'));
    expect(retry).toBeDefined();retry!.click();await settle();
    expect(pageCalls('resources').at(-1)![1].page).toBe(2);expect(host.textContent).toContain('request-page-2');
  });

  it('切换日志Tab恢复该Tab已读页面，不重查或被空total夹回第一页', async () => {
    const { host, router } = await mount('logs', 'bills');
    button(host, '.el-pagination .btn-next').click();await settle();
    await router.replace({ query: { merchantTab: 'fee-ledger' } });await settle();
    expect(pageCalls('fee-ledger')[0]![1].page).toBe(1);
    await router.replace({ query: { merchantTab: 'bills' } });await settle();
    expect(pageCalls('bills')).toHaveLength(2);expect(host.textContent).toContain('bill-page-2');
    expect(host.querySelector('.el-pager .is-active')?.textContent).toBe('2');
  });

  it('旧页迟到不能覆盖搜索重置的新页面', async () => {
    const { host } = await mount('logs', 'bills');
    let finish!: (value: any) => void;state.query.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    button(host, '.el-pagination .btn-next').click();await settle();
    const reset = [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.includes('merchant.reset'))!;
    reset.click();await settle();finish(response('bills', 2));await settle();
    expect(host.textContent).toContain('bill-page-1');expect(host.textContent).not.toContain('bill-page-2');
    expect(host.querySelector('.el-pager .is-active')?.textContent?.trim()).toBe('1');
  });

  it('旧Crypto缺少归集total时明确失败，不用当前页长度冒充总数', async () => {
    state.query.mockImplementation((resource: string) => Promise.resolve(resource === 'filter-options' ? { items: [] } : { items: [{ batch_id: 'old' }], has_more: true, next_cursor: 'old' }));
    const { host } = await mount('collections', 'batches');
    expect(host.textContent).toContain('merchant.loadFailed');expect(host.querySelector('.el-pagination')).toBeNull();
  });

  it('翻页使用已查询筛选，修改草稿后点击查询才从第一页应用', async () => {
    const { host } = await mount('logs', 'bills');
    const address = host.querySelector<HTMLInputElement>('input[aria-label="merchant.field.address"]')!;
    address.value = 'new-address';address.dispatchEvent(new Event('input', { bubbles: true }));await settle();
    button(host, '.el-pagination .btn-next').click();await settle();
    expect(pageCalls('bills').at(-1)![1]).not.toHaveProperty('address');
    const search = [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.includes('merchant.search'))!;
    search.click();await settle();
    expect(pageCalls('bills').at(-1)![1]).toMatchObject({ page: 1, address: 'new-address' });
  });

  it('翻页中禁用重复操作且权限撤销后迟到数据不回填', async () => {
    const { host } = await mount('logs', 'bills');
    let finish!: (value: any) => void;state.query.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    button(host, '.el-pagination .btn-next').click();await settle();
    expect(button(host, '.el-pagination .btn-next').disabled).toBe(true);
    button(host, '.el-pagination .btn-next').click();expect(pageCalls('bills')).toHaveLength(2);
    state.allowed.value = false;await settle();finish(response('bills', 2));await settle();
    expect(host.querySelector('.el-pagination')).toBeNull();expect(host.textContent).not.toContain('bill-page-2');
  });

  it('首次进入另一商户页面不沿用资源第4页及已提交筛选', async () => {
    const { host, router } = await mount('energy', 'resources');
    const address = host.querySelector<HTMLInputElement>('input[aria-label="merchant.field.address"]')!;
    address.value = 'energy-only-address';address.dispatchEvent(new Event('input', { bubbles: true }));await settle();
    [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.includes('merchant.search'))!.click();await settle();
    [...host.querySelectorAll<HTMLElement>('.el-pager .number')].find(item => item.textContent === '4')!.click();await settle();
    expect(pageCalls('resources').at(-1)![1]).toMatchObject({ page: 4, address: 'energy-only-address' });
    router.addRoute({ path: '/merchant/collections', component: Merchant, props: { page: 'collections' } });
    await router.push('/merchant/collections');await settle();
    expect(pageCalls('batches')).toHaveLength(1);expect(pageCalls('batches')[0]![1].page).toBe(1);
    expect(pageCalls('batches')[0]![1]).not.toHaveProperty('address');
    expect(host.querySelector('.el-pager .is-active')?.textContent?.trim()).toBe('1');
  });

  it('重置请求失败后刷新仍使用第一页空筛选，不恢复失败前的已提交条件', async () => {
    const { host } = await mount('logs', 'bills');
    const address = host.querySelector<HTMLInputElement>('input[aria-label="merchant.field.address"]')!;
    address.value = 'old-address';address.dispatchEvent(new Event('input', { bubbles: true }));await settle();
    [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.includes('merchant.search'))!.click();await settle();
    [...host.querySelectorAll<HTMLElement>('.el-pager .number')].find(item => item.textContent === '4')!.click();await settle();
    state.query.mockRejectedValueOnce(new Error('reset unavailable'));
    [...host.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.includes('merchant.reset'))!.click();await settle();
    expect(host.querySelector('.el-pager .is-active')?.textContent?.trim()).toBe('4');
    cleanup.splice(0).forEach(dispose => dispose());
    await mount('logs', 'bills');
    expect(pageCalls('bills').at(-1)![1].page).toBe(1);expect(pageCalls('bills').at(-1)![1]).not.toHaveProperty('address');
  });

  it('页码输入框提交第4页会查询并更新已展示页', async () => {
    const { host } = await mount('collections', 'sweeps');
    button(host, '.el-pagination .btn-next').click();await settle();
    const input = host.querySelector<HTMLInputElement>('.el-pagination__jump input')!;
    expect(input).not.toBeNull();input.value = '4';input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));await settle();
    expect(pageCalls('sweeps').at(-1)![1].page).toBe(4);
    expect(host.querySelector('.el-pager .is-active')?.textContent?.trim()).toBe('4');
  });

  it('复用商户组件从能量进入日志时不被旧资源参数重定向回能量', async () => {
    const { router } = await mount('energy', 'resources');
    router.addRoute({ path: '/merchant/logs', component: Merchant, props: { page: 'logs' } });
    router.addRoute({ path: '/merchant/energy', component: Merchant, props: { page: 'energy' } });
    await router.push('/merchant/logs');await settle();
    expect(router.currentRoute.value.path).toBe('/merchant/logs');
    expect(pageCalls('bills')).toHaveLength(1);expect(pageCalls('bills')[0]![1].page).toBe(1);
    expect(pageCalls('bills')[0]![1]).not.toHaveProperty('request_id');
  });
});
