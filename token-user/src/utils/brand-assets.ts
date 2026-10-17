/** 同一版品牌资源显式换址，避免浏览器沿用更新前的安装图标。 */
export function brandAsset(file: 'icon-192.png' | 'icon-512.png' | 'favicon.svg' | 'apple-touch-icon.png' | 'manifest.webmanifest' | 'tensor-ai-logo.png' | 'brand.png'): string {
  return `${import.meta.env.BASE_URL}${file}?v=20261018-tensor-ai`;
}
