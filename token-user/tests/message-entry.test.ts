import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick, reactive } from 'vue';
import Vant from 'vant';

const state = vi.hoisted(() => ({ session: null as any, list: vi.fn(), detail: vi.fn(), read: vi.fn(), unread: vi.fn() }));
vi.mock('@/stores/session', () => ({ useSession: () => state.session }));
vi.mock('@/api/presentation', () => ({ fetchUnreadCount: state.unread }));
vi.mock('@/api/content', async importOriginal => ({
  ...await importOriginal<typeof import('../src/api/content')>(),
  contentApi: { list: state.list, detail: state.detail, read: state.read },
}));
import App from '../src/App.vue';
import router from '../src/router';
import { i18n } from '../src/locales';

let app: ReturnType<typeof createApp> | undefined;
let view: HTMLDivElement;
async function settle() { for (let index = 0; index < 8; index++) { await Promise.resolve(); await nextTick(); } }
async function mount() {
  await router.push('/settings');
  await router.isReady();
  view = document.createElement('div'); document.body.append(view);
  app = createApp(App); app.use(router).use(i18n).use(Vant).mount(view);
  await settle();
}
function entry() { return view.querySelector<HTMLAnchorElement>('.topbar a.message-entry')!; }
beforeEach(() => {
  vi.clearAllMocks();
  state.session = reactive({
    token: 'isolated-account', authenticated: true, initialized: true,
    member: { id: 1, username: 'fixture', userLanguage: 'zh' }, siteName: 'Token',
    config: { language: 'zh', languages: [{ code: 'zh', name: '中文', nativeName: '中文' }], features: {}, presentation: { valuation: { fiatPerUsd: { USD: '1' } } } },
    initialize: async () => {}, loadMember: async () => {}, syncUserLanguage: async () => {}, clear: () => {},
  });
  i18n.global.locale.value = 'zh';
  state.list.mockResolvedValue({ records: [], total: 0 });
  state.read.mockResolvedValue(undefined);
  state.unread.mockResolvedValue({ notifications: 0, messages: 1, tasks: 0, total: 1 });
});
afterEach(() => { app?.unmount(); app = undefined; document.body.innerHTML = ''; });

describe('顶部信息入口真实路由链路', () => {
  it('保留原生链接及可访问名称，点击进入消息列表，再阅读详情只标记已读', async () => {
    const message = { id: 7, kind: 'MESSAGE', title: '已发布信息', bodyHtml: '<p>消息正文</p>', createdAt: '2026-10-08T00:00:00Z' };
    state.list.mockResolvedValue({ records: [message], total: 1 });
    state.detail.mockResolvedValue({ ...message });
    await mount();
    expect(entry().getAttribute('href')).toBe('/messages');
    expect(entry().getAttribute('aria-label')).toBe('我的消息');
    expect(entry().getAttribute('title')).toBe('我的消息');
    entry().click();
    await vi.waitFor(() => expect(view.textContent).toContain('已发布信息'));
    expect(router.currentRoute.value.path).toBe('/messages');
    expect(state.list).toHaveBeenCalledTimes(1);
    expect(state.list.mock.calls[0]).toEqual(['MESSAGE', 1, false, '', '', false, undefined]);
    view.querySelector<HTMLAnchorElement>('main a[href="/messages/7"]')!.click();
    await vi.waitFor(() => expect(view.textContent).toContain('消息正文'));
    expect(router.currentRoute.value.path).toBe('/messages/7');
    expect(state.detail).toHaveBeenCalledWith('MESSAGE', 7, false, '');
    expect(state.read).toHaveBeenCalledTimes(1);
    expect(state.read).toHaveBeenCalledWith(7, undefined, false);
  });

  it('读取失败能展示错误并重试到空列表，未读数为零不禁用入口', async () => {
    state.unread.mockResolvedValue({ notifications: 0, messages: 0, tasks: 0, total: 0 });
    state.list.mockRejectedValueOnce(new Error('隔离读取失败'));
    await mount(); entry().click();
    await vi.waitFor(() => expect(view.textContent).toContain('隔离读取失败'));
    const retry = [...view.querySelectorAll<HTMLButtonElement>('main button')].find(button => button.textContent?.includes('刷新'))!;
    retry.click();
    await vi.waitFor(() => expect(state.list).toHaveBeenCalledTimes(2));
    await settle();
    expect(view.textContent).not.toContain('隔离读取失败');
    expect(view.querySelector('main .van-empty')).not.toBeNull();
    expect(entry().getAttribute('href')).toBe('/messages');
  });

  it('未登录点击仍进入登录并保存消息页回跳，不读取私人消息', async () => {
    await mount();
    state.session.token = ''; state.session.authenticated = false; state.session.member = null;
    await settle(); entry().click();
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'), { timeout: 5000 });
    expect(router.currentRoute.value.query.redirect).toBe('/messages');
    expect(state.list).not.toHaveBeenCalled();
  });
});
