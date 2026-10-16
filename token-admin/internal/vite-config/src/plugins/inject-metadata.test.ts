import { describe, expect, it, vi } from 'vitest';
vi.mock('@vben/node-utils', () => ({
  dateUtil: () => ({ format: () => '2026-09-22 00:00:00' }),
  findMonorepoRoot: () => '/isolated',
  getPackages: async () => ({ packages: [] }),
  readPackageJSON: async () => ({ version: '1.0.0', author: 'Token' }),
}));
vi.mock('@pnpm/workspace.read-manifest', () => ({ readWorkspaceManifest: async () => ({}) }));
import { viteMetadataPlugin } from './inject-metadata';

describe('构建版本清单', () => {
  it('注入代码与输出清单共享buildId，每次新构建生成独立标识，无需执行实际构建', async () => {
    const plugin = await viteMetadataPlugin('/isolated') as any;
    const config = await plugin.config();
    const emitFile = vi.fn();plugin.generateBundle.call({ emitFile });
    const artifact = emitFile.mock.calls[0]![0];
    const manifest = JSON.parse(artifact.source);
    expect(artifact).toMatchObject({ type: 'asset', fileName: 'version.json' });
    expect(manifest.buildId).toBe(JSON.parse(config.define['import.meta.env.VITE_APP_BUILD_ID']));
    expect(manifest.buildId).toBe(JSON.parse(config.define.__VBEN_ADMIN_METADATA__).buildId);
    expect(manifest.version).toBe('1.0.0');
    const next = await viteMetadataPlugin('/isolated') as any;
    expect((await next.config()).define['import.meta.env.VITE_APP_BUILD_ID']).not.toBe(config.define['import.meta.env.VITE_APP_BUILD_ID']);
  });
});
