import type { CompanyFile } from '#/api/company-files';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, reactive } from 'vue';

const state = vi.hoisted(() => ({ download: vi.fn(), access: null as any }));
vi.mock('#/api/company-files', () => ({ downloadCompanyFile: state.download }));
vi.mock('@vben/locales', () => ({ useI18n: () => ({ t: (key: string) => key }) }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => state.access }));
import CompanyVideoPreview from './CompanyVideoPreview.vue';

const cleanup: (() => void)[] = [];
const browserSettings = (window as unknown as { happyDOM: { settings: { navigation: { disableChildFrameNavigation: boolean } } } }).happyDOM.settings;
const previousIframeLoading = browserSettings.navigation.disableChildFrameNavigation;
beforeAll(() => { browserSettings.navigation.disableChildFrameNavigation = true; });
afterAll(() => { browserSettings.navigation.disableChildFrameNavigation = previousIframeLoading; });
const flush = async () => { for (let i = 0; i < 10; i++) { await Promise.resolve(); await nextTick(); } };
const file = (extra: Partial<CompanyFile> = {}): CompanyFile => ({ id: 42, fileName: 'intro.mp4', resourceType: 'VIDEO', playbackType: 'FILE', names: {}, homepage: false,
  mimeType: 'video/mp4', enabled: true, sortNo: 0, version: 1, size: 5, url: '/api/compute/admin/company-files/42/video', createdAt: '', updatedAt: '', ...extra });
async function mount(value: CompanyFile, localFile?: File) {
  const host = document.createElement('div'); document.body.append(host);
  const app = createApp({ render: () => h(CompanyVideoPreview, { file: value, localFile }) }); app.mount(host);
  const dispose = () => { app.unmount(); host.remove(); };
  cleanup.push(dispose); await flush(); return { host, dispose: () => { cleanup.splice(cleanup.indexOf(dispose), 1); dispose(); } };
}
beforeEach(() => {
  state.download.mockReset(); state.access = reactive({ accessToken: 'current-session' });
  state.download.mockResolvedValue(new Blob(['video'], { type: 'video/mp4' }));
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:preview'); vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => undefined); vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
});
afterEach(() => { cleanup.splice(0).forEach(dispose => dispose()); vi.restoreAllMocks(); });

describe('公司视频管理预览', () => {
  it('已存视频显式点击后通过鉴权下载，媒体元素不包含令牌或直接管理端 URL', async () => {
    const { host, dispose } = await mount(file());
    expect(state.download).not.toHaveBeenCalled(); expect(host.querySelector('video')).toBeNull();
    host.querySelector('button')!.click(); await flush();
    expect(state.download).toHaveBeenCalledExactlyOnceWith(42, expect.any(AbortSignal), true);
    expect(host.querySelector('video')?.getAttribute('src')).toBe('blob:preview'); expect(host.innerHTML).not.toContain('current-session');
    dispose(); expect(HTMLMediaElement.prototype.pause).toHaveBeenCalled(); expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:preview');
  });
  it.each(['closed', 'session'])('预览请求等待期间发生 %s 后取消请求并忽略迟到结果', async reason => {
    let resolve!: (blob: Blob) => void;
    state.download.mockImplementation(() => new Promise(result => { resolve = result; }));
    const { host, dispose } = await mount(file()); host.querySelector('button')!.click(); await flush();
    const signal = state.download.mock.calls[0]![1] as AbortSignal;
    if (reason === 'closed') dispose(); else state.access.accessToken = 'different-session';
    await flush(); expect(signal.aborted).toBe(true);
    resolve(new Blob(['video'], { type: 'video/mp4' })); await flush();
    expect(URL.createObjectURL).not.toHaveBeenCalled(); expect(host.querySelector('video')).toBeNull();
  });
  it('错误 MIME、失败响应都不创建视频 URL，并提供重试', async () => {
    state.download.mockResolvedValueOnce(new Blob(['html error'], { type: 'text/html' })).mockRejectedValueOnce(new Error('failed'));
    const { host } = await mount(file()); host.querySelector('button')!.click(); await flush();
    expect(host.textContent).toContain('admin.compute.companyVideo.previewFailed'); expect(URL.createObjectURL).not.toHaveBeenCalled();
    host.querySelector('button')!.click(); await flush(); expect(host.textContent).toContain('admin.compute.companyVideo.previewFailed');
    host.querySelector('button')!.click(); await flush(); expect(host.querySelector('video')).not.toBeNull();
    expect(state.download).toHaveBeenCalledTimes(3);
  });
  it.each([
    { playbackType: 'YOUTUBE', embedUrl: 'https://www.youtube-nocookie.com.evil.example/embed/abcdefghijk' },
    { playbackType: 'VIMEO', embedUrl: 'https://player.vimeo.com/video/123?redirect=bad' },
    { playbackType: 'DIRECT', url: 'http://example.com/intro.mp4' },
    { playbackType: 'DIRECT', url: 'https://127.0.0.1/intro.mp4' },
  ] as Partial<CompanyFile>[])('拒绝来源 $playbackType 的非法地址 $url $embedUrl', async extra => {
    const { host } = await mount(file({ resourceType: 'VIDEO_LINK', ...extra })); host.querySelector('button')!.click(); await flush();
    expect(host.querySelector('video,iframe')).toBeNull(); expect(host.textContent).toContain('admin.compute.companyVideo.previewFailed'); expect(state.download).not.toHaveBeenCalled();
  });
  it('只有点击后加载已验证的嵌入视频，卸载时移除播放器', async () => {
    const { host, dispose } = await mount(file({ resourceType: 'VIDEO_LINK', playbackType: 'YOUTUBE', embedUrl: 'https://www.youtube-nocookie.com/embed/abcdefghijk' }));
    expect(host.querySelector('iframe')).toBeNull(); host.querySelector('button')!.click(); await flush();
    expect(host.querySelector('iframe')?.getAttribute('src')).toBe('https://www.youtube-nocookie.com/embed/abcdefghijk');
    dispose(); expect(document.querySelector('iframe')).toBeNull();
  });
});
