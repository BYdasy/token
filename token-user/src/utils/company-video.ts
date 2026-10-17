import type { CompanyFile } from '@/api/company-files';

/** 后端重建来源地址，前端仍只接收精确的嵌入域名及视频ID。 */
export function safeVideoSource(file: Pick<CompanyFile, 'playbackType' | 'url' | 'embedUrl'>): string {
  try {
    const url = new URL(file.playbackType === 'DIRECT' ? file.url : file.embedUrl || '');
    if (url.protocol !== 'https:' || url.username || url.password || (url.port && url.port !== '443') || url.hash) return '';
    if (file.playbackType === 'YOUTUBE') return url.hostname === 'www.youtube-nocookie.com' && /^\/embed\/[A-Za-z0-9_-]{11}$/.test(url.pathname) && !url.search ? url.href : '';
    if (file.playbackType === 'VIMEO') return url.hostname === 'player.vimeo.com' && /^\/video\/\d{1,12}$/.test(url.pathname) && !url.search ? url.href : '';
    if (file.playbackType === 'DIRECT' && url.hostname.includes('.') && !/[\[\]:]/.test(url.hostname) && !/^[\d.]+$/.test(url.hostname) && !/\.(?:localhost|local)$/.test(url.hostname) && /\.(mp4|webm)$/i.test(url.pathname)) return url.href;
  } catch { /* 非法或不完整来源不交给浏览器播放器。 */ }
  return '';
}

/** 只从已经验证的官方视频 ID 派生缩略图，不接收任意封面地址。 */
export function companyVideoPoster(file: Pick<CompanyFile, 'playbackType' | 'url' | 'embedUrl'>): string {
  const source = safeVideoSource(file);
  if (!source || file.playbackType !== 'YOUTUBE') return '';
  return `https://i.ytimg.com/vi/${new URL(source).pathname.split('/').at(-1)}/hqdefault.jpg`;
}

let currentPlayback: { stop: () => void } | undefined;

/** 同一用户页面只保留一个已激活播放器；先终止旧请求/播放器，再挂载新的。 */
export function createVideoPlaybackController(stop: () => void) {
  const owner = { stop };
  return {
    claim() {
      const previous = currentPlayback;
      currentPlayback = owner;
      if (previous !== owner) previous?.stop();
    },
    release() { if (currentPlayback === owner) currentPlayback = undefined; },
  };
}
