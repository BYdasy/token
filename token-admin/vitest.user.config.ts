import { fileURLToPath, URL } from 'node:url';
import { defineConfig, mergeConfig } from 'vitest/config';
import base from './vitest.config';

// 用户端复用工作区已有 Vitest，无需安装新依赖；接口在每个用例中隔离模拟。
// 从管理端执行：node node_modules/vitest/vitest.mjs run --root ../token-user --config ../token-admin/vitest.user.config.ts tests/useTaskPrompt.test.ts
export default mergeConfig(base, defineConfig({
  root: fileURLToPath(new URL('../token-user', import.meta.url)).replaceAll('\\', '/'),
  resolve: { alias: { '@': fileURLToPath(new URL('../token-user/src', import.meta.url)).replaceAll('\\', '/') } },
  test: { include: ['tests/**/*.test.ts'] },
}));
