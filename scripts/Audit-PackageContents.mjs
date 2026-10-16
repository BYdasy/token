import { access } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { isRuntimePublicAsset, listPublicAssets } from './RuntimePublicAssets.ts';

const mode = process.argv[2] || '--sources';
if (!['--sources', '--dist'].includes(mode)) throw new Error('Usage: node scripts/Audit-PackageContents.mjs [--sources|--dist]');
const root = new URL('../', import.meta.url);
const applications = ['token-user', 'token-admin/apps/web-ele'];
let invalid = false;
for (const application of applications) {
  const directory = fileURLToPath(new URL(`${application}/${mode === '--dist' ? 'dist' : 'public'}/`, root));
  await access(directory);
  const files = await listPublicAssets(directory);
  const excluded = files.filter((name) => !isRuntimePublicAsset(name));
  console.log(JSON.stringify({ application, mode, files: files.length, [mode === '--dist' ? 'unexpectedFiles' : 'excludedFromBuild']: excluded }));
  if (mode === '--dist' && excluded.length) invalid = true;
}
if (invalid) process.exitCode = 1;
