import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/locales', () => ({ i18n: { global: { t: (key: string) => key } } }));
import { fetchCompanyFiles, fetchCompanyPdf } from '../src/api/company-files';
import { TOKEN_KEY } from '../src/api';

const send = vi.fn();
const body = (value: string, contentType = 'application/pdf', status = 200) => new Response(value, { status, headers: { 'Content-Type': contentType } });
beforeEach(() => { send.mockReset(); vi.stubGlobal('fetch', send); sessionStorage.setItem(TOKEN_KEY, 'session-a'); localStorage.clear(); });
afterEach(() => { vi.unstubAllGlobals(); sessionStorage.clear(); localStorage.clear(); });

describe('公司文件受保护读取', () => {
  it('文件列表和PDF均使用当前凭据，PDF采用固定编号路径且禁止重定向', async () => {
    send.mockResolvedValueOnce(body(JSON.stringify({ code: 0, data: [{ id: 1, fileName: '公司介绍.pdf', size: 16, url: '/api/compute/user/company-files/1/pdf' }] }), 'application/json'));
    expect(await fetchCompanyFiles()).toHaveLength(1);
    send.mockResolvedValueOnce(body('%PDF-1.7\ncontent'));
    const data = await fetchCompanyPdf(1);
    expect(new TextDecoder().decode(data)).toBe('%PDF-1.7\ncontent');
    expect(send.mock.calls[1]![0]).toBe('/api/compute/user/company-files/1/pdf');
    expect(send.mock.calls.every(([, options]) => options.headers.Authorization === 'Bearer session-a' && options.redirect === 'error')).toBe(true);
  });
  it('错误编号不发请求；HTML响应和伪PDF不能进入渲染器', async () => {
    for (const id of [0, -1, Number.NaN, 1.5]) await expect(fetchCompanyPdf(id)).rejects.toThrow('user.v20Company.notFound');
    expect(send).not.toHaveBeenCalled();
    send.mockResolvedValueOnce(body('<html>expired</html>', 'text/html'));
    await expect(fetchCompanyPdf(1)).rejects.toThrow('user.error.request');
    send.mockResolvedValueOnce(body('not-a-pdf', 'application/pdf'));
    await expect(fetchCompanyPdf(1)).rejects.toThrow('user.v20Company.previewFailed');
  });
  it('PDF接口返回业务错误时保留后台错误文案', async () => {
    send.mockResolvedValueOnce(body(JSON.stringify({ code: 404, message: '文件已停用' }), 'application/json', 404));
    await expect(fetchCompanyPdf(1)).rejects.toThrow('文件已停用');
    expect(sessionStorage.getItem(TOKEN_KEY)).toBe('session-a');
  });
  it('旧会话迟到401不能清除新账号，当前会话401仍及时撤销', async () => {
    let finish!: (value: Response) => void;
    send.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const pending = fetchCompanyPdf(1);
    sessionStorage.setItem(TOKEN_KEY, 'session-b');
    finish(body(JSON.stringify({ code: 401, message: '已过期' }), 'application/json', 401));
    await expect(pending).rejects.toThrow('已过期');
    expect(sessionStorage.getItem(TOKEN_KEY)).toBe('session-b');
    send.mockResolvedValueOnce(body(JSON.stringify({ code: 401, message: '已过期' }), 'application/json', 401));
    await expect(fetchCompanyPdf(1)).rejects.toThrow('已过期');
    expect(sessionStorage.getItem(TOKEN_KEY)).toBeNull();
  });
  it('切换文件主动取消保留AbortError，不伪装超时', async () => {
    send.mockImplementationOnce((_url, options) => new Promise((_resolve, reject) => {
      options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
    }));
    const controller = new AbortController(), pending = fetchCompanyPdf(1, controller.signal);
    controller.abort();
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
  });
});
