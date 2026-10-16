import type { ComputeRecord, TranslationFormResource } from '#/api/compute';
import { computed, onScopeDispose, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { useI18n } from '@vben/locales';
import { useAccessStore, useUserStore } from '@vben/stores';
import { fetchFormTranslation, startFormTranslation } from '#/api/compute';
import { businessContentPayload, businessContentSnapshot } from './businessContent';

/** 显式翻译刷新本次所选语言；表单人工修改或操作上下文变化后拒绝所有迟到结果。 */
export function useBusinessFormTranslation(options: {
  model: ComputeRecord;
  resource: () => TranslationFormResource;
  kind?: () => 'MESSAGE' | 'TASK' | 'ANNOUNCEMENT' | 'ARTICLE';
  active: () => boolean;
  titleKey?: string;
  bodyKey?: string;
  /** 活动等多字段草稿复用相同的账号、会话及人工修改防迟到保护。 */
  adapter?: {
    payload: () => ComputeRecord;
    snapshot: () => string;
    valid: (body: ComputeRecord) => boolean;
    merge: (result: ComputeRecord, sourceLanguage: string, targets?: Set<string>) => void;
    start: (body: ComputeRecord) => Promise<ComputeRecord>;
    fetch: (taskId: number | string) => Promise<ComputeRecord>;
  };
}) {
  const { t } = useI18n();
  const accessStore = useAccessStore();
  const userStore = useUserStore();
  const task = ref<ComputeRecord>({}),
    loading = ref(false);
  const running = computed(() =>
    ['PENDING', 'RUNNING'].includes(task.value.status),
  );
  let generation = 0,
    polls = 0,
    merging = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let captured: ComputeRecord = {};
  let expectedSnapshot = '';
  let expectedResource: TranslationFormResource;
  let expectedKind: string | undefined;
  let expectedCredential: null | string;
  let expectedAccount: unknown;
  let targets: Set<string> | undefined;
  const account = () => {
    const user = userStore.userInfo as { uid?: number | string; userId?: number | string } | null;
    return user?.uid ?? user?.userId;
  };
  const snapshot = () => options.adapter?.snapshot() ?? businessContentSnapshot(options.model);
  const payload = () => {
    if (options.adapter) return options.adapter.payload();
    if (options.resource() === 'COMPANY_FILE' && !String(options.model.translations?.zh?.title || '').trim()) {
      options.model.translations ||= {};
      options.model.translations.zh = { title: options.model[options.titleKey || 'title'] || '', bodyHtml: '' };
    }
    const model = businessContentPayload(
      options.model,
      options.titleKey,
      options.bodyKey,
    );
    return {
      sourceId: options.model.id,
      ...(options.resource() === 'CONTENT' ? { kind: options.kind?.() ?? options.model.kind } : {}),
      title: model[options.titleKey || 'title'],
      bodyHtml: model[options.bodyKey || 'bodyHtml'],
      sourceLanguage: model.sourceLanguage,
      translations: JSON.parse(JSON.stringify(model.translations || {})),
    };
  };
  function stop() {
    generation++;
    if (timer) clearTimeout(timer);
    timer = undefined;
    loading.value = false;
  }
  function invalidate(notify = true) {
    const pending = loading.value || running.value;
    stop();
    task.value = {};
    expectedSnapshot = '';
    if (notify && pending)
      ElMessage.warning(t('admin.compute.v17.translation.formChanged'));
  }
  const unchanged = () =>
    expectedSnapshot === snapshot() &&
    expectedResource === options.resource() &&
    expectedKind === options.kind?.() &&
    expectedAccount === account() &&
    expectedCredential === accessStore.accessToken;

  // 同步监听可识别“修改后又改回”的情况，不能仅在响应到达时比较最终字段值。
  watch(
    [snapshot, options.resource, () => options.kind?.(), account, () => accessStore.accessToken],
    () => {
      if (!merging && expectedSnapshot && !unchanged()) invalidate();
    },
    { flush: 'sync' },
  );
  watch(options.active, (active) => { if (!active) invalidate(false); }, { flush: 'sync' });

  function merge(result: ComputeRecord) {
    if (!unchanged()) return invalidate();
    merging = true;
    try {
      if (options.adapter) {
        options.adapter.merge(result, captured.sourceLanguage, targets);
        expectedSnapshot = snapshot();
        return;
      }
      options.model.translations ||= {};
      for (const [language, raw] of Object.entries(result.translations || {})) {
        if (
          language === captured.sourceLanguage ||
          (targets && !targets.has(language))
        ) continue;
        const translated = raw as ComputeRecord;
        for (const field of ['title', 'bodyHtml']) {
          if (String(translated[field] || '').trim()) {
            const target = (options.model.translations[language] ||= {});
            target[field] = translated[field];
          }
        }
      }
      expectedSnapshot = snapshot();
    } finally {
      merging = false;
    }
  }
  function schedule() {
    if (running.value && polls++ < 90)
      timer = setTimeout(() => void refresh(), 2000);
  }
  async function refresh() {
    if (loading.value || !task.value.taskId || !options.active()) return;
    if (!unchanged()) return invalidate();
    if (timer) clearTimeout(timer);
    timer = undefined;
    const current = generation,
      resource = options.resource();
    loading.value = true;
    try {
      const result = options.adapter
        ? await options.adapter.fetch(task.value.taskId)
        : await fetchFormTranslation(resource, Number(task.value.taskId));
      if (current !== generation || !options.active()) return;
      task.value = result;
      merge(result);
      if (current === generation) schedule();
    } finally {
      if (current === generation) loading.value = false;
    }
  }
  async function start(languages?: string[]) {
    if (loading.value || running.value || !options.active()) return;
    const body = payload();
    if (options.adapter ? !options.adapter.valid(body) : (!String(body.title || '').trim() ||
      (options.resource() !== 'COMPANY_FILE' && !String(body.bodyHtml || '').trim())))
      return void ElMessage.warning(
        t('admin.compute.validation.contentRequired'),
      );
    stop();
    const current = generation;
    captured = structuredClone(body);
    expectedResource = options.resource();
    expectedKind = options.kind?.();
    expectedCredential = accessStore.accessToken;
    expectedAccount = account();
    expectedSnapshot = snapshot();
    targets = languages?.length ? new Set(languages) : undefined;
    loading.value = true;
    polls = 0;
    try {
      const request = { ...body, targetLanguages: languages?.length ? languages : undefined };
      const result = options.adapter
        ? await options.adapter.start(request)
        : await startFormTranslation(expectedResource, request);
      if (current !== generation || !options.active()) return;
      task.value = result;
      merge(result);
      if (current === generation) schedule();
    } finally {
      if (current === generation) loading.value = false;
    }
  }
  onScopeDispose(stop);
  return { task, loading, running, start, refresh, stop };
}
