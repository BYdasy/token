import type { MaybeRefOrGetter } from 'vue';
import type { TableColumnSpec } from './tableExport';

import { computed, ref, toValue, watch } from 'vue';
import { useUserStore } from '@vben/stores';

import { normalizeTableColumns } from './tableExport';
import {
  fetchTableColumnPreference,
  getTablePreferenceRevision,
} from '#/api/table-columns';

export type { TableColumnSpec } from './tableExport';

/** 仅保存栏位偏好；按登录账号隔离，不持久化业务记录。 */
export function useTableColumns(
  storageKey: MaybeRefOrGetter<string>,
  options: MaybeRefOrGetter<TableColumnSpec[]>,
  enabled: MaybeRefOrGetter<boolean> = false,
) {
  const userStore = useUserStore();
  const currentUid = computed(() => {
    const info = userStore.userInfo as {
      uid?: string | number;
      userId?: string | number;
    } | null;
    return info?.uid ?? info?.userId;
  });
  const key = computed(
    () =>
      `${import.meta.env.VITE_APP_NAMESPACE}:columns:${
        currentUid.value ?? 'anonymous'
      }:${toValue(storageKey)}`,
  );
  const selectedColumns = ref<string[]>([]);
  const preferenceLoading = ref(false);
  let generation = 0;
  watch(
    [
      key,
      () => toValue(enabled),
      () =>
        toValue(options)
          .map((column) => column.key)
          .join('|'),
    ],
    async () => {
      const current = ++generation;
      preferenceLoading.value = false;
      if (!toValue(enabled)) {
        selectedColumns.value = normalizeTableColumns(null, toValue(options));
        return;
      }
      let saved: unknown;
      try {
        saved = JSON.parse(sessionStorage.getItem(key.value) || 'null');
      } catch {
        /* 隐私模式下仅保留当前页面偏好。 */
      }
      selectedColumns.value = normalizeTableColumns(saved, toValue(options));
      const initial = JSON.stringify(selectedColumns.value);
      const pageKey = toValue(storageKey);
      const revision = getTablePreferenceRevision(pageKey);
      if (!currentUid.value) return;
      preferenceLoading.value = true;
      try {
        const data = await fetchTableColumnPreference(pageKey);
        // 页面、登录身份或本地选择变化后，不允许迟到请求覆盖。
        if (
          current === generation &&
          revision === getTablePreferenceRevision(pageKey) &&
          JSON.stringify(selectedColumns.value) === initial &&
          data.columns
        )
          selectedColumns.value = normalizeTableColumns(
            data.columns,
            toValue(options),
          );
      } catch {
        // 偏好读取失败时保留临时栏位，业务列表仍可操作。
      } finally {
        if (current === generation) preferenceLoading.value = false;
      }
    },
    { immediate: true },
  );
  watch(
    selectedColumns,
    (selected) => {
      if (!toValue(enabled)) return;
      try {
        sessionStorage.setItem(key.value, JSON.stringify(selected));
      } catch {
        /* 存储不可用时不影响列表。 */
      }
    },
    { deep: true },
  );
  const visibleColumns = computed(() =>
    normalizeTableColumns(selectedColumns.value, toValue(options))
      .map(
        (selected) =>
          toValue(options).find((column) => column.key === selected)!,
      )
      .filter(Boolean),
  );
  return { selectedColumns, visibleColumns, preferenceLoading };
}
