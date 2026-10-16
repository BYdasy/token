import { afterEach, describe, expect, it, vi } from 'vitest';
import { userImpersonationUrl } from './memberAccess';

function setup(href: string) {
  vi.stubGlobal('window', { location: { href } });
  vi.stubEnv('VITE_USER_APP_BASE', '/');
  vi.stubEnv('VITE_USER_APP_PORT', '6677');
  vi.stubEnv('VITE_USER_APP_URL', '');
  vi.stubEnv('DEV', false);
}
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });
describe('模拟登录的环境地址解析', () => {
  it('根据当前后台子域定位用户站并保持实例公共路径，凭据只在fragment中', () => {
    setup('https://admin.example.test/token/?private=1#/user/members');
    const url = new URL(userImpersonationUrl('one-time-ticket'));
    expect(url.origin).toBe('https://example.test');
    expect(url.pathname).toBe('/');
    expect(url.search).toBe(''); expect(url.hash).toBe('#impersonation-ticket=one-time-ticket');
  });
  it('本地使用当前用户端实际环境端口，不沿用后台端口或生产域名规则', () => {
    setup('http://127.0.0.1:5789/token/#/user/members');
    vi.stubEnv('DEV', true);
    expect(userImpersonationUrl('ticket')).toBe('http://127.0.0.1:6677/#impersonation-ticket=ticket');
  });
  it('显式用户端部署地址优先且不继承管理端查询串', () => {
    setup('https://api.example.test/token/?private=1');
    vi.stubEnv('VITE_USER_APP_URL', 'https://members.example.test/app/');
    expect(userImpersonationUrl('ticket')).toBe('https://members.example.test/app/#impersonation-ticket=ticket');
  });
  it.each(['xiongda', 'api', 'admin', 'capital', 'ops.admin'])('任意后台前缀 %s 均进入实际用户主站', (prefix) => {
    setup(`https://${prefix}.winter-mute.vip/token/#/user/members`);
    expect(userImpersonationUrl('ticket')).toBe('https://winter-mute.vip/#impersonation-ticket=ticket');
  });
  it.each([
    ['https://winter-mute.vip/token/', 'https://winter-mute.vip'],
    ['https://www.winter-mute.vip/token/', 'https://winter-mute.vip'],
    ['https://admin.example.co.uk:8443/token/', 'https://example.co.uk:8443'],
    ['https://example.co.uk/token/', 'https://example.co.uk'],
    ['https://admin.tenant.github.io/token/', 'https://tenant.github.io'],
    ['https://tenant.github.io/token/', 'https://tenant.github.io'],
    ['https://admin.tenant.pages.dev/token/', 'https://tenant.pages.dev'],
  ])('正确识别多级公共后缀和托管租户边界：%s', (href, origin) => {
    setup(href);
    expect(new URL(userImpersonationUrl('ticket')).origin).toBe(origin);
  });
  it('独立 API 域名不能改变用户站入口', () => {
    setup('https://xiongda.winter-mute.vip/token/');
    vi.stubEnv('VITE_GLOB_API_URL', 'https://api.other-example.com/api');
    expect(new URL(userImpersonationUrl('ticket')).origin).toBe('https://winter-mute.vip');
  });
  it('配置 www 用户站时保留该入口，不自动切换为裸域', () => {
    setup('https://xiongda.winter-mute.vip/token/');
    vi.stubEnv('VITE_USER_APP_URL', 'https://www.winter-mute.vip/');
    expect(userImpersonationUrl('ticket')).toBe('https://www.winter-mute.vip/#impersonation-ticket=ticket');
  });
  it.each(['localhost', '127.0.0.1', '[::1]', '192.168.2.10', 'workstation'])('开发主机 %s 只替换为用户端配置端口', (host) => {
    setup(`http://${host}:5789/token/`);
    vi.stubEnv('DEV', true);
    expect(new URL(userImpersonationUrl('ticket')).origin).toBe(`http://${host}:6677`);
  });
  it.each(['localhost', '127.0.0.1', '[::1]', '192.168.2.10', 'workstation'])('生产主机 %s 不猜测域名或使用开发端口', (host) => {
    setup(`http://${host}:8080/token/`);
    expect(new URL(userImpersonationUrl('ticket')).origin).toBe(`http://${host}:8080`);
  });
  it('用户端子目录和 hash 部署都先进入实际站点入口', () => {
    setup('https://api.example.test/token/');
    vi.stubEnv('VITE_USER_APP_BASE', '/members/');
    expect(userImpersonationUrl('ticket')).toBe('https://example.test/members/#impersonation-ticket=ticket');
    vi.stubEnv('VITE_USER_APP_URL', '/members/#/login');
    expect(userImpersonationUrl('ticket')).toBe('https://api.example.test/members/#impersonation-ticket=ticket');
  });
  it('显式首页文件不添加目录斜线，且片段中的特殊字符正确编码', () => {
    setup('https://api.example.test/token/');
    vi.stubEnv('VITE_USER_APP_URL', 'https://members.example.test/app/index.html?old=1#/login');
    const url = new URL(userImpersonationUrl('ticket&next=/wallet'));
    expect(url.pathname).toBe('/app/index.html');
    expect(url.search).toBe('');
    expect(new URLSearchParams(url.hash.slice(1)).get('impersonation-ticket')).toBe('ticket&next=/wallet');
  });
  it('拒绝非 HTTP 地址，配置空白仍使用当前实例地址', () => {
    setup('https://api.example.test/token/');
    vi.stubEnv('VITE_USER_APP_URL', 'javascript:alert(1)');
    expect(() => userImpersonationUrl('ticket')).toThrow();
    vi.stubEnv('VITE_USER_APP_URL', '   ');
    expect(new URL(userImpersonationUrl('ticket')).origin).toBe('https://example.test');
  });
});
