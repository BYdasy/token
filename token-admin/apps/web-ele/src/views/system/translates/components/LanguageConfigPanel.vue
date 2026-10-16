<script lang="ts" setup>
import { useFilterRecovery } from '#/components/business/useFilterRecovery';
import { adminTimeColumn } from '#/utils/display-time';
import BusinessTableToolbar from '#/components/business/BusinessTableToolbar.vue';
import { chineseLanguageLabel, englishLanguageLabel } from '#/components/business/businessLanguages';
import { useTableColumns, type TableColumnSpec } from '#/components/business/useTableColumns';
import type { FormInstance, FormRules, TagProps } from 'element-plus';

import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { useI18n } from '@vben/locales';

import {
  ElButton,
  ElCard,
  ElDatePicker,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElOption,
  ElPagination,
  ElSelect,
  ElTableColumn,
  ElTag,
} from 'element-plus';
import { ElTable } from '#/adapter/table';

import { fetchTranslationLanguagePage, updateTranslationLanguage } from '#/api/system';
import { refreshAdminLocale } from '#/locales';
import { permissionCodes, SYSTEM_PERMISSIONS } from '#/constants/permissions';

const { t } = useI18n();

const tableData = ref<Array<Record<string, any>>>([]);
const tableLoading = ref(false);
const queryLoading = ref(false);
const resetLoading = ref(false);
const formVisible = ref(false);
const submitting = ref(false);
const formRef = ref<FormInstance>();

const statusOptions = computed<
  Array<{
    label: string;
    type: TagProps['type'];
    value: number;
  }>
>(() => [
  {
    label: t('admin.translation.language.enabled'),
    type: 'success',
    value: 1,
  },
  {
    label: t('admin.translation.language.disabled'),
    type: 'info',
    value: 0,
  },
]);

const queryModel = reactive({
  updateRange: [] as string[] | null,
  key: '',
  name: '',
  status: undefined as number | undefined,
});

const pager = reactive({
  current: 1,
  size: 20,
  total: 0,
});

useFilterRecovery('languages', { queryModel, pager });
const formModel = reactive({
  id: undefined as number | undefined,
  lang: '',
  chineseName: '',
  name: '',
  nativeName: '',
  sortNo: 0,
  status: 1,
});

const formRules = computed<FormRules>(() => ({
  id: [
    {
      message: t('admin.translation.language.idMissing'),
      required: true,
      trigger: 'change',
    },
  ],
}));

onMounted(loadData);

function cleanFilter(value: any) {
  return value === undefined || value === null || value === '' ? undefined : value;
}

function buildQuery() {
  return {
    current: pager.current,
    filterParams: {
      key: cleanFilter(queryModel.key.trim()),
      name: cleanFilter(queryModel.name.trim()),
      endDate: cleanFilter(queryModel.updateRange?.[1]),
      startDate: cleanFilter(queryModel.updateRange?.[0]),
      status: cleanFilter(queryModel.status),
    },
    size: pager.size,
  };
}

let listGeneration = 0;
onBeforeUnmount(() => { ++listGeneration; });
async function loadData(kind: '' | 'query' | 'reset' = '') {
  const current = ++listGeneration;
  tableLoading.value = true;
  queryLoading.value = kind === 'query'; resetLoading.value = kind === 'reset';

  try {
    const appliedQuery = buildQuery();
    const page = await fetchTranslationLanguagePage(appliedQuery);
    if (current !== listGeneration) return;
    exportQuery.value = JSON.parse(JSON.stringify(appliedQuery));
    tableData.value = page.items;
    pager.total = page.total;
  } finally {
    if (current === listGeneration) {
      tableLoading.value = false; queryLoading.value = false; resetLoading.value = false;
    }
  }
}

async function search() {
  pager.current = 1;
  await loadData('query');
}

async function resetSearch() {
  queryModel.key = '';
  queryModel.name = '';
  queryModel.status = undefined;
  queryModel.updateRange = [];
  pager.current = 1;

  await loadData('reset');
}

function resetForm(row: Record<string, any>) {
  formModel.id = row?.id;
  formModel.lang = row?.lang || '';
  formModel.chineseName = chineseLanguageName(row);
  formModel.name = englishLanguageName(row);
  formModel.nativeName = nativeLanguageName(row);
  formModel.sortNo = Number(row?.sortNo ?? 0);
  formModel.status = Number(row?.status ?? 1);
}

function openEdit(row: Record<string, any>) {
  resetForm(row);
  formVisible.value = true;
}

async function submitForm() {
  await formRef.value?.validate();
  submitting.value = true;

  try {
    await updateTranslationLanguage({
      id: formModel.id,
      sortNo: formModel.sortNo,
      status: formModel.status,
    });

    await refreshAdminLocale();

    ElMessage.success(t('admin.translation.language.saved'));
    formVisible.value = false;
    await loadData();
  } finally {
    submitting.value = false;
  }
}

function handleSizeChange(size: number) {
  pager.size = size;
  pager.current = 1;
  void loadData();
}

function handleCurrentChange(current: number) {
  pager.current = current;
  void loadData();
}

function statusLabel(value: any) {
  return statusOptions.value.find((item) => `${item.value}` === `${value}`)?.label || '-';
}

function statusType(value: any): TagProps['type'] {
  return statusOptions.value.find((item) => `${item.value}` === `${value}`)?.type || 'info';
}

function languageCode(row: Record<string, any>) {
  return String(row?.lang || '').trim();
}

function chineseLanguageName(row: Record<string, any>) {
  return chineseLanguageLabel(languageCode(row), row?.chineseName, row?.name) || '-';
}

function englishLanguageName(row: Record<string, any>) {
  return englishLanguageLabel(languageCode(row), row?.name) || '-';
}

function nativeLanguageName(row: Record<string, any>) {
  const configured = String(row?.nativeName || '').trim();
  return configured || '-';
}

const columnOptions = computed<TableColumnSpec[]>(() => [
  { key: 'id', label: 'ID', exportValue: (row: Record<string, any>) => row.id },
  {
    key: 'lang',
    label: t('admin.translation.language.code'),
    exportValue: (row: Record<string, any>) => row.lang,
  },
  {
    key: 'column2',
    label: t('admin.translation.language.chineseName'),
    exportValue: (row: Record<string, any>) => row.column2,
  },
  {
    key: 'name',
    label: t('admin.translation.language.englishName'),
    exportValue: (row: Record<string, any>) => row.name,
  },
  {
    key: 'nativeName',
    label: t('admin.translation.language.originalName'),
    exportValue: (row: Record<string, any>) => row.nativeName,
  },
  {
    key: 'sortNo',
    label: t('admin.translation.language.sort'),
    exportValue: (row: Record<string, any>) => row.sortNo,
  },
  {
    key: 'status',
    label: t('admin.common.status'),
    exportValue: (row: Record<string, any>) => row.status,
  },
  {
    key: 'updateTime',
    label: t('admin.common.updateTime'),
    exportValue: (row: Record<string, any>) => row.updateTime,
  },
]);
const { selectedColumns, visibleColumns } = useTableColumns(
  'translates-components-LanguageConfigPanel',
  columnOptions
);
const exportQuery = ref<ReturnType<typeof buildQuery>>();
const exportPage = computed(() => {
  const query = JSON.parse(JSON.stringify(exportQuery.value || {}));
  return async (current: number, size: number) => {
    const page = await fetchTranslationLanguagePage({ ...query, current, size });
    return { records: page.items, total: page.total };
  };
});
</script>

<template>
  <div class="system-translation-panel">
    <ElCard class="exp-card exp-search-card mb-5" shadow="never">
      <div class="exp-toolbar">
        <div class="exp-filter-row">
          <ElDatePicker
            v-model="queryModel.updateRange"
            class="exp-time-filter"
            clearable
            type="datetimerange"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :start-placeholder="t('admin.common.updateStartTime')"
            :end-placeholder="t('admin.common.updateEndTime')"
          />
          <ElInput
            v-model="queryModel.key"
            class="exp-filter"
            clearable
            maxlength="40"
            :placeholder="t('admin.translation.language.codeSearchPlaceholder')"
            @keyup.enter="search"
          />
          <ElInput
            v-model="queryModel.name"
            class="exp-filter"
            clearable
            maxlength="80"
            :placeholder="t('admin.compute.v25.language.nameSearchPlaceholder')"
            @keyup.enter="search"
          />
          <ElSelect
            v-model="queryModel.status"
            class="exp-filter"
            clearable
            :placeholder="t('admin.common.status')"
          >
            <ElOption
              v-for="item in statusOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
          <ElButton :loading="queryLoading" type="primary" @click="search">
            {{ t('admin.common.search') }}
          </ElButton>
          <ElButton :loading="resetLoading" @click="resetSearch">
            {{ t('admin.common.reset') }}
          </ElButton>
          <BusinessTableToolbar
            :fetch-page="exportPage"
            v-model:columns="selectedColumns"
            :column-options="columnOptions"
            :rows="tableData"
            :loading="tableLoading"
            storage-key="translates-components-LanguageConfigPanel"
          />
        </div>
      </div>
    </ElCard>

    <ElCard class="exp-card exp-table-card" shadow="never">
      <ElTable v-loading="tableLoading" :data="tableData" row-key="id" size="small" stripe>
        <template v-for="column in visibleColumns" :key="column.key">
          <ElTableColumn v-if="column.key === 'id'" label="ID" min-width="90" prop="id" />
          <ElTableColumn
            v-if="column.key === 'lang'"
            :label="t('admin.translation.language.code')"
            min-width="130"
            prop="lang"
          />
          <ElTableColumn
            v-if="column.key === 'column2'"
            :label="t('admin.translation.language.chineseName')"
            min-width="160"
          >
            <template #default="{ row }">{{ chineseLanguageName(row) }}</template>
          </ElTableColumn>
          <ElTableColumn
            v-if="column.key === 'name'"
            :label="t('admin.translation.language.englishName')"
            min-width="160"
            prop="name"
          >
            <template #default="{ row }">{{ englishLanguageName(row) }}</template>
          </ElTableColumn>
          <ElTableColumn
            v-if="column.key === 'nativeName'"
            :label="t('admin.translation.language.originalName')"
            min-width="160"
            prop="nativeName"
          >
            <template #default="{ row }">
              <span class="language-native-name" :lang="languageCode(row)">
                {{ nativeLanguageName(row) }}
              </span>
            </template>
          </ElTableColumn>
          <ElTableColumn
            v-if="column.key === 'sortNo'"
            :label="t('admin.translation.language.sort')"
            width="90"
            prop="sortNo"
          />
          <ElTableColumn
            v-if="column.key === 'status'"
            :label="t('admin.common.status')"
            width="100"
            prop="status"
          >
            <template #default="{ row }">
              <ElTag :type="statusType(row.status)">
                {{ statusLabel(row.status) }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn
            v-if="column.key === 'updateTime'"
            :label="t('admin.common.updateTime')"
            min-width="170"
            prop="updateTime"
            :formatter="adminTimeColumn"
          />
        </template>

        <ElTableColumn fixed="right" :label="t('admin.common.operation')" width="100">
          <template #default="{ row }">
            <ElButton
              v-access:code="
                permissionCodes(
                  SYSTEM_PERMISSIONS.translation.language.update,
                )
              "
              link
              size="small"
              type="primary"
              @click="openEdit(row)"
            >
              {{ t('admin.common.edit') }}
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>

      <div class="exp-pagination">
        <ElPagination
          v-model:current-page="pager.current"
          v-model:page-size="pager.size"
          background
          layout="total, sizes, prev, pager, next, jumper"
          :page-sizes="[10, 20, 50, 100, 200]"
          :total="pager.total"
          @current-change="handleCurrentChange"
          @size-change="handleSizeChange"
        />
      </div>
    </ElCard>

    <ElDialog
      v-model="formVisible"
      append-to-body
      destroy-on-close
      :title="t('admin.translation.language.editTitle')"
      width="560px"
    >
      <ElForm
        ref="formRef"
        class="exp-dialog-form"
        label-position="right"
        label-width="110px"
        :model="formModel"
        :rules="formRules"
      >
        <ElFormItem :label="t('admin.translation.language.code')">
          <ElInput v-model="formModel.lang" disabled />
        </ElFormItem>
        <ElFormItem :label="t('admin.translation.language.chineseName')">
          <ElInput v-model="formModel.chineseName" disabled />
        </ElFormItem>
        <ElFormItem :label="t('admin.translation.language.englishName')">
          <ElInput v-model="formModel.name" disabled />
        </ElFormItem>
        <ElFormItem :label="t('admin.translation.language.originalName')">
          <ElInput v-model="formModel.nativeName" disabled />
        </ElFormItem>
        <ElFormItem :label="t('admin.translation.language.sort')" prop="sortNo">
          <ElInputNumber v-model="formModel.sortNo" :min="0" />
        </ElFormItem>
        <ElFormItem :label="t('admin.common.status')" prop="status">
          <ElSelect v-model="formModel.status" class="w-full">
            <ElOption
              v-for="item in statusOptions"
              :key="item.value"
              :label="item.label"
              :disabled="['zh', 'en'].includes(formModel.lang.toLowerCase().split('-')[0] || '') && item.value === 0"
              :value="item.value"
            />
          </ElSelect>
        </ElFormItem>
      </ElForm>

      <template #footer>
        <ElButton @click="formVisible = false">
          {{ t('admin.common.cancel') }}
        </ElButton>
        <ElButton :loading="submitting" type="primary" @click="submitForm">
          {{ t('admin.common.save') }}
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>
