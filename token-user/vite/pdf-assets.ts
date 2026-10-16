import type { Plugin, ResolvedConfig } from 'vite';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { createRequire } from 'node:module';
import { readFile, readdir, realpath } from 'node:fs/promises';
import { dirname, isAbsolute, join, relative } from 'node:path';

const PDFJS_VERSION = '6.3.289';
const DISTRIBUTION_FILES = {
  cmaps: /(?:\.bcmap|^LICENSE(?:_[A-Z0-9_]+)?)$/,
  standard_fonts: /(?:\.(?:pfb|ttf)|^LICENSE(?:_[A-Z0-9_]+)?)$/,
  wasm: /(?:\.(?:wasm|js)|^LICENSE(?:_[A-Z0-9_]+)?)$/,
  iccs: /(?:\.icc|^LICENSE(?:_[A-Z0-9_]+)?)$/,
};
interface PdfAsset { data: Buffer; contentType: string }

function mime(name: string) {
  if (name.endsWith('.wasm')) return 'application/wasm';
  if (name.endsWith('.js')) return 'text/javascript; charset=utf-8';
  if (name.endsWith('.ttf')) return 'font/ttf';
  if (name.split('/').at(-1)?.startsWith('LICENSE')) return 'text/plain; charset=utf-8';
  return 'application/octet-stream';
}

/** 只读取已安装官方发行包中固定目录的平面资源，不接受请求路径参与文件系统解析。 */
async function distributionAssets(root: string) {
  const require = createRequire(join(root, 'package.json'));
  const packagePath = await realpath(require.resolve('pdfjs-dist/package.json'));
  const packageRoot = dirname(packagePath);
  const metadata = JSON.parse(await readFile(packagePath, 'utf8'));
  if (metadata.name !== 'pdfjs-dist' || metadata.version !== PDFJS_VERSION) {
    throw new Error(`PDF.js 资源插件要求 pdfjs-dist ${PDFJS_VERSION}，请同步依赖版本后再升级资源清单。`);
  }
  const assets = new Map<string, PdfAsset>();
  async function add(name: string) {
    const path = await realpath(join(packageRoot, name));
    const within = relative(packageRoot, path);
    if (isAbsolute(within) || within === '..' || within.startsWith(`..${process.platform === 'win32' ? '\\' : '/'}`)) {
      throw new Error('PDF.js 发行资源超出依赖包目录。');
    }
    assets.set(name, { data: await readFile(path), contentType: mime(name) });
  }
  await add('LICENSE');
  // .js 在现有 Nginx mime.types 中可直接识别，避免 .mjs 被当作二进制而拒绝模块 Worker。
  const worker = await readFile(join(packageRoot, 'legacy/build/pdf.worker.mjs'));
  assets.set('pdf.worker.js', { data: worker, contentType: 'text/javascript; charset=utf-8' });
  for (const [directory, allowed] of Object.entries(DISTRIBUTION_FILES)) {
    const entries = await readdir(join(packageRoot, directory), { withFileTypes: true });
    let resources = 0;
    for (const entry of entries.sort((a, b) => a.name.localeCompare(b.name))) {
      if (!entry.isFile() || !/^[A-Za-z0-9_.-]+$/.test(entry.name) || !allowed.test(entry.name)) continue;
      await add(`${directory}/${entry.name}`);
      if (!entry.name.startsWith('LICENSE')) resources++;
    }
    if (!resources) throw new Error(`PDF.js 发行资源目录为空：${directory}`);
  }
  return assets;
}

function basePath(base: string) {
  const pathname = new URL(base || '/', 'http://pdf-assets.local/').pathname;
  return pathname.endsWith('/') ? pathname : `${pathname}/`;
}

/** Vite 开发和生产构建共用一份精确清单，版本目录可安全使用不可变缓存。 */
export function pdfAssetsPlugin(root: string): Plugin {
  let config: ResolvedConfig;
  let assets: Promise<Map<string, PdfAsset>> | undefined;
  const inventory = () => assets ??= distributionAssets(root);
  return {
    name: 'token:pdfjs-assets',
    async config() {
      await inventory();
      return { define: { 'import.meta.env.VITE_PDFJS_ASSET_VERSION': JSON.stringify(PDFJS_VERSION) } };
    },
    configResolved(value) { config = value; },
    async configureServer(server) {
      const files = await inventory();
      const namespace = `${basePath(config.base)}pdfjs/`;
      const prefix = `${namespace}${PDFJS_VERSION}/`;
      server.middlewares.use((request: IncomingMessage, response: ServerResponse, next: () => void) => {
        // 不做 URL 规范化或百分号解码；任何编码绕过、子目录、其他包文件都无法命中白名单。
        const path = (request.url || '').split('?')[0] || '';
        if (!path.startsWith(namespace)) return next();
        const asset = path.startsWith(prefix) ? files.get(path.slice(prefix.length)) : undefined;
        if (!asset) { response.statusCode = 404; response.end(); return; }
        if (request.method !== 'GET' && request.method !== 'HEAD') {
          response.statusCode = 405;response.setHeader('Allow', 'GET, HEAD');response.end();return;
        }
        response.setHeader('Content-Type', asset.contentType);
        response.setHeader('Content-Length', asset.data.byteLength);
        response.setHeader('Cache-Control', 'public, max-age=31536000, immutable');
        response.setHeader('X-Content-Type-Options', 'nosniff');
        response.end(request.method === 'HEAD' ? undefined : asset.data);
      });
    },
    async generateBundle() {
      for (const [name, asset] of await inventory()) {
        this.emitFile({ type: 'asset', fileName: `pdfjs/${PDFJS_VERSION}/${name}`, source: asset.data });
      }
    },
  };
}
