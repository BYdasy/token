import assert from 'node:assert/strict';
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// 只读审计当前目录、服务端和前端权限；--write 仅更新路由对照文档，不生成迁移或修改数据库。
const root = fileURLToPath(new URL('../', import.meta.url));
const read = (path) => readFileSync(resolve(root, path), 'utf8');
const { nodes } = JSON.parse(read('docs/v23-permissions.json'));
const leaves = nodes.filter((node) => node.leaf).map((node) => String(node.id)).sort();
assert.equal(new Set(nodes.map((node) => node.id)).size, nodes.length, '权限编号重复');
assert.equal(new Set(nodes.map((node) => node.key)).size, nodes.length, '权限翻译键重复');
for (const node of nodes) {
  assert.ok(Number.isSafeInteger(node.id) && node.id > 0 && node.id !== 609);
  assert.ok(node.pid === 0 || nodes.some((parent) => parent.id === node.pid));
  assert.equal(node.leaf, !nodes.some((child) => child.pid === node.id));
  assert.ok(node.zh && node.en && !node.zh.startsWith('admin.') && !node.en.startsWith('admin.'));
  assert.ok(!Object.hasOwn(node, 'legacyAny'), '当前目录不得重新引入旧权限映射');
}
const java = read('token-server/src/main/java/org/token/custom/security/PermissionCodes.java');
const defined = (name) => [...java.match(new RegExp(`${name} = Set\\.of\\(([\\s\\S]*?)\\);`))[1].matchAll(/\d+/gu)]
  .map((match) => match[0]).sort();
assert.deepEqual(defined('GRANTABLE_IDS'), leaves, '服务端可授权叶子与目录不一致');
assert.deepEqual(defined('MENU_IDS'), nodes.map((node) => String(node.id)).sort(), '服务端菜单与目录不一致');
const frontend = [...new Set([...read('token-admin/apps/web-ele/src/constants/permissions.ts').matchAll(/'(\d+)'/gu)].map((match) => match[1]))].sort();
assert.deepEqual(frontend, leaves, '前端权限常量与目录不一致');
const controllers = 'token-server/src/main/java/org/token/controller/admin';
let declarations = 0;
for (const file of readdirSync(resolve(root, controllers)).filter((name) => name.endsWith('.java'))) {
  for (const call of read(`${controllers}/${file}`).matchAll(/hasAnyAuthority\(([^)]*)\)/gu)) {
    declarations++;
    for (const match of call[1].matchAll(/'([^']+)'/gu)) {
      assert.ok(match[1] === 'super' || leaves.includes(match[1]), `${file} 引用了不可授权码 ${match[1]}`);
    }
  }
}
const lines = ['# 当前权限码与路由对照', '', '来源为 `docs/v23-permissions.json`。只有操作叶子可授权；结构节点仅用于菜单分组。超级权限由超级角色标记授予。', '', '| 权限码 | 父码 | 排序 | 类型 | 名称 | 页面/路由 |', '| --- | --- | --- | --- | --- | --- |'];
const visited = new Set();
function visit(parentId) {
  for (const node of nodes.filter((item) => item.pid === parentId).sort((a, b) => a.sort - b.sort || a.id - b.id)) {
    assert.ok(!visited.has(node.id), '权限目录存在循环');
    visited.add(node.id);
    lines.push(`| ${node.id} | ${node.pid} | ${node.sort} | ${node.leaf ? '操作' : '分组'} | ${node.zh} | ${node.route || ''} |`);
    visit(node.id);
  }
}
visit(0);
assert.equal(visited.size, nodes.length, '权限目录存在不可达节点');
const document = lines.join('\n') + '\n';
if (process.argv.includes('--write')) writeFileSync(resolve(root, 'docs/权限码与路由对照.md'), document, 'utf8');
else if (process.argv.includes('--check')) assert.equal(read('docs/权限码与路由对照.md'), document, '权限对照文档需更新');
console.log(`权限审计通过：${nodes.length} 个节点、${leaves.length} 个叶子、${declarations} 个接口授权声明；未连接数据库。`);
