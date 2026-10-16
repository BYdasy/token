import { beforeAll, describe, expect, it, vi } from 'vitest';
import { basename, resolve } from 'node:path';
import { pdfAssetsPlugin } from '../vite/pdf-assets';
import { pdfAssetUrls, pdfWorkerUrl } from '../src/utils/pdf-assets';

let plugin: any;
let handler: any;
let define: Record<string, string>;
beforeAll(async () => {
  const root = basename(process.cwd()) === 'token-user' ? process.cwd() : resolve(process.cwd(), '../token-user');
  plugin = pdfAssetsPlugin(root);
  define = (await plugin.config()).define;
  plugin.configResolved({ base: '/preview/' });
  await plugin.configureServer({ middlewares: { use(value: any) { handler = value; } } });
});

function request(url: string, method = 'GET') {
  const headers: Record<string, string | number> = {};
  const response = { statusCode: 200, setHeader(name: string, value: string | number) { headers[name] = value; }, end: vi.fn() };
  const next = vi.fn();
  handler({ url, method }, response, next);
  return { response, headers, next };
}

describe('PDF.js 发行资源自托管', () => {
  it('在 BASE_URL 下只提供已扫描的官方资源和许可证，wasm类型与不可变缓存正确', () => {
    const version = JSON.parse(define['import.meta.env.VITE_PDFJS_ASSET_VERSION']!);
    expect(version).toBe('6.3.289');
    const worker = request(`/preview/pdfjs/${version}/pdf.worker.js`);
    expect(worker.response.statusCode).toBe(200);
    expect(worker.headers['Content-Type']).toBe('text/javascript; charset=utf-8');
    expect(worker.response.end.mock.calls[0]?.[0].byteLength).toBeGreaterThan(500_000);
    const wasm = request(`/preview/pdfjs/${version}/wasm/openjpeg.wasm?version=1`);
    expect(wasm.response.statusCode).toBe(200);
    expect(wasm.headers['Content-Type']).toBe('application/wasm');
    expect(wasm.headers['Cache-Control']).toContain('immutable');
    expect(wasm.response.end.mock.calls[0]?.[0].byteLength).toBeGreaterThan(0);
    for (const path of ['LICENSE', 'cmaps/Adobe-Japan1-UCS2.bcmap', 'standard_fonts/LiberationSans-Regular.ttf', 'wasm/LICENSE_OPENJPEG', 'wasm/LICENSE_PDFJS_OPENJPEG', 'wasm/LICENSE_PDFJS_JBIG2', 'wasm/LICENSE_PDFJS_QCMS', 'iccs/CGATS001Compat-v2-micro.icc']) {
      const result = request(`/preview/pdfjs/${version}/${path}`);
      expect(result.response.statusCode, path).toBe(200);
      expect(result.next).not.toHaveBeenCalled();
    }
  });

  it.each(['../package.json', 'cmaps/../../package.json', '%2e%2e/package.json', '%252e%252e%252fpackage.json', 'cmaps%2fAdobe-Japan1-UCS2.bcmap', 'wasm/..\\..\\package.json', 'package.json', 'build/pdf.mjs'])('不允许路径穿越和发行资源白名单之外的文件：%s', (path) => {
    const result = request(`/preview/pdfjs/6.3.289/${path}`);
    expect(result.response.statusCode).toBe(404);
    expect(result.next).not.toHaveBeenCalled();
    expect(result.response.end).toHaveBeenCalledWith();
  });

  it('未知版本资源404，HEAD不返回正文，禁止写入方法，非资源路径交给Vite', () => {
    expect(request('/preview/pdfjs/old/LICENSE').response.statusCode).toBe(404);
    const head = request('/preview/pdfjs/6.3.289/LICENSE', 'HEAD');
    expect(head.response.statusCode).toBe(200);expect(head.response.end).toHaveBeenCalledWith(undefined);
    expect(Number(head.headers['Content-Length'])).toBeGreaterThan(0);
    expect(request('/preview/pdfjs/6.3.289/LICENSE', 'POST').response.statusCode).toBe(405);
    expect(request('/preview/company-files').next).toHaveBeenCalledTimes(1);
  });

  it('构建钩子仅向emitFile提供同一资源清单，不写src或产生真实构建', async () => {
    const emitFile = vi.fn();
    await plugin.generateBundle.call({ emitFile });
    const assets = emitFile.mock.calls.map(([asset]) => asset);
    expect(assets.length).toBeGreaterThan(180);
    expect(assets.every(asset => asset.type === 'asset' && /^pdfjs\/6\.3\.289\/(LICENSE|pdf\.worker\.js|(?:cmaps|standard_fonts|wasm|iccs)\/[A-Za-z0-9_.-]+)$/.test(asset.fileName))).toBe(true);
    for (const asset of assets) {
      const served = request(`/preview/${asset.fileName}`);
      expect(served.response.end.mock.calls[0]?.[0]).toBe(asset.source);
    }
  });

  it('前端资源地址使用插件同一版本并保留部署前缀', () => {
    vi.stubEnv('BASE_URL', '/preview/');
    vi.stubEnv('VITE_PDFJS_ASSET_VERSION', JSON.parse(define['import.meta.env.VITE_PDFJS_ASSET_VERSION']!));
    expect(pdfAssetUrls()).toEqual({
      cMapUrl: '/preview/pdfjs/6.3.289/cmaps/',
      standardFontDataUrl: '/preview/pdfjs/6.3.289/standard_fonts/',
      wasmUrl: '/preview/pdfjs/6.3.289/wasm/',
      iccUrl: '/preview/pdfjs/6.3.289/iccs/',
    });
    expect(pdfWorkerUrl()).toBe('/preview/pdfjs/6.3.289/pdf.worker.js');
    vi.unstubAllEnvs();
  });
});
