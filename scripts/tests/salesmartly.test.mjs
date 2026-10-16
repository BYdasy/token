import assert from 'node:assert/strict';
import test from 'node:test';
import { installSalesmartly } from '../../token-user/src/utils/salesmartly.ts';

function browserDocument() {
  const nodes = [];
  return {
    nodes,
    getElementById: id => nodes.find(node => node.id === id),
    createElement: type => {
      assert.equal(type, 'script');
      const node = { listeners: {}, addEventListener(name, action) { this.listeners[name] = action; },
        remove() { nodes.splice(nodes.indexOf(this), 1); } };
      return node;
    },
    head: { append: node => nodes.push(node) },
  };
}

test('空配置与伪造或可执行输入不加载外部脚本', () => {
  const document = browserDocument();
  for (const value of ['', undefined, 'javascript:alert(1)', '<script src="https://assets.salesmartly.com/js/project_1.js"></script>',
    'https://assets.salesmartly.com.evil.test/js/project_1.js', 'https://plugin-code.salesmartly.com/js/project_1.js#fragment',
    'https://plugin-code.salesmartly.com.evil.test/js/project_1.js', 'https://plugin-code.salesmartly.com/js/project_1.js/extra',
    'https://assets.salesmartly.com/js/project_1.js#', 'https://@assets.salesmartly.com/js/project_1.js',
    'https://%61ssets.salesmartly.com/js/project_1.js',
    'http://assets.salesmartly.com/js/project_1.js', 'https://user@assets.salesmartly.com/js/project_1.js'])
    assert.equal(installSalesmartly(value, document), false);
  assert.equal(document.nodes.length, 0);
});

test('官方安装域名与项目链接可变，接受用户提供的plugin-code地址及版本参数', () => {
  for (const url of [
    'https://plugin-code.salesmartly.com/js/project_393667_404668_1752494487.js',
    'https://assets.salesmartly.com/js/project_next.js?v=2',
    'https://sub.assets.salesmartly.com/js/install.js?version=3',
  ]) {
    const document = browserDocument();
    assert.equal(installSalesmartly(url, document), true);
    assert.equal(document.nodes[0].src, url);
  }
});

test('SPA内只安装一次官方原生插件，避免多个悬浮窗口', () => {
  const document = browserDocument();
  const url = 'https://assets.salesmartly.com/js/project_123_abc-DEF.js';
  assert.equal(installSalesmartly(url, document), true);
  assert.equal(installSalesmartly(url, document), false);
  assert.equal(installSalesmartly('https://assets.salesmartly.com/js/project_other.js', document), false);
  assert.equal(document.nodes.length, 1);
  assert.equal(document.nodes[0].src, url);
  assert.equal(document.nodes[0].async, true);
});

test('脚本加载失败可重新尝试，不修改现有客服入口', () => {
  const document = browserDocument();
  const url = 'https://assets.salesmartly.com/js/project_123.js';
  installSalesmartly(url, document);
  document.nodes[0].listeners.error();
  assert.equal(installSalesmartly(url, document), true);
  assert.equal(document.nodes.length, 1);
});
