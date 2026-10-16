import type { CAC } from 'cac';
import type { Result } from 'publint';

import { basename, dirname, join } from 'node:path';

import {
  colors,
  consola,
  ensureFile,
  findMonorepoRoot,
  generatorContentHash,
  getPackages,
  outputJSON,
  readJSON,
  UNICODE,
} from '@vben/node-utils';

import { publint } from 'publint';
import { formatMessage } from 'publint/utils';

const CACHE_FILE = join(
  'node_modules',
  '.cache',
  'publint',
  '.pkglintcache.json',
);

interface PubLintCommandOptions {
  /**
   * 仅检查错误，不主动退出进程
   */
  check?: boolean;
}

/**
 * 获取需要检查的文件
 * @param files 待检查的文件列表
 */
async function getLintFiles(files: string[] = []) {
  const lintFiles: string[] = [];

  if (files?.length > 0) {
    return files.filter((file) => basename(file) === 'package.json');
  }

  const { packages } = await getPackages();

  for (const { dir } of packages) {
    lintFiles.push(join(dir, 'package.json'));
  }
  return lintFiles;
}

function getCacheFile() {
  const root = findMonorepoRoot();
  return join(root, CACHE_FILE);
}

async function readCache(cacheFile: string) {
  try {
    await ensureFile(cacheFile);
    return await readJSON(cacheFile);
  } catch {
    return {};
  }
}

async function runPublint(files: string[], { check }: PubLintCommandOptions) {
  const lintFiles = await getLintFiles(files);
  const cacheFile = getCacheFile();

  const cacheData = await readCache(cacheFile);
  const cache: Record<string, { hash: string; result: Result }> = cacheData;

  const results = await Promise.all(
    lintFiles.map(async (file) => {
      try {
        const pkgJson = await readJSON(file);

        if (pkgJson.private) {
          return null;
        }

        Reflect.deleteProperty(pkgJson, 'dependencies');
        Reflect.deleteProperty(pkgJson, 'devDependencies');
        Reflect.deleteProperty(pkgJson, 'peerDependencies');
        const content = JSON.stringify(pkgJson);
        const hash = generatorContentHash(content);

        const publintResult: Result =
          cache?.[file]?.hash === hash
            ? (cache?.[file]?.result ?? [])
            : await publint({
                level: 'suggestion',
                pkgDir: dirname(file),
                strict: true,
              });

        cache[file] = {
          hash,
          result: publintResult,
        };

        return { pkgJson, pkgPath: file, publintResult };
      } catch {
        return null;
      }
    }),
  );

  await outputJSON(cacheFile, cache);
  printResult(results, check);
}

function printResult(
  results: Array<null | {
    pkgJson: Record<string, number | string>;
    pkgPath: string;
    publintResult: Result;
  }>,
  check?: boolean,
) {
  let errorCount = 0;
  let warningCount = 0;
  let suggestionsCount = 0;

  for (const result of results) {
    if (!result) {
      continue;
    }
    const { pkgJson, pkgPath, publintResult } = result;
    const messages = publintResult?.messages ?? [];
    if (messages?.length < 1) {
      continue;
    }

    consola.log('');
    consola.log(pkgPath);
    for (const message of messages) {
      switch (message.type) {
        case 'error': {
          errorCount++;

          break;
        }
        case 'suggestion': {
          suggestionsCount++;
          break;
        }
        case 'warning': {
          warningCount++;

          break;
        }
        // 无默认分支
      }
      const ruleUrl = `https://publint.dev/rules#${message.code.toLocaleLowerCase()}`;
      consola.log(
        `  ${formatMessage(message, pkgJson)}${colors.dim(` ${ruleUrl}`)}`,
      );
    }
  }

  const totalCount = warningCount + errorCount + suggestionsCount;
  if (totalCount > 0) {
    consola.error(
      colors.red(
        `${UNICODE.FAILURE} 共 ${totalCount} 个问题（${errorCount} 个错误、${warningCount} 个警告、${suggestionsCount} 条建议）`,
      ),
    );
    !check && process.exit(1);
  } else {
    consola.log(colors.green(`${UNICODE.SUCCESS} 未发现问题`));
  }
}

function definePubLintCommand(cac: CAC) {
  cac
    .command('publint [...files]')
    .usage('检查 monorepo 中的包是否符合 publint 标准。')
    .option('--check', '仅检查错误，不主动退出进程。')
    .action(runPublint);
}

export { definePubLintCommand };
