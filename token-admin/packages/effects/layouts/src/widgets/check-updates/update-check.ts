export const UPDATE_QUERY_KEY = '_token_build';

export function refreshedPageUrl(href: string, buildId: string) {
  const url = new URL(href);
  url.searchParams.set(UPDATE_QUERY_KEY, buildId);
  return url.href;
}

export function cleanRefreshUrl(href: string) {
  const url = new URL(href);
  if (!url.searchParams.has(UPDATE_QUERY_KEY)) return undefined;
  url.searchParams.delete(UPDATE_QUERY_KEY);
  return `${url.pathname}${url.search}${url.hash}`;
}

export async function fetchBuildId(url: string, signal: AbortSignal) {
  const manifest = new URL(url, window.location.href);
  manifest.searchParams.set('_check', `${Date.now()}-${Math.random().toString(36).slice(2)}`);
  const response = await fetch(manifest.href, {
    cache: 'no-store', credentials: 'same-origin', redirect: 'error', signal,
    headers: { Accept: 'application/json' },
  });
  if (!response.ok) return undefined;
  const value: unknown = await response.json();
  if (!value || typeof value !== 'object' || !('buildId' in value)) return undefined;
  return typeof value.buildId === 'string' && /^[\w.-]{1,128}$/.test(value.buildId) ? value.buildId : undefined;
}

/** 串行前台轮询。取消后迟到的响应不能提示，重新显示页面立即检查。 */
export function createUpdateMonitor(options: {
  currentBuildId: string;
  intervalMs: number;
  visible: () => boolean;
  read: (signal: AbortSignal) => Promise<string | undefined>;
  onUpdate: (buildId: string) => void;
}) {
  let active = false, disposed = false, notified = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let current: AbortController | undefined;
  const clear = () => { clearTimeout(timer); timer = undefined; };
  const check = async () => {
    clear();
    if (!active || disposed || notified || current || !options.visible() || !options.currentBuildId || options.intervalMs <= 0) return;
    const controller = new AbortController();
    current = controller;
    const timeout = setTimeout(() => controller.abort(), 10_000);
    try {
      const buildId = await options.read(controller.signal);
      if (current !== controller || controller.signal.aborted || !active || disposed || !options.visible()) return;
      if (buildId && buildId !== options.currentBuildId) {
        notified = true;
        options.onUpdate(buildId);
      }
    } catch { /* 网络或部署短暂不可用不弹错，下一次前台轮询继续检查。 */ }
    finally {
      clearTimeout(timeout);
      if (current === controller) {
        current = undefined;
        if (active && !disposed && !notified && options.visible()) timer = setTimeout(() => void check(), options.intervalMs);
      }
    }
  };
  const pause = () => { active = false; clear(); current?.abort(); current = undefined; };
  return {
    start() { if (disposed || notified) return; active = true; void check(); },
    pause,
    dispose() { disposed = true; pause(); },
  };
}
