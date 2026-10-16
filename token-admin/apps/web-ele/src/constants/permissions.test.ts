import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { COMPUTE_PERMISSIONS, CONTENT_PERMISSIONS, CONFIG_PERMISSIONS, ACTIVITY_PERMISSIONS } from './permissions';
import { normalizePermissionSelection } from './permission-selection';
import { routePermissions } from './route-permissions';

const manifest = JSON.parse(readFileSync(resolve(__dirname, '../../../../../docs/v23-permissions.json'), 'utf8')) as { nodes: Array<{ id: number; field?: string; leaf: boolean }> };
const leafTree = manifest.nodes.filter(node => node.leaf).map(node => ({ id: node.id }));

describe('v23路由及叶子权限边界', () => {
  it('业务常量逐一对应审定清单，不复用结构码', () => {
    for (const node of manifest.nodes.filter(node => node.field)) {
      expect(COMPUTE_PERMISSIONS[node.field as keyof typeof COMPUTE_PERMISSIONS]).toBe(String(node.id));
      expect(node.leaf).toBe(true);
    }
  });
  it('页面只接受自身叶子，旧无效结构码不能激活新页面', () => {
    expect(routePermissions('/operation/product-management')).toEqual(['70100', '70101', 'super']);
    expect(routePermissions('/operation/levels')).toEqual(['70200', '70201', 'super']);
    expect(routePermissions('/operation/models')).not.toEqual(expect.arrayContaining(['703', '7', '610']));
    expect(routePermissions('/user/deposit-addresses')).toEqual(['20400', 'super']);
    expect(routePermissions('/user')).not.toEqual(expect.arrayContaining(['20401', '20402', '20107']));
    expect(routePermissions('/user/members')).not.toContain('20107');
    expect(routePermissions('/system/account')).toEqual(['60200', 'super']);
    expect(routePermissions('/system')).not.toContain('6');
  });
  it('共用内容、配置及活动组件使用当前分类权限', () => {
    expect(new Set(Object.values(CONTENT_PERMISSIONS).map(pair => pair.write)).size).toBe(4);
    expect(CONFIG_PERMISSIONS.products.write).not.toBe(CONFIG_PERMISSIONS.levels.write);
    expect(CONFIG_PERMISSIONS['fee-rules'].write).not.toBe(CONFIG_PERMISSIONS['transfer-rules'].write);
    expect(ACTIVITY_PERMISSIONS.WHEEL.write).not.toBe(ACTIVITY_PERMISSIONS.RED_PACKET.write);
  });
  it('角色只回显当前叶子，丢弃旧码、未知码与结构节点', () => {
    const selected = normalizePermissionSelection(['61013', '7', '703', '710', '999999'], leafTree);
    expect(selected).toEqual([]);
    expect(normalizePermissionSelection(['601'], leafTree)).toEqual([]);
    expect(normalizePermissionSelection(['610'], leafTree)).toEqual([]);
    expect(normalizePermissionSelection(['70101'], leafTree)).toEqual([70101]);
  });
  it('系统路由不会通过历史父权限授权', () => {
    for (const path of ['/system', '/system/basics', '/system/account', '/system/role', '/system/log', '/system/material', '/system/translates', '/system/notifications', '/system/ip-whitelist']) {
      expect(routePermissions(path).every(code => code === 'super' || leafTree.some(node => String(node.id) === code))).toBe(true);
    }
  });
});
