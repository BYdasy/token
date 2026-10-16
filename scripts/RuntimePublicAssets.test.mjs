import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import { basename, dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { test } from 'node:test';
import { isRuntimePublicAsset, listPublicAssets, runtimePublicAssetsPlugin } from './RuntimePublicAssets.ts';

test('excludes developer artifacts without dropping legal or business documents', () => {
  for (const name of ['images/vora/README.md', 'tinymce/CHANGELOG.md', 'AGENTS.md', 'README.zh-CN.md', 'index.js.map', 'tinymce.d.ts', '.DS_Store']) {
    assert.equal(isRuntimePublicAsset(name), false, name);
  }
  for (const name of ['tinymce/license.md', 'fonts/CormorantGaramond-OFL.txt', 'pdfjs/LICENSE', '公司文件.pdf', 'terms.md', 'offline.html', 'manifest.webmanifest', 'version.json', 'sw.js', 'images/vora/home.png']) {
    assert.equal(isRuntimePublicAsset(name), true, name);
  }
});

test('emits filtered assets unchanged and leaves the source documentation in place', async () => {
  const publicDir = await mkdtemp(join(tmpdir(), 'token-public-assets-'));
  try {
    await mkdir(join(publicDir, 'nested'));
    await writeFile(join(publicDir, 'nested', 'README.md'), '# Development note');
    await writeFile(join(publicDir, 'nested', 'license.md'), 'License notice');
    await writeFile(join(publicDir, 'asset.png'), Buffer.from([0, 255, 19]));
    const plugin = runtimePublicAssetsPlugin();
    assert.equal(plugin.apply, 'build');
    assert.deepEqual(plugin.config(), { build: { copyPublicDir: false } });
    plugin.configResolved({ publicDir });
    const assets = new Map();
    await plugin.generateBundle.call({ emitFile(asset) { assets.set(asset.fileName, asset); return asset.fileName; } });
    assert.deepEqual([...assets.keys()].sort(), ['asset.png', 'nested/license.md']);
    assert.deepEqual(assets.get('asset.png').source, Buffer.from([0, 255, 19]));
    assert.equal((await readFile(join(publicDir, 'nested', 'README.md'), 'utf8')), '# Development note');
    assert.equal((await listPublicAssets(publicDir)).length, 3);
  } finally {
    const resolvedPublicDir = await realpath(publicDir);
    const resolvedTempDir = await realpath(tmpdir());
    assert.equal(dirname(resolvedPublicDir), resolvedTempDir, 'Cleanup target must be a direct child of the temporary directory');
    assert.ok(basename(resolvedPublicDir).startsWith('token-public-assets-'), 'Cleanup target must belong to this test');
    await rm(resolvedPublicDir, { recursive: true, force: true });
  }
});

test('supports projects that disable the public directory', async () => {
  assert.deepEqual(await listPublicAssets(''), []);
});
