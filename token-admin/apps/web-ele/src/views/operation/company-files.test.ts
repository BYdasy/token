import type { CompanyFile } from '#/api/company-files';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';

const state = vi.hoisted(() => ({
  options: vi.fn(), page: vi.fn(), upload: vi.fn(), update: vi.fn(), link: vi.fn(), videoLink: vi.fn(), download: vi.fn(),
  access: null as any, locale: null as any, permissions: null as any,
}));
vi.mock('#/api/company-files', () => ({ fetchCompanyFileOptions: state.options, fetchCompanyFiles: state.page, uploadCompanyFile: state.upload,
  updateCompanyFile: state.update, createCompanyLink: state.link, createCompanyVideoLink: state.videoLink, downloadCompanyFile: state.download }));
vi.mock('@vben/locales', () => ({ useI18n: () => ({ locale: state.locale, t: (key: string) => key }) }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => state.access }));
vi.mock('@vben/constants', () => ({ SUPPORT_LANGUAGES: [] }));
vi.mock('#/components/business/shared', () => ({ P: { companyFileRead: 'read', companyFileWrite: 'write' }, useCompute: () => ({
  c: (key: string) => key, date: (value: string) => value, can: (...permissions: string[]) => permissions.some(key => state.permissions.has(key)),
}) }));
vi.mock('#/components/business/useFilterRecovery', () => ({ useFilterRecovery: () => undefined }));
vi.mock('#/components/business/useBusinessFormTranslation', () => ({ useBusinessFormTranslation: () => ({ task: ref({}), loading: ref(false), running: ref(false), start: vi.fn(), refresh: vi.fn(), stop: vi.fn() }) }));
vi.mock('#/components/business/businessLanguages', () => ({ orderedBusinessLanguages: () => [] }));
vi.mock('#/adapter/table', async () => ({ ElTable: (await import('element-plus')).ElTable }));
import CompanyFiles from './company-files.vue';

const cleanup: (() => void)[] = [];
const flush = async () => { for (let i = 0; i < 18; i++) { await Promise.resolve(); await nextTick(); } };
const row = (type: 'PDF' | 'VIDEO' = 'VIDEO', extra: Partial<CompanyFile> = {}): CompanyFile => ({ id: 42, fileName: type === 'PDF' ? 'company.pdf' : 'intro.mp4', resourceType: type, playbackType: type === 'VIDEO' ? 'FILE' : 'NONE',
  names: {}, homepage: false, mimeType: type === 'VIDEO' ? 'video/mp4' : 'application/pdf', enabled: true, sortNo: 0, version: 1, size: 7, url: `/api/compute/admin/company-files/42/${type.toLowerCase()}`,
  createdAt: '2026-10-18T00:00:00Z', updatedAt: '2026-10-18T00:00:00Z', ...extra });
function button(label: string, scope: ParentNode = document) { const button = [...scope.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent?.trim() === label); if (!button) throw new Error(`Missing button ${label}`); return button; }
async function mount() {
  const host = document.createElement('div'); document.body.append(host);
  const app = createApp({ render: () => h(CompanyFiles) }); app.directive('loading', {}); app.mount(host);
  cleanup.push(() => { app.unmount(); host.remove(); }); await flush(); return host;
}
async function upload(host: HTMLElement, type: 'PDF' | 'VIDEO', file = new File(['content'], type === 'PDF' ? 'company.pdf' : 'intro.mp4', { type: type === 'PDF' ? 'application/pdf' : 'video/mp4' })) {
  const input = host.querySelector<HTMLInputElement>(`input[type="file"][accept^="${type === 'PDF' ? 'application/pdf' : 'video/mp4'}"]`)!;
  Object.defineProperty(input, 'files', { configurable: true, value: [file] });
  input.dispatchEvent(new Event('change', { bubbles: true })); await flush(); return file;
}
beforeEach(() => {
  Object.values(state).forEach(value => { if (vi.isMockFunction(value)) value.mockReset(); });
  state.access = reactive({ accessToken: 'session-1' }); state.locale = ref('zh'); state.permissions = reactive(new Set(['read', 'write']));
  state.options.mockResolvedValue({ globalScope: true, maxFileBytes: 20 * 1024 * 1024, maxVideoBytes: 100 * 1024 * 1024 });
  state.page.mockResolvedValue({ records: [], total: 0 }); state.upload.mockResolvedValue(row()); state.update.mockResolvedValue(row());
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:company-video'); vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => undefined);
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
  vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({ close: vi.fn() } as any));
  vi.spyOn(ElMessage, 'success').mockImplementation(() => ({ close: vi.fn() } as any));
  vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: vi.fn() } as any));
});
afterEach(() => { cleanup.splice(0).forEach(dispose => dispose()); vi.restoreAllMocks(); });

describe('公司文件首次上传及分类型操作', () => {
  it.each(['PDF', 'VIDEO'] as const)('上传 %s 后首次打开真实 Element Plus 表单即可保存，无需取消再编辑', async type => {
    state.upload.mockResolvedValueOnce(row(type));
    const host = await mount(); await upload(host, type);
    const dialog = document.querySelector('.el-dialog')!;
    expect(dialog).not.toBeNull();
    expect(dialog.querySelector<HTMLInputElement>('.el-input-number input')?.value).toBe('0');
    button('common.save', dialog).click(); await flush();
    expect(ElMessage.warning).not.toHaveBeenCalled();
    expect(state.update).toHaveBeenCalledExactlyOnceWith(42, expect.objectContaining({ sortNo: 0, version: 1, fileName: type === 'PDF' ? 'company.pdf' : 'intro.mp4', externalUrl: undefined }), expect.any(AbortSignal));
  });
  it('上传响应中的有效整数文本在首次表单中规范化，分页中没有新文件也能编辑', async () => {
    state.upload.mockResolvedValueOnce(row('VIDEO', { sortNo: '0' as unknown as number, version: '1' as unknown as number }));
    const host = await mount(); await upload(host, 'VIDEO');
    button('common.save', document.querySelector('.el-dialog')!).click(); await flush();
    expect(state.update).toHaveBeenCalledWith(42, expect.objectContaining({ sortNo: 0, version: 1 }), expect.any(AbortSignal));
    expect(ElMessage.warning).not.toHaveBeenCalled();
  });
  it('PDF 与视频输入入口互相拒绝不匹配的文件，不触发上传请求', async () => {
    const host = await mount();
    expect(button('companyFile.upload', host)).toBeDefined(); expect(button('companyVideo.uploadVideo', host)).toBeDefined();
    await upload(host, 'PDF', new File(['video'], 'intro.mp4', { type: 'video/mp4' }));
    await upload(host, 'VIDEO', new File(['pdf'], 'company.pdf', { type: 'application/pdf' }));
    expect(state.upload).not.toHaveBeenCalled(); expect(ElMessage.warning).toHaveBeenCalledTimes(2);
  });
  it('上传视频后使用本次文件预览，关闭表单销毁播放器并释放 URL', async () => {
    const host = await mount(); const file = await upload(host, 'VIDEO');
    const dialog = document.querySelector('.el-dialog')!;
    expect(dialog.querySelector('video')?.getAttribute('src')).toBe('blob:company-video');
    expect(URL.createObjectURL).toHaveBeenCalledWith(file); expect(state.download).not.toHaveBeenCalled();
    button('common.cancel', dialog).click(); await flush();
    expect(document.querySelector('video')).toBeNull(); expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:company-video');
    expect(state.update).not.toHaveBeenCalled();
  });
  it('错误的视频扩展名显示文件名字段错误，不混入 PDF、链接或排序提示', async () => {
    const host = await mount(); await upload(host, 'VIDEO');
    const dialog = document.querySelector('.el-dialog')!;
    const input = dialog.querySelector<HTMLInputElement>('.el-form-item input')!;
    input.value = 'renamed.webm'; input.dispatchEvent(new Event('input', { bubbles: true })); await flush();
    button('common.save', dialog).click(); await flush();
    expect(state.update).not.toHaveBeenCalled(); await vi.waitFor(() => expect(dialog.textContent).toContain('companyVideo.invalidVideoName'));
    expect(dialog.textContent).not.toContain('companyV24.invalidDetails'); expect(dialog.textContent).not.toContain('companyVideo.invalidSort');
    input.value = 'renamed.mp4'; input.dispatchEvent(new Event('input', { bubbles: true })); await flush();
    button('common.save', dialog).click(); await flush();
    expect(state.update).toHaveBeenCalledWith(42, expect.objectContaining({ fileName: 'renamed.mp4' }), expect.any(AbortSignal));
  });
  it('视频行展示播放预览，PDF 行保留下载 PDF，外链不被当作文件下载', async () => {
    state.page.mockResolvedValue({ records: [row(), row('PDF', { id: 43 }), row('PDF', { id: 44, resourceType: 'LINK', externalUrl: 'https://example.com/company' })], total: 3 });
    const host = await mount();
    const tableRows = host.querySelectorAll('.el-table__body tr');
    expect(tableRows[0]?.textContent).toContain('companyVideo.preview'); expect(tableRows[0]?.textContent).not.toContain('companyFile.download');
    expect(tableRows[1]?.textContent).toContain('companyFile.download');
    expect(tableRows[2]?.querySelector('a')?.getAttribute('href')).toBe('https://example.com/company');
  });
  it('首次上传失败保留页面并可重试，过期会话的上传结果不打开编辑或创建预览', async () => {
    let resolve!: (value: CompanyFile) => void;
    state.upload.mockRejectedValueOnce(new Error('upload unavailable'));
    const host = await mount(); await upload(host, 'VIDEO');
    expect(document.querySelector('.el-dialog')).toBeNull();
    state.upload.mockImplementationOnce(() => new Promise(result => { resolve = result; }));
    await upload(host, 'VIDEO');
    const signal = state.upload.mock.calls[1]![1] as AbortSignal;
    state.access.accessToken = 'session-2'; await flush(); expect(signal.aborted).toBe(true);
    resolve(row()); await flush();
    expect(document.querySelector('.el-dialog')).toBeNull(); expect(URL.createObjectURL).not.toHaveBeenCalled();
  });
  it('移除编辑权限立即关闭上传编辑及本地预览，并保留只读权限', async () => {
    const host = await mount(); await upload(host, 'VIDEO');
    state.permissions.delete('write'); await flush();
    expect(document.querySelector('video')).toBeNull(); expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:company-video');
    expect(host.textContent).not.toContain('companyVideo.uploadVideo'); expect(state.update).not.toHaveBeenCalled();
  });
});
