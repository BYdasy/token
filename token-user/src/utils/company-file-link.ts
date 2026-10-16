/** 公司外链只在用户点击时打开；不作为 PDF 地址，也不经由平台代理下载。 */
export function companyFileLink(value: string) {
  if (!value || /[\\\x00-\x1f\x7f]/.test(value)) return '';
  try {
    const url = new URL(value);
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url.href : '';
  } catch { return ''; }
}
