/** 只加载官方项目脚本，悬浮图标和会话窗口均由SaleSmartly自身提供。 */
export function installSalesmartly(scriptUrl: unknown, target: Document = document): boolean {
  if (typeof scriptUrl !== 'string') return false;
  const url = scriptUrl.trim();
  try {
    const parsed = new URL(url);
    const authority = url.match(/^https:\/\/([^/?#]+)/i)?.[1] || '';
    const rawPath = url.match(/^https:\/\/[^/?#]+(\/[^?#]*)/i)?.[1] || '';
    if (url.length > 2000 || /[\s\\]/.test(url) || parsed.protocol !== 'https:' ||
      !/^[A-Za-z0-9.-]+(?::[0-9]+)?$/.test(authority) || url.includes('#') ||
      !(parsed.hostname === 'salesmartly.com' || parsed.hostname.endsWith('.salesmartly.com')) ||
      parsed.username || parsed.password || parsed.hash || (parsed.port && parsed.port !== '443') ||
      !/^\/js\/[A-Za-z0-9_-]+\.js$/.test(rawPath)) return false;
  } catch { return false; }
  // SPA切换页面/语言不重复安装；修改接入地址后在下一次页面刷新时使用新配置。
  if (target.getElementById('token-salesmartly-sdk')) return false;
  const script = target.createElement('script');
  script.id = 'token-salesmartly-sdk';
  script.src = url;
  script.async = true;
  script.referrerPolicy = 'strict-origin-when-cross-origin';
  script.addEventListener('error', () => script.remove(), { once: true });
  target.head.append(script);
  return true;
}
