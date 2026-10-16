import { colors, consola } from '@vben/node-utils';

import { cac } from 'cac';

import { version } from '../package.json';
import { defineCheckCircularCommand } from './check-circular';
import { defineCheckDepCommand } from './check-dep';
import { defineCodeWorkspaceCommand } from './code-workspace';
import { defineLintCommand } from './lint';
import { definePubLintCommand } from './publint';

// 命令描述
const COMMAND_DESCRIPTIONS = {
  'check-circular': '检查循环依赖',
  'check-dep': '检查未使用的依赖',
  'code-workspace': '管理 VS Code 工作区设置',
  lint: '执行项目代码检查',
  publint: '检查 package.json 是否符合发布规范',
} as const;

/**
 * 初始化并运行命令行工具
 */
async function main(): Promise<void> {
  try {
    const vsh = cac('vsh');

    // 注册命令
    defineLintCommand(vsh);
    definePubLintCommand(vsh);
    defineCodeWorkspaceCommand(vsh);
    defineCheckCircularCommand(vsh);
    defineCheckDepCommand(vsh);

    // 配置命令行工具
    vsh.usage('vsh <command> [options]');
    vsh.help();
    vsh.version(version);

    // 解析参数但暂不自动执行，以便识别未知命令
    // 注意：cac v7 已移除 EventEmitter，解析后改用 matchedCommand 判断
    vsh.parse(undefined, { run: false });

    if (!vsh.matchedCommand && vsh.args.length > 0) {
      const unknownCmd = String(vsh.args[0]);
      consola.error(
        colors.red(`无效命令：${unknownCmd}`),
        '\n',
        colors.yellow('可用命令：'),
        '\n',
        Object.entries(COMMAND_DESCRIPTIONS)
          .map(([name, desc]) => `  ${colors.cyan(name)} - ${desc}`)
          .join('\n'),
      );
      process.exit(1);
    }

    await vsh.runMatchedCommand();
  } catch (error) {
    consola.error(
      colors.red('发生意外错误：'),
      '\n',
      error instanceof Error ? error.message : error,
    );
    process.exit(1);
  }
}

// 运行命令行工具
main().catch((error) => {
  consola.error(
    colors.red('命令行工具启动失败：'),
    '\n',
    error instanceof Error ? error.message : error,
  );
  process.exit(1);
});
