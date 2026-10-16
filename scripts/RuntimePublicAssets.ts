import { readFile, readdir } from 'node:fs/promises';
import { join } from 'node:path';

/** Only developer documentation is excluded; business documents and license notices remain deployable. */
export function isRuntimePublicAsset(name: string): boolean {
  const basename = name.replaceAll('\\', '/').split('/').at(-1) || '';
  if (/^(?:readme|changelog|changes|agents|contributing|development|todo)(?:[._-][a-z0-9-]+)?\.(?:md|markdown|rst|txt)$/i.test(basename)) return false;
  return !/(?:\.map|\.d\.(?:ts|mts|cts))$/i.test(basename)
    && !/^(?:\.DS_Store|Thumbs\.db)$/i.test(basename);
}

/** Read only regular files under public; never follow a link into an unrelated directory. */
export async function listPublicAssets(publicDir: string): Promise<string[]> {
  const files: string[] = [];
  async function visit(directory: string, prefix = '') {
    for (const entry of (await readdir(directory, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name))) {
      const name = prefix ? `${prefix}/${entry.name}` : entry.name;
      if (entry.isSymbolicLink()) throw new Error(`Public asset must not be a symbolic link: ${name}`);
      if (entry.isDirectory()) await visit(join(directory, entry.name), name);
      else if (entry.isFile()) files.push(name);
    }
  }
  if (publicDir) await visit(publicDir);
  return files;
}

/** Emit public assets before PWA manifests/ZIPs are generated, instead of pruning a finished build. */
export function runtimePublicAssetsPlugin() {
  let publicDir = '';
  return {
    name: 'token:runtime-public-assets',
    apply: 'build' as const,
    config() { return { build: { copyPublicDir: false } }; },
    configResolved(config: { publicDir: string }) { publicDir = config.publicDir; },
    async generateBundle(this: { emitFile(asset: { type: 'asset'; fileName: string; source: Uint8Array }): string }) {
      for (const name of await listPublicAssets(publicDir)) {
        if (!isRuntimePublicAsset(name)) continue;
        this.emitFile({ type: 'asset', fileName: name, source: await readFile(join(publicDir, name)) });
      }
    },
  };
}
