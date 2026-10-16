import { afterEach, describe, expect, it, vi } from 'vitest';

import { savedSharePreviewLinks } from './sharePreviewLinks';

function settings(entry = '') {
  return { userShareEntryUrl: entry, userShareTitle: 'Site title', userShareDescription: 'Description', userShareImage: '' };
}
function setup() {
  vi.stubGlobal('window', { location: { href: 'https://api.example.test/token/?private=1#admin-token' } });
  vi.stubEnv('VITE_USER_APP_URL', '');
  vi.stubEnv('VITE_USER_APP_BASE', '/');
  vi.stubEnv('DEV', false);
}
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

describe('已保存分享设置的普通入口和服务器预览', () => {
  it('复制主域根网址，API仅用于独立预览且OG目标与分享地址一致', () => {
    setup();
    const links = savedSharePreviewLinks(settings());
    const entry = new URL(links.shareLink), preview = new URL(links.previewLink);
    expect(entry.origin).toBe('https://example.test');
    expect(entry.pathname).toBe('/');
    expect([...entry.searchParams.keys()]).toEqual(['v']);
    expect(entry.hash).toBe('');
    expect(preview.origin).toBe(entry.origin);
    expect(preview.pathname).toBe('/api/compute/public/share');
    expect(preview.searchParams.get('target')).toBe(entry.pathname + entry.search);
    expect(preview.searchParams.get('preview')).toBe('1');
    expect(preview.searchParams.get('v')).toBe(entry.searchParams.get('v'));
    expect(JSON.stringify(links)).not.toMatch(/private|admin-token|impersonation-ticket/);
  });

  it('显式用户站及非默认端口优先，保存的子路径不丢失', () => {
    setup();
    const links = savedSharePreviewLinks(settings('https://members.example.test:8443/register'));
    expect(new URL(links.shareLink).origin).toBe('https://members.example.test:8443');
    expect(new URL(links.shareLink).pathname).toBe('/register');
    expect(new URL(links.previewLink).searchParams.get('target')).toBe('/register' + new URL(links.shareLink).search);
  });

  it('自定义后台前缀的默认分享链接同样使用用户主站', () => {
    setup();
    vi.stubGlobal('window', { location: { href: 'https://xiongda.winter-mute.vip/token/?private=1#admin-token' } });
    const links = savedSharePreviewLinks(settings());
    expect(new URL(links.shareLink).origin).toBe('https://winter-mute.vip');
    expect(new URL(links.previewLink).origin).toBe('https://winter-mute.vip');
    expect(JSON.stringify(links)).not.toMatch(/private|admin-token|impersonation-ticket/);
  });

  it('相同已保存配置生成稳定版本，文案更新后替换两个链接的版本', () => {
    setup();
    const first = savedSharePreviewLinks(settings());
    expect(savedSharePreviewLinks(settings())).toEqual(first);
    const changed = savedSharePreviewLinks({ ...settings(), userShareTitle: 'Changed title' });
    expect(changed.shareLink).not.toBe(first.shareLink);
    expect(changed.previewLink).not.toBe(first.previewLink);
  });

  it('本地开发仍沿用现有用户站端口而非管理端路径', () => {
    setup();
    vi.stubGlobal('window', { location: { href: 'http://127.0.0.1:5789/token/' } });
    vi.stubEnv('DEV', true);
    vi.stubEnv('VITE_USER_APP_PORT', '5578');
    expect(new URL(savedSharePreviewLinks(settings()).shareLink).origin).toBe('http://127.0.0.1:5578');
  });
});
