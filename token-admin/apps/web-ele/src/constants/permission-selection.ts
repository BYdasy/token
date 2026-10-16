/** 角色编辑仅回显当前权限树的叶子，不展开父码或旧权限。 */
export interface PermissionTreeNode { id: number | string; children?: PermissionTreeNode[]; }
export function normalizePermissionSelection(selected: Array<number | string>, tree: PermissionTreeNode[]) {
  const leafIds = new Set<number>();
  const visit = (nodes: PermissionTreeNode[]) => nodes.forEach(node => {
    if (node.children?.length) visit(node.children);
    else if (Number.isSafeInteger(Number(node.id))) leafIds.add(Number(node.id));
  });
  visit(tree);
  return [...new Set(selected.map(Number))]
    .filter(id => leafIds.has(id));
}
