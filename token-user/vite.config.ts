import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";
import { fileURLToPath, URL } from "node:url";
import { pdfAssetsPlugin } from "./vite/pdf-assets";
import { sharePreviewPlugin } from "./vite/share-preview";
import { runtimePublicAssetsPlugin } from "../scripts/RuntimePublicAssets";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  return {
    base: env.VITE_BASE || "/",
    plugins: [vue(), runtimePublicAssetsPlugin(), pdfAssetsPlugin(fileURLToPath(new URL(".", import.meta.url))),
      sharePreviewPlugin({ root: fileURLToPath(new URL(".", import.meta.url)),
        settingsUrl: env.SHARE_PREVIEW_SETTINGS_URL, siteUrl: env.SHARE_PREVIEW_SITE_URL })],
    resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
    server: {
      port: Number(env.VITE_PORT || 5578),
      // 保留配置端口为首选；占用时自动顺延，也可显式要求固定端口。
      strictPort: env.VITE_STRICT_PORT === "true",
      // 同源开发代理保留浏览器 Host，避免改写 Host 后原 Origin 被服务端 CORS 拒绝。
      // 首选端口被占用顺延时仍按同源处理；跨域直连接口仍由服务端明确来源白名单控制。
      proxy: { "/api": { target: "http://localhost:9091", changeOrigin: false } },
    },
    build: { sourcemap: false, chunkSizeWarningLimit: 750 },
  };
});
