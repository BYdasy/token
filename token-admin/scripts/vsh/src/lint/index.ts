import type { CAC } from 'cac';

import { execSync } from 'node:child_process';
import { availableParallelism, freemem } from 'node:os';

import { execaCommand } from '@vben/node-utils';

interface LintCommandOptions {
  /**
   * 自动修复格式与代码检查问题。
   */
  format?: boolean;
  /**
   * oxfmt 和 oxlint 使用的线程数（默认值：2）。
   */
  threads?: number;
}

/**
 * CPU 逻辑核心数阈值 4 核。
 * - 小于等于该值：视为低配机器，检查命令串行执行，避免瞬时占用飙升。
 */
const CPU_CORE_THRESHOLD = 4;

/**
 * 可用内存阈值 8 GB。
 * 仅当空余内存大于阈值且当前可用内存大于该值时，
 * oxfmt / oxlint 默认线程数才提升到 4，否则使用 2。
 */
const FREE_MEMORY_THRESHOLD = 8 * 1024 ** 3;

/**
 * 串行执行所有命令：一次只运行一个进程。
 * 保证一次能看到所有工具的报错（配置低的机器更友好）。
 */
async function runSerial(commands: string[]) {
  const failed: string[] = [];

  for (const command of commands) {
    try {
      await execaCommand(command, { stdio: 'inherit' });
    } catch {
      failed.push(command);
    }
  }

  if (failed.length > 0) {
    throw new Error(
      `代码检查失败：\n${failed.map((command) => `  - ${command}`).join('\n')}`,
    );
  }
}

/**
 * 并行执行所有命令：同时启动全部进程。
 * 任一进程失败时，强制结束其余仍在运行的进程，避免产生遗漏进程。
 */
async function runParallel(commands: string[]) {
  const subprocesses = commands.map((command) =>
    execaCommand(command, { stdio: 'inherit' }),
  );

  try {
    await Promise.all(subprocesses);
  } catch (error) {
    for (const subprocess of subprocesses) {
      try {
        if (process.platform === 'win32' && subprocess.pid) {
          execSync(`taskkill /F /T /PID ${subprocess.pid}`, {
            stdio: 'ignore',
          });
        } else {
          subprocess.kill('SIGKILL');
        }
      } catch {
        // 进程可能已经退出
      }
    }
    await Promise.allSettled(subprocesses);
    throw error;
  }
}

async function runLint({ format, threads }: LintCommandOptions) {
  // process.env.FORCE_COLOR = '3';
  const cpuCores = availableParallelism();

  // CPU 核心数充足且可用内存充足时，默认线程数提升到 4，否则维持 2；
  // 用户通过 --threads 显式指定时优先使用其值。
  const defaultThreads =
    cpuCores > CPU_CORE_THRESHOLD && freemem() > FREE_MEMORY_THRESHOLD ? 4 : 2;
  const threadsArg = ` --threads=${threads || defaultThreads}`;

  if (format) {
    await execaCommand(`stylelint "**/*.{vue,css,less,scss}" --cache --fix`, {
      stdio: 'inherit',
    });
    await execaCommand(`oxfmt${threadsArg}`, {
      stdio: 'inherit',
    });
    await execaCommand(`oxlint --fix --type-aware${threadsArg}`, {
      stdio: 'inherit',
    });
    await execaCommand(`eslint . --cache --fix`, {
      stdio: 'inherit',
    });
    return;
  }

  const commands = [
    `oxfmt --check${threadsArg}`,
    `oxlint --type-aware${threadsArg}`,
    `eslint . --cache`,
    `stylelint "**/*.{vue,css,less,scss}" --cache`,
  ];

  // 低配机器（CPU 核心数较少）串行执行，避免多进程并发导致瞬时占用飙升；
  // 高配机器并行执行以缩短整体耗时。
  await (cpuCores <= CPU_CORE_THRESHOLD
    ? runSerial(commands)
    : runParallel(commands));
}

function defineLintCommand(cac: CAC) {
  cac
    .command('lint')
    .usage('批量执行项目代码检查。')
    .option('--format', '自动修复格式与代码检查问题。')
    .option('--threads <count>', '指定 oxfmt 和 oxlint 使用的线程数。')
    .action(runLint);
}

export { defineLintCommand };
