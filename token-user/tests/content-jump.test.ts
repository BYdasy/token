import { describe, expect, it } from 'vitest';
import { createMemoryHistory, createRouter } from 'vue-router';
import { contentJump } from '../src/utils/content-jump';

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/deposit', component: {} },
    { path: '/token', component: {} },
    { path: '/products/:id', component: {} },
    { path: '/:pathMatch(.*)*', redirect: '/token' },
  ],
});

describe('内容跳转方式和站内路径校验', () => {
  it('历史未配置方式的链接默认新窗口', () => {
    expect(contentJump({ jumpUrl: 'https://example.invalid/info' }, router)).toEqual({ href: 'https://example.invalid/info', path: undefined, target: '_blank' });
    expect(contentJump({ jumpUrl: 'javascript:alert(1)' }, router)).toBeUndefined();
  });
  it('站内充值与动态详情保留查询和锚点，交给既有路由', () => {
    expect(contentJump({ jumpMode: 'INTERNAL', jumpUrl: '/deposit?currency=USDT#address' }, router)).toEqual({ href: '/deposit?currency=USDT#address', path: '/deposit?currency=USDT#address', target: undefined });
    expect(contentJump({ jumpMode: 'INTERNAL', jumpUrl: '/products/42' }, router)?.path).toBe('/products/42');
  });
  it.each(['https://example.invalid/deposit', '//example.invalid', '/%2fexample.invalid', '/%5cexample.invalid', '/%252fexample.invalid', '/deposit%0a', '/unknown-page', '/\\example.invalid', 'javascript:alert(1)'])('拒绝外链、编码绕过与不存在的内部页面 %s', (jumpUrl) => {
    expect(contentJump({ jumpMode: 'INTERNAL', jumpUrl }, router)).toBeUndefined();
  });
});
