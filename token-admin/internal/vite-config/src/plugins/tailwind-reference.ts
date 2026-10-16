import type { Plugin } from 'vite';

const REFERENCE_LINE = '@reference "@vben/tailwind-config/theme";\n';

/**
 * 为使用 @apply 的 Vue 单文件组件 <style> 块自动注入 @reference。
 *
 * 在 Tailwind CSS v4 中，每个 Vue 单文件组件的 <style scoped> 块都会作为
 * 独立 CSS 模块处理。样式块通过 @apply 使用自定义主题工具类（如
 * bg-primary、text-foreground）时，需要借助 @reference 访问 @theme 定义。
 * 此插件会自动完成注入，组件无需逐一手动添加。
 */
export function viteTailwindReferencePlugin(): Plugin {
  return {
    enforce: 'pre',
    name: 'vite:tailwind-reference',
    transform(code, id) {
      // 仅处理 Vue 单文件组件的样式块
      if (!id.includes('.vue')) {
        return null;
      }
      if (!id.includes('type=style')) {
        return null;
      }
      // 已存在 @reference 时跳过
      if (code.includes('@reference')) {
        return null;
      }
      // 仅在样式块使用 @apply 时注入
      if (!code.includes('@apply')) {
        return null;
      }
      return {
        code: REFERENCE_LINE + code,
        map: null,
      };
    },
  };
}
