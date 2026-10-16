import type { ComputedRef, Ref } from 'vue';
import type { ComputeQuery, ComputeRecord, PageResource } from '#/api/compute';
import { getCurrentInstance, onScopeDispose, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useAccessStore } from '@vben/stores';
import { fetchComputePage } from '#/api/compute';
import { toUtc } from '#/components/business/shared';
import { pageFilters } from './pageState';

export function usePaged(
  resource: ComputedRef<PageResource> | Ref<PageResource>,
  allowed: () => boolean,
  extraQuery: () => ComputeQuery = () => ({}),
) {
  const loading = ref(false),
    failed = ref(false);
  const rows = ref<ComputeRecord[]>([]);
  const dates = ref<string[] | null>([]);
  const query = reactive({
    keyword: '',
    username: '',
    usernameMatchMode: 'contains',
    usernames: '',
    isTest: '',
    online: '',
    topName: '',
    topMemberId: '',
    email: '',
    phone: '',
    registrationCountry: '',
    registrationIp: '',
    lastLoginCountry: '',
    lastLoginIp: '',
    address: '',
    depth: '',
    minDepth: '',
    maxDepth: '',
    status: '',
    enabled: '',
    memberId: '',
    teamMemberId: '',
    teamUsername: '',
    orderNo: '',
    attentionIgnored: '',
    gatewayReference: '',
    receiverAddress: '',
    txHash: '',
    detailType: '',
    productId: '',
    firstDeposit: '',
    collectionState: '',
    kind: '',
    contentKey: '',
    direction: '',
    currency: '',
    groupId: undefined as number | undefined,
    agentId: undefined as number | undefined,
    level: undefined as number | undefined,
    inviteCode: '',
    inviterId: '',
    inviterName: '',
    activityId: '',
    redemptionCode: '',
    walletType: '',
    network: '',
    minAmount: '',
    maxAmount: '',
    minAvailable: '',
    maxAvailable: '',
    minDeposits: '',
    maxDeposits: '',
    minFrozen: '',
    maxFrozen: '',
    minWithdrawals: '',
    maxWithdrawals: '',
    minPrincipal: '',
    maxPrincipal: '',
    minIncome: '',
    maxIncome: '',
    searchField: '',
    matchMode: '',
    sortBy: '',
    sortOrder: '',
  });
  const defaults = { ...query };
  const metadata = ref<Record<string, any>>({});
  const pager = reactive({ page: 1, size: 20, total: 0 });
  let generation = 0;
  let applied: ComputeQuery = { ...extraQuery() };
  const component = getCurrentInstance();
  const route = component ? useRoute() : undefined;
  const access = component ? useAccessStore() : undefined;
  const ownerToken = access?.accessToken;
  // 分类决定独立列表；外置筛选（如团队深度／任务）变化不能换掉该列表的恢复键。
  const category = extraQuery();
  const stateKey = route ? JSON.stringify([route.path, resource.value, { kind: category.kind, type: category.type }]) : undefined;
  const stateVersion = route ? pageFilters.version(route.path) : undefined;
  const ownerPath = route?.path;
  let disposed = false;
  const saved = stateKey ? pageFilters.get(stateKey) : undefined;
  if (saved) {
    for (const key of Object.keys(defaults)) if (key in saved.query) (query as Record<string, unknown>)[key] = saved.query[key];
    dates.value = saved.dates.filter(value => typeof value === 'string');
    pager.page = Number.isInteger(saved.page) && saved.page > 0 ? saved.page : 1;
    pager.size = Number.isInteger(saved.size) && saved.size > 0 && saved.size <= 100 ? saved.size : 20;
    const restored = Object.fromEntries(Object.entries(saved.applied).filter(([, value]) =>
      value === undefined || ['string', 'number', 'boolean'].includes(typeof value))) as ComputeQuery;
    applied = { ...restored, ...extraQuery() };
  }
  function remember() {
    if (stateKey && ownerPath && stateVersion === pageFilters.version(ownerPath) && !disposed && access?.accessToken === ownerToken)
      pageFilters.save(stateKey, { query: { ...query }, dates: [...(dates.value || [])], page: pager.page, size: pager.size, applied: { ...applied } });
  }
  if (component) {
    watch([query, dates, () => pager.page, () => pager.size], remember, { deep: true });
    onScopeDispose(() => { disposed = true; generation++; });
  }
  let exportSnapshot:
    | { resource: PageResource; query: ComputeQuery }
    | undefined;
  async function load(extra: ComputeQuery = {}, background = false) {
    if (disposed || !allowed()) return;
    const token = access?.accessToken;
    const current = ++generation;
    loading.value = true;
    failed.value = false;
    try {
      const data = await fetchComputePage(resource.value, {
        ...applied,
        ...extra,
        // 页面固有分类不能被通用筛选里的空字段覆盖，否则搜索后会跨模块混查。
        ...extraQuery(),
        page: pager.page,
        size: pager.size,
      }, background);
      if (!disposed && current === generation && token === access?.accessToken && allowed()) {
        rows.value = data.records;
        pager.total = data.total;
        metadata.value = data;
        remember();
      }
    } catch {
      if (current === generation) failed.value = true;
    } finally {
      if (current === generation) loading.value = false;
    }
  }
  async function search() {
    applied = {
      ...Object.fromEntries(
        Object.entries(query).map(([key, value]) => [
          key,
          value === '' ? undefined : value,
        ]),
      ),
      keyword: query.keyword.trim() || undefined,
      status: query.status || undefined,
      memberId: query.memberId.trim() || undefined,
      currency: query.currency || undefined,
      groupId: query.groupId,
      agentId: query.agentId,
      startTime: toUtc(dates.value?.[0]),
      endTime: toUtc(dates.value?.[1]),
      ...extraQuery(),
    };
    pager.page = 1;
    remember();
    await load();
  }
  function resetFilters() {
    // 通用字段以外的筛选会由页面动态写入；仅assign默认值会将它们留到下一次搜索。
    for (const key of Object.keys(query)) {
      if (!Object.hasOwn(defaults, key)) delete (query as Record<string, unknown>)[key];
    }
    Object.assign(query, defaults);
    dates.value = [];
    pager.page = 1;
    applied = { ...extraQuery() };
    remember();
  }
  async function reset() {
    resetFilters();
    await search();
  }
  async function resize() {
    pager.page = 1;
    await load();
  }
  async function sort(sortBy: string, sortOrder: string) {
    query.sortBy = sortBy;
    query.sortOrder = sortOrder;
    applied = {
      ...applied,
      sortBy: sortBy || undefined,
      sortOrder: sortOrder || undefined,
    };
    pager.page = 1;
    await load();
  }
  async function exportPage(page: number, size: number) {
    if (!allowed()) return { records: [], total: 0 };
    if (page === 1 || !exportSnapshot)
      exportSnapshot = { resource: resource.value, query: { ...applied } };
    return fetchComputePage(exportSnapshot.resource, {
      ...exportSnapshot.query,
      page,
      size,
    });
  }
  return {
    rows,
    metadata,
    query,
    pager,
    dates,
    loading,
    failed,
    load,
    search,
    reset,
    resetFilters,
    restoredFilters: Boolean(saved),
    resize,
    sort,
    exportPage,
  };
}
