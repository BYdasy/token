import { requestClient } from './request';

export interface SystemPageQuery {
  current?: number;
  filterParams?: Record<string, any>;
  size?: number;
  sortColumn?: {
    order?: '' | 'ascending' | 'descending';
    prop?: string;
  };
}

export interface SystemPageResult<T = Record<string, any>> {
  current: number;
  items: T[];
  size: number;
  total: number;
}

export interface SystemRolePermission {
  ignoredCount?: number;
  selected: Array<number | string>;
  treeData: Record<string, any>[];
}

/** 操作日志沿用服务端ad_record字段，不暴露后台账号内部编号。 */
export interface SystemOperationLog {
  id: number;
  username?: string;
  status?: number | null;
  type?: string;
  remarks?: string;
  loginIp?: string;
  clientos?: string;
  domain?: string;
  address?: string;
  actionTime?: string;
  deviceId?: string;
}

export interface SystemTranslationRecord {
  actionTime?: string;
  completionAllowed?: boolean;
  systemMessageEvent?: string;
  systemMessageEnabled?: boolean;
  editable?: boolean;
  englishTvalue?: string;
  id?: number;
  isDel?: number;
  kind?: 'CUSTOM' | 'SYSTEM' | string;
  lang?: string;
  languages?: string[];
  prop?: number;
  protectedPackage?: boolean;
  title?: string;
  translations?: Record<string, string>;
  translationEditable?: boolean;
  tkey: string;
  tvalue?: string;
  type?: number;
}

export interface SystemTranslationCompletion {
  completeKeyCount: number;
  enabledLanguageCount: number;
  existingCount: number;
  expectedCount: number;
  incompleteKeyCount: number;
  inserted?: number;
  keyCount: number;
  missingAfter?: number;
  missingBefore?: number;
  missingCount: number;
  templateName?: string;
  translatedKeys?: number;
  type: number;
}

export type SystemTranslationCompletionTaskStatus =
  | 'FAILED'
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCEEDED';

export interface SystemTranslationCompletionTask {
  createdAt?: string;
  errorCode?: string;
  errorMessage?: string;
  finishedAt?: string;
  inserted: number;
  missingAfter: number;
  missingBefore: number;
  processedKeys: number;
  progressPercent: number;
  startedAt?: string;
  status: SystemTranslationCompletionTaskStatus;
  taskId: number;
  templateName?: string;
  templateType: number;
  totalKeys: number;
  translatedKeys: number;
}

export interface SystemTranslationLanguage {
  chineseName?: string;
  createTime?: string;
  id?: number;
  lang: string;
  name: string;
  nativeName?: string;
  remark?: string;
  sortNo?: number;
  status?: number;
  updateTime?: string;
}

export interface SystemTranslationLanguageOverview {
  languages?: SystemTranslationLanguage[];
  options?: Array<{
    chineseName?: string;
    label?: string;
    lang?: string;
    name?: string;
    nativeName?: string;
    sortNo?: number;
    status?: number;
    value?: string;
  }>;
  total?: number;
}

export interface SystemTranslationLanguageListQuery {
  current?: number;
  filterParams?: {
    key?: string;
    name?: string;
    status?: number | string;
  };
  size?: number;
}

export interface SystemTranslationListQuery {
  current?: number;
  filterParams?: {
    key?: string;
    langTitle?: string;
    content?: string;
    prop?: number | string;
    type?: number | string;
  };
  size?: number;
}

function normalizePage<T>(data: any): SystemPageResult<T> {
  const items = data?.records ?? data?.items ?? data?.list ?? [];

  return {
    current: Number(data?.current ?? data?.page ?? 1),
    items,
    size: Number(data?.size ?? data?.pageSize ?? items.length ?? 10),
    total: Number(data?.total ?? data?.count ?? items.length ?? 0),
  };
}

function buildQuery(data: SystemPageQuery = {}) {
  return {
    filterParams: data.filterParams ?? {},
    page: {
      current: data.current ?? 1,
      size: data.size ?? 10,
    },
    sortColumn: data.sortColumn ?? {
      order: '',
      prop: '',
    },
  };
}

function buildTranslationQuery(data: SystemTranslationListQuery = {}) {
  return {
    filterParams: data.filterParams ?? {},
    page: {
      current: data.current ?? 1,
      size: data.size ?? 20,
    },
  };
}

export async function fetchSystemAccountList<T = Record<string, any>>(
  data?: SystemPageQuery,
) {
  const result = await requestClient.post<SystemPageResult<T>>(
    '/system/user_role/account/list',
    buildQuery(data),
  );

  return normalizePage<T>(result);
}

export function createSystemAccount<T = Record<string, any>>(
  data: Record<string, any>,
) {
  return requestClient.post<T>('/system/user_role/account/create', data);
}

export function updateSystemAccount<T = Record<string, any>>(
  data: Record<string, any>,
) {
  return requestClient.post<T>('/system/user_role/account/update', data);
}

export function updateSystemAccountState(uid: number, state: number) {
  return requestClient.post('/system/user_role/account/enable/update', {
    state,
    uid,
  });
}

export function deleteSystemAccount(uid: number) {
  return requestClient.post('/system/user_role/account/delete', { uid });
}

export function fetchSystemAccountRoles<T = Record<string, any>>() {
  return requestClient.post<T[]>('/system/user_role/account/roleList');
}

export async function fetchSystemRoleList<T = Record<string, any>>(
  data?: SystemPageQuery,
) {
  const result = await requestClient.post<SystemPageResult<T>>(
    '/system/user_role/role/list',
    buildQuery(data),
  );

  return normalizePage<T>(result);
}

export function createSystemRole<T = Record<string, any>>(
  data: Record<string, any>,
) {
  return requestClient.post<T>('/system/user_role/role/create', data);
}

export function updateSystemRole<T = Record<string, any>>(
  data: Record<string, any>,
) {
  return requestClient.post<T>('/system/user_role/role/update', data);
}

export function deleteSystemRole(id: number) {
  return requestClient.post('/system/user_role/role/delete', { id });
}

export function fetchSystemRolePermission(roleId: number) {
  return requestClient.post<SystemRolePermission>(
    '/system/user_role/permission/read',
    { id: roleId },
  );
}

export function updateSystemRolePermission(data: {
  newPlatformMenuIds: number[];
  roleId: number;
}) {
  return requestClient.post('/system/user_role/permission/update', data);
}

export async function fetchSystemOperationLogs<T = SystemOperationLog>(
  data?: SystemPageQuery,
  signal?: AbortSignal,
) {
  const result = await requestClient.post<SystemPageResult<T>>(
    '/system/system_log/read',
    buildQuery(data),
    { signal },
  );

  return normalizePage<T>(result);
}

export function fetchSystemLogActions(q = '', signal?: AbortSignal) {
  return requestClient.post<string[]>('/system/system_log/actions', { q }, { signal });
}

export function fetchTranslationTemplates() {
  return requestClient.post<SystemTranslationRecord[]>('/translates/tem', {});
}

export function createTranslationTemplate(data: SystemTranslationRecord) {
  return requestClient.post<SystemTranslationRecord>('/translates/tem/add', data);
}

export function updateTranslationTemplate(data: SystemTranslationRecord) {
  return requestClient.post<SystemTranslationRecord>(
    '/translates/tem/update',
    data,
  );
}

export function deleteTranslationTemplate(id: number) {
  return requestClient.post('/translates/tem/delete', { id });
}

export function fetchTranslationTemplateCompletion(type: number) {
  return requestClient.post<SystemTranslationCompletion>(
    '/translates/template/completion/overview',
    { type },
  );
}

export function fillMissingTemplateTranslations(type: number) {
  return requestClient.post<SystemTranslationCompletionTask>(
    '/translates/template/fill-missing',
    { type },
  );
}

export function fetchTranslationCompletionTask(taskId: number) {
  return requestClient.get<SystemTranslationCompletionTask>(
    `/translates/template/fill-missing/task/${taskId}`,
    {
      headers: { 'X-Client-Silent-Error': '1', 'X-Background-Request': 'true' },
      timeout: 15_000,
    },
  );
}

export async function fetchTranslationList(
  data?: SystemTranslationListQuery,
) {
  const result = await requestClient.post<any>(
    '/translates/list',
    buildTranslationQuery(data),
  );

  return normalizePage<SystemTranslationRecord>(result);
}

export function removeTranslation(tkey: string, type: number) {
  return requestClient.post('/translates/delete', { tkey, type });
}

export function translateToAllLanguages(data: {
  languages: string[];
  prop?: number;
  tvalue: string;
  type: number;
}, timeout = 15 * 60_000) {
  return requestClient.post<Record<string, string>>(
    '/translates/translateToAllLanguages',
    data,
    { timeout },
  );
}

export function translateAndAddBatch(data: SystemTranslationRecord) {
  return requestClient.post<SystemTranslationRecord[]>(
    '/translatesBatch/translateAdd',
    data,
    { timeout: 15 * 60_000 },
  );
}

export function updateTranslatesBatch(data: SystemTranslationRecord) {
  return requestClient.post<SystemTranslationRecord>(
    '/translatesBatch/update',
    data,
    { timeout: 10 * 60_000 },
  );
}

export async function fetchTranslationLanguagePage(
  data?: SystemTranslationLanguageListQuery,
) {
  const result = await requestClient.post<any>(
    '/translates/language/page',
    buildTranslationQuery(data),
  );

  return normalizePage<SystemTranslationLanguage>(result);
}

export function fetchTranslationLanguageOptions(data?: { enabledOnly?: boolean }) {
  return requestClient.post<SystemTranslationLanguageOverview>(
    '/translates/language/options',
    data ?? { enabledOnly: true },
  );
}

export function updateTranslationLanguage(data: Pick<SystemTranslationLanguage, 'id' | 'sortNo' | 'status'>) {
  return requestClient.post<SystemTranslationLanguage>(
    '/translates/language/update',
    data,
  );
}

export function fetchTranslationsByKeyBatch(data: {
  tkey: string;
  type?: number | string;
}) {
  return requestClient.post<Record<string, string>>(
    '/translates/getByKeyBatch',
    data,
  );
}
