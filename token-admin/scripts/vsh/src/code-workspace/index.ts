import type { CAC } from 'cac';

import { join, relative } from 'node:path';

import {
  colors,
  consola,
  findMonorepoRoot,
  formatFile,
  getPackages,
  gitAdd,
  outputJSON,
  toPosixPath,
} from '@vben/node-utils';

const CODE_WORKSPACE_FILE = join('vben-admin.code-workspace');

interface CodeWorkspaceCommandOptions {
  autoCommit?: boolean;
  spaces?: number;
}

async function createCodeWorkspace({
  autoCommit = false,
  spaces = 2,
}: CodeWorkspaceCommandOptions) {
  const { packages, rootDir } = await getPackages();

  let folders = packages.map((pkg) => {
    const { dir, packageJson } = pkg;
    return {
      name: packageJson.name,
      path: toPosixPath(relative(rootDir, dir)),
    };
  });

  folders = folders.filter(Boolean);

  const monorepoRoot = findMonorepoRoot();
  const outputPath = join(monorepoRoot, CODE_WORKSPACE_FILE);
  await outputJSON(outputPath, { folders }, spaces);

  await formatFile(outputPath);
  if (autoCommit) {
    await gitAdd(CODE_WORKSPACE_FILE, monorepoRoot);
  }
}

async function runCodeWorkspace({
  autoCommit,
  spaces,
}: CodeWorkspaceCommandOptions) {
  await createCodeWorkspace({
    autoCommit,
    spaces,
  });
  if (autoCommit) {
    return;
  }
  consola.log('');
  consola.success(colors.green(`${CODE_WORKSPACE_FILE} 已更新！`));
  consola.log('');
}

function defineCodeWorkspaceCommand(cac: CAC) {
  cac
    .command('code-workspace')
    .usage('更新 `.code-workspace` 文件。')
    .option('--spaces [number]', '设置 .code-workspace JSON 文件的缩进空格数。', {
      default: 2,
    })
    .option('--auto-commit', '自动提交 .code-workspace JSON 文件。', {
      default: false,
    })
    .action(runCodeWorkspace);
}

export { defineCodeWorkspaceCommand };
