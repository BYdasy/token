<script setup lang="ts">
import type { TableColumnSpec } from './tableExport';

import { computed, onBeforeUnmount, ref } from 'vue';
import { useI18n } from '@vben/locales';
import { useUserStore } from '@vben/stores';
import {
  ElButton,
  ElDialog,
  ElMessage,
  ElProgress,
  ElTransfer,
} from 'element-plus';
import { saveTableColumnPreference } from '#/api/table-columns';

import { createTableCsv, normalizeTableColumns } from './tableExport';
import './business.css';

const props = defineProps<{
  enabled?: boolean;
  storageKey: string;
  columnOptions: TableColumnSpec[];
  columns?: string[];
  rows: Record<string, any>[];
  filename?: string;
  loading?: boolean;
  fetchPage?: (
    page: number,
    size: number,
  ) => Promise<{ records: Record<string, any>[]; total: number }>;
}>();
const emit = defineEmits<{ 'update:columns': [value: string[]] }>();
const { t } = useI18n();
const userStore = useUserStore();
const currentUid = () => {
  const info = userStore.userInfo as {
    uid?: string | number;
    userId?: string | number;
  } | null;
  return info?.uid ?? info?.userId;
};
const dialog = ref(false),
  exporting = ref(false),
  progress = ref(0);
const cancelled = ref(false);
const draft = ref<string[]>([]),
  preferenceSaving = ref(false);
const selected = computed(() =>
  normalizeTableColumns(props.columns, props.columnOptions),
);
const transferOptions = computed(() =>
  props.columnOptions.map((column) => ({
    key: column.key,
    label: column.label,
    disabled: !!column.required || preferenceSaving.value,
    required: !!column.required,
  })),
);
function openColumns() {
  if (!props.enabled) return;
  draft.value = [...selected.value];
  dialog.value = true;
}
function move(key: string, offset: number) {
  if (preferenceSaving.value) return;
  const next = [...draft.value],
    from = next.indexOf(key),
    to = from + offset;
  if (from < 0 || to < 0 || to >= next.length) return;
  if (
    props.columnOptions.some(
      (column) => column.required && [key, next[to]].includes(column.key),
    )
  )
    return;
  [next[from], next[to]] = [next[to]!, next[from]!];
  draft.value = next;
}
function changeDraft(value: Array<string | number>) {
  draft.value = normalizeTableColumns(value, props.columnOptions);
}
async function saveColumns() {
  if (!props.enabled || preferenceSaving.value) return;
  const pageKey = props.storageKey;
  const uid = currentUid();
  const columns = normalizeTableColumns(draft.value, props.columnOptions);
  preferenceSaving.value = true;
  try {
    await saveTableColumnPreference(pageKey, columns);
    if (pageKey !== props.storageKey || uid !== currentUid()) return;
    emit('update:columns', columns);
    dialog.value = false;
    ElMessage.success(t('admin.table.columnsSaved'));
  } finally {
    preferenceSaving.value = false;
  }
}
function download(rows: Record<string, any>[], columns: TableColumnSpec[]) {
  const url = URL.createObjectURL(
    new Blob([createTableCsv(columns, rows)], {
      type: 'text/csv;charset=utf-8',
    }),
  );
  const link = document.createElement('a');
  link.href = url;
  link.download = `${(props.filename || props.storageKey).replaceAll(
    /[<>:"/\\|?*\u0000-\u001f]/g,
    '_',
  )}.csv`;
  link.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}
async function exportRows(all: boolean) {
  if (!props.enabled || exporting.value) return;
  const columns = selected.value
    .map((key) => props.columnOptions.find((column) => column.key === key)!)
    .filter(Boolean);
  if (!all || !props.fetchPage) return download(props.rows, columns);
  const fetchPage = props.fetchPage;
  exporting.value = true;
  cancelled.value = false;
  progress.value = 0;
  const records: Record<string, any>[] = [];
  const seen = new Set<string>();
  const started = Date.now();
  try {
    let expected = 0;
    for (let page = 1; ; page++) {
      if (cancelled.value) return;
      let timer: ReturnType<typeof setTimeout> | undefined;
      const data = await Promise.race([
        fetchPage(page, 100),
        new Promise<never>((_, reject) => {
          timer = setTimeout(
            () => reject(new Error(t('admin.table.exportTimeout'))),
            30_000,
          );
        }),
      ]).finally(() => clearTimeout(timer));
      if (cancelled.value) return;
      expected = page === 1 ? data.total : expected;
      if (data.total !== expected)
        throw new Error(t('admin.table.exportChanged'));
      if (expected > 20_000 || records.length + data.records.length > 20_000)
        throw new Error(t('admin.table.exportLimit'));
      for (const row of data.records) {
        const identity = row.id ?? row.uid;
        if (identity == null) continue;
        const key = String(identity);
        if (seen.has(key)) throw new Error(t('admin.table.exportChanged'));
        seen.add(key);
      }
      records.push(...data.records);
      progress.value = Math.min(
        100,
        Math.round((records.length * 100) / Math.max(expected, 1)),
      );
      if (records.length >= expected || !data.records.length) break;
      if (Date.now() - started > 180_000)
        throw new Error(t('admin.table.exportTimeout'));
    }
    if (records.length < expected)
      throw new Error(t('admin.table.exportChanged'));
    download(records, columns);
    ElMessage.success(t('admin.table.exportDone', { count: records.length }));
  } catch (error) {
    if (!cancelled.value)
      ElMessage.error(
        error instanceof Error ? error.message : t('admin.table.exportFailed'),
      );
  } finally {
    exporting.value = false;
  }
}
onBeforeUnmount(() => {
  cancelled.value = true;
});
</script>

<template>
  <div v-if="enabled" class="business-table-toolbar" :aria-busy="exporting">
    <ElButton
      :disabled="loading || exporting || !rows.length"
      @click="exportRows(false)"
      >{{ t('admin.table.exportPage') }}</ElButton
    >
    <ElButton
      v-if="fetchPage"
      :disabled="loading || exporting"
      @click="exportRows(true)"
      >{{ t('admin.table.exportAll') }}</ElButton
    >
    <ElButton @click="openColumns">{{ t('admin.table.columns') }}</ElButton>
    <template v-if="exporting">
      <ElProgress class="export-progress" :percentage="progress" />
      <ElButton @click="cancelled = true">{{
        t('admin.common.cancel')
      }}</ElButton>
    </template>
  </div>
  <ElDialog
    v-if="enabled"
    v-model="dialog"
    append-to-body
    class="column-dialog"
    top="5vh"
    :title="t('admin.table.columns')"
    width="min(850px, 96vw)"
    :close-on-click-modal="false"
    :close-on-press-escape="!preferenceSaving"
    :show-close="!preferenceSaving"
  >
    <p class="column-hint">{{ t('admin.table.columnsPersistHelp') }}</p>
    <ElTransfer
      :model-value="draft"
      :data="transferOptions"
      :titles="[
        t('admin.table.availableColumns'),
        t('admin.table.selectedColumns'),
      ]"
      :filter-placeholder="t('admin.table.searchColumns')"
      :button-texts="[t('admin.table.availableColumns'), t('admin.table.selectedColumns')]"
      target-order="push"
      filterable
      class="column-transfer"
      @update:model-value="changeDraft"
    >
      <template #default="{ option }">
        <span class="column-label" :title="option.label">{{ option.label }}</span>
        <span v-if="option.required" class="column-required">{{
          t('admin.table.requiredColumn')
        }}</span>
        <span
          v-else-if="draft.includes(option.key)"
          class="column-move"
          @click.stop.prevent
        >
          <ElButton
            size="small"
            text
            :aria-label="`${t('admin.table.moveUp')} · ${option.label}`"
            :disabled="
              preferenceSaving ||
              draft.indexOf(option.key) <=
                columnOptions.filter((column) => column.required).length
            "
            @click.stop.prevent="move(option.key, -1)"
            >{{ t('admin.table.moveUp') }}</ElButton
          >
          <ElButton
            size="small"
            text
            :aria-label="`${t('admin.table.moveDown')} · ${option.label}`"
            :disabled="
              preferenceSaving || draft.indexOf(option.key) === draft.length - 1
            "
            @click.stop.prevent="move(option.key, 1)"
            >{{ t('admin.table.moveDown') }}</ElButton
          >
        </span>
      </template>
    </ElTransfer>
    <template #footer>
      <ElButton
        :disabled="preferenceSaving"
        @click="draft = normalizeTableColumns(null, columnOptions)"
        >{{ t('admin.table.resetColumns') }}</ElButton
      >
      <ElButton :disabled="preferenceSaving" @click="dialog = false">{{
        t('admin.common.cancel')
      }}</ElButton>
      <ElButton
        type="primary"
        :loading="preferenceSaving"
        @click="saveColumns"
        >{{ t('admin.common.save') }}</ElButton
      >
    </template>
  </ElDialog>
</template>
