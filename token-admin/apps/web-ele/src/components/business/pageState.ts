/** 只持久化筛选控件，不将查询结果、凭据或授权信息写入存储。 */
export interface SavedPageFilters {
  query: Record<string, unknown>;
  dates: string[];
  page: number;
  size: number;
  applied: Record<string, unknown>;
}

export class PageFilterCache {
  private owner = '';
  generation = 0;
  private closedPaths = new Map<string, number>();
  private filters: Record<string, SavedPageFilters> = {};
  constructor(private storage: Storage | undefined, private storageKey: string) {
    try {
      const saved = JSON.parse(storage?.getItem(storageKey) || 'null');
      if (saved && typeof saved.owner === 'string' && saved.filters && typeof saved.filters === 'object') {
        this.owner = saved.owner;
        this.filters = saved.filters;
      }
    } catch { /* 存储损坏或不可用时使用空筛选。 */ }
  }
  setOwner(owner: string) {
    if (owner !== this.owner) { this.generation++; this.owner = owner; this.filters = {}; this.persist(); }
  }
  version(path: string) { return `${this.generation}:${this.closedPaths.get(path) ?? 0}`; }
  get(key: string): SavedPageFilters | undefined {
    const value = this.filters[key];
    if (!value || !value.query || !Array.isArray(value.dates) || !value.applied) return;
    return JSON.parse(JSON.stringify(value));
  }
  save(key: string, value: SavedPageFilters) {
    if (!this.owner) return;
    this.filters[key] = JSON.parse(JSON.stringify(value));
    this.persist();
  }
  close(paths: readonly string[]) {
    for (const path of paths) this.closedPaths.set(path, (this.closedPaths.get(path) ?? 0) + 1);
    for (const key of Object.keys(this.filters)) {
      try { if (paths.includes(JSON.parse(key)[0])) delete this.filters[key]; }
      catch { delete this.filters[key]; }
    }
    this.persist();
  }
  clear() { this.generation++; this.owner = ''; this.filters = {}; this.closedPaths.clear(); this.persist(); }
  private persist() {
    try {
      if (!this.owner) this.storage?.removeItem(this.storageKey);
      else this.storage?.setItem(this.storageKey, JSON.stringify({ owner: this.owner, filters: this.filters }));
    } catch { /* 存储配额或隐私模式不能阻止正常查询。 */ }
  }
}

let storage: Storage | undefined;
try { storage = globalThis.sessionStorage; } catch { /* 使用内存回退。 */ }
export const pageFilters = new PageFilterCache(storage, `${import.meta.env.VITE_APP_NAMESPACE}:page-filters:v1`);

export function clearPageSelections(paths?: readonly string[]) {
  try {
    const prefix = `${import.meta.env.VITE_APP_NAMESPACE}:selection:`;
    for (const key of Object.keys(sessionStorage))
      if (key.startsWith(prefix) && (!paths || paths.some(path => key.includes(`:${path}:`)))) sessionStorage.removeItem(key);
  } catch { /* 存储不可用也必须允许关闭标签。 */ }
  window.dispatchEvent(new CustomEvent('token-page-state-cleared', { detail: paths }));
}
export function clearBusinessPageState() { pageFilters.clear(); clearPageSelections(); }
