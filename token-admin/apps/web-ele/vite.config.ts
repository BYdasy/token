import { defineConfig } from '@vben/vite-config';

import ElementPlus from 'unplugin-element-plus/vite';
import { loadEnv } from 'vite';
import { fileURLToPath } from 'node:url';
import { viteMetadataPlugin } from '../../internal/vite-config/src/plugins/inject-metadata';
import { runtimePublicAssetsPlugin } from '../../../scripts/RuntimePublicAssets';

export default defineConfig(async ({ mode }) => {
  const userEnv = loadEnv(mode, fileURLToPath(new URL('../../../token-user', import.meta.url)), 'VITE_');
  return {
    // 直接使用工作区元数据源码，避免已安装的配置包产物仍注入旧版无清单逻辑。
    application: { injectMetadata: false },
    vite: {
      define: {
        'import.meta.env.VITE_USER_APP_BASE': JSON.stringify(userEnv.VITE_BASE || '/'),
        'import.meta.env.VITE_USER_APP_PORT': JSON.stringify(userEnv.VITE_PORT || ''),
      },
      plugins: [
        runtimePublicAssetsPlugin(),
        await viteMetadataPlugin(),
        ElementPlus({
          format: 'esm',
        }),
      ],
      server: {
        proxy: {
          '/api': {
            // 保留浏览器访问的 Host，使本地代理请求仍可按同源判断。
            changeOrigin: false,
            rewrite: (path) => path.replace(/^\/api/, ''),
            // 本地 EXP 服务端地址。
            target: 'http://localhost:9091/api',
            ws: true,
          },
        },
      },
    },
  };
});
