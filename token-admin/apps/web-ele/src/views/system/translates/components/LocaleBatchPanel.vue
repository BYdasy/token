<script lang="ts" setup>
import { useFilterRecovery } from '#/components/business/useFilterRecovery';
import { adminTimeColumn } from '#/utils/display-time';
import BusinessTableToolbar from '#/components/business/BusinessTableToolbar.vue';
import {
  useTableColumns,
  type TableColumnSpec,
} from '#/components/business/useTableColumns';
import type { FormInstance, FormRules } from 'element-plus';

import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from '@vben/locales';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  ElButton,
  ElCard,
  ElDatePicker,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElPagination,
  ElRadio,
  ElRadioGroup,
  ElSelect,
  ElTabPane,
  ElTableColumn,
  ElTabs,
  ElTag,
  ElSwitch,
} from 'element-plus';
import { ElTable } from '#/adapter/table';

import {
  fetchTranslationLanguageOptions,
  fetchTranslationList,
  fetchTranslationsByKeyBatch,
  fetchTranslationTemplates,
  removeTranslation,
  translateAndAddBatch,
  translateToAllLanguages,
  updateTranslatesBatch,
} from '#/api/system';
import { refreshAdminLocale } from '#/locales';
import { permissionCodes } from '#/constants/permissions';
import { hasTranslationContent } from '#/utils/translation-content';
import { usePageSelection } from '#/components/business/usePageSelection';

import {
  fallbackTranslationLanguages,
  normalizeTranslationLanguages,
  stripHtml,
  templateName,
  translationPropOptions,
} from '../constants';

import TinymceMin from '../../../Tinymcemin.vue';
import TelegramTemplateEditor from '../../notifications/components/TelegramTemplateEditor.vue';

const { t } = useI18n();
const accessStore = useAccessStore();
const userStore = useUserStore();
function systemMessageName(event: string | undefined) {
  const names: Record<string, string> = {
    WELCOME: 'messageWelcome', DEPOSIT_APPROVED: 'messageDeposit', WITHDRAWAL_COMPLETED: 'messageWithdrawal',
  };
  return event && names[event] ? t(`admin.compute.v24Finance.${names[event]}`) : '';
}

const props = withDefaults(
  defineProps<{
    accessCode?: string;
    allowCreate?: boolean;
    allowDelete?: boolean;
    allowEdit?: boolean;
    showPropSelect?: boolean;
    showTemplateSelect?: boolean;
    type?: number;
  }>(),
  {
    accessCode: '60603',
    allowCreate: true,
    allowDelete: true,
    allowEdit: true,
    showPropSelect: false,
    showTemplateSelect: false,
  },
);

const tableData = ref<Array<Record<string, any>>>([]);
const tableLoading = ref(false);
const queryLoading = ref(false);
const resetLoading = ref(false);
const formVisible = ref(false);
const submitting = ref(false);
const translateLoading = ref(false);
const templateLoading = ref(false);
const languageLoading = ref(false);
const formMode = ref<'create' | 'edit'>('create');
const formRef = ref<FormInstance>();

function operationCode(operation: 'create' | 'delete' | 'read' | 'update') {
  const suffix = { create: '02', delete: '04', read: '01', update: '03' }[
    operation
  ];
  return `${props.accessCode}${suffix}`;
}

const templates = ref<Array<Record<string, any>>>([]);
const editableTemplates = computed(() =>
  templates.value.filter((item) => {
    const type = Number(item.id);
    return (
      type > 0 &&
      item.translationEditable !== false &&
      item.protectedPackage !== true &&
      type !== 5 &&
      type !== 7
    );
  }),
);
const languageOptions = ref<
  Array<{ label?: string; labelKey?: string; value: string }>
>(fallbackTranslationLanguages);

const queryModel = reactive({
  actionRange: [] as string[] | null,
  key: '',
  langTitle: '',
  content: '',
  prop: undefined as number | undefined,
  type: undefined as number | undefined,
});
const rememberedTemplate = usePageSelection(
  'translation-filter-template',
  () =>
    templates.value.length
      ? ['', ...editableTemplates.value.map((item) => String(item.id))]
      : [],
  '',
  false,
);
watch(
  rememberedTemplate,
  (value) => {
    queryModel.type = value ? Number(value) : undefined;
  },
  { immediate: true, flush: 'sync' },
);
watch(
  () => queryModel.type,
  (value) => {
    rememberedTemplate.value = value === undefined ? '' : String(value);
  },
  { flush: 'sync' },
);

const pager = reactive({
  current: 1,
  size: 20,
  total: 0,
});

useFilterRecovery(`translations:${props.type ?? 'kv'}`, { queryModel, pager });
const formModel = reactive<{
  id?: number;
  prop: number;
  title: string;
  tkey: string;
  translations: Record<string, string>;
  tvalue: string;
  type?: number;
  systemMessageEvent?: string;
  systemMessageEnabled?: boolean;
}>({
  id: undefined,
  prop: 0,
  title: '',
  translations: {},
  tkey: '',
  tvalue: '',
  type: props.type,
});

// 修改后再改回、切换条目或登录账号均使旧请求失效，不能仅比较最终中文文本。
let draftRevision = 0;
let applyingTranslation = false;
watch(
  [() => JSON.stringify(formModel), formVisible, () => accessStore.accessToken,
    () => userStore.userInfo?.userId, () => props.type],
  () => {
    if (!applyingTranslation) {
      draftRevision++;
      translateLoading.value = false;
    }
  },
  { flush: 'sync' },
);

const formRules = computed<FormRules>(() => ({
  title: [
    {
      message: t('admin.translation.batch.titleRequired'),
      required: true,
      trigger: 'blur',
    },
  ],
  tkey: [
    {
      message: t('admin.translation.batch.keyRequired'),
      required: true,
      trigger: 'blur',
    },
  ],
  tvalue: [
    {
      message: t('admin.translation.batch.chineseContentRequired'),
      required: true,
      trigger: 'blur',
    },
  ],
  type: [
    {
      message: t('admin.translation.batch.templateRequired'),
      required: true,
      trigger: 'change',
    },
  ],
}));

const targetLanguages = computed(() =>
  languageOptions.value.filter((item) => item.value && item.value !== 'zh'),
);

const targetLanguageValues = computed(() =>
  targetLanguages.value.map((item) => item.value),
);
const activeRichLanguage = usePageSelection(
  () => `translation-language-${props.type ?? 'kv'}`,
  () => targetLanguageValues.value,
  'en',
  false,
);

const showTemplateColumn = computed(() => props.showTemplateSelect);
const showTemplateField = computed(
  () => props.showTemplateSelect && props.type === undefined,
);
const showPropField = computed(() => props.showPropSelect);
const canModifyCurrentForm = computed(() =>
  formMode.value === 'create' ? props.allowCreate : props.allowEdit,
);

onMounted(async () => {
  await Promise.all([loadLanguages(), loadTemplates()]);
  await loadData();
});

function cleanFilter(value: any) {
  return value === undefined || value === null || value === ''
    ? undefined
    : value;
}

function cleanType(value: any) {
  const text = String(value ?? '').trim();
  return text ? Number(text) : undefined;
}

function normalizeTranslationMap(map: Record<string, string> = {}) {
  return Object.fromEntries(
    Object.entries(map)
      .map(([lang, text]) => [lang.trim().replaceAll('_', '-').toLowerCase(), text])
      .filter(([lang]) => lang && lang !== 'zh'),
  );
}

function currentQueryType() {
  return props.type === undefined ? cleanType(queryModel.type) : props.type;
}

function buildQuery() {
  const filterParams: Record<string, any> = {
    endDate: queryModel.actionRange?.[1] || undefined,
    key: cleanFilter(queryModel.key.trim()),
    langTitle: cleanFilter(queryModel.langTitle.trim()),
    content: cleanFilter(queryModel.content.trim()),
    startDate: queryModel.actionRange?.[0] || undefined,
  };

  const type = currentQueryType();
  if (type !== undefined) {
    filterParams.type = type;
  }

  if (props.showPropSelect) {
    filterParams.prop = cleanFilter(queryModel.prop);
  }

  return {
    current: pager.current,
    filterParams,
    size: pager.size,
  };
}

async function loadLanguages() {
  languageLoading.value = true;

  try {
    const result = await fetchTranslationLanguageOptions({ enabledOnly: true });
    languageOptions.value = normalizeTranslationLanguages(result);
    if (
      !targetLanguages.value.some(
        (item) => item.value === activeRichLanguage.value,
      )
    ) {
      activeRichLanguage.value = targetLanguages.value[0]?.value || '';
    }
  } finally {
    languageLoading.value = false;
  }
}

async function loadTemplates() {
  if (!props.showTemplateSelect) {
    return;
  }

  templateLoading.value = true;

  try {
    templates.value = await fetchTranslationTemplates();
  } finally {
    templateLoading.value = false;
  }
}

let listGeneration = 0;
onBeforeUnmount(() => { ++listGeneration; });
async function loadData(kind: '' | 'query' | 'reset' = '') {
  const current = ++listGeneration;
  tableLoading.value = true;
  queryLoading.value = kind === 'query'; resetLoading.value = kind === 'reset';

  try {
    const appliedQuery = buildQuery();
    const page = await fetchTranslationList(appliedQuery);
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
  queryModel.langTitle = '';
  queryModel.content = '';
  queryModel.type = undefined;
  queryModel.prop = undefined;
  queryModel.actionRange = [];
  pager.current = 1;

  await loadData('reset');
}

function resetForm(row?: Record<string, any>) {
  formModel.id = row?.id;
  formModel.tkey = row?.tkey || '';
  formModel.title = row?.title || '';
  formModel.tvalue = row?.tvalue || '';
  formModel.type = cleanType(row?.type ?? props.type ?? queryModel.type);
  formModel.prop = Number(row?.prop ?? queryModel.prop ?? 0);
  formModel.translations = {};
  formModel.systemMessageEvent = row?.systemMessageEvent;
  formModel.systemMessageEnabled = row?.systemMessageEnabled;
}

function openCreate() {
  formMode.value = 'create';
  resetForm();
  formVisible.value = true;
}

async function openEdit(row: Record<string, any>) {
  formMode.value = 'edit';
  resetForm(row);
  formVisible.value = true;
  const revision = ++draftRevision;
  translateLoading.value = true;

  try {
    const result = await fetchTranslationsByKeyBatch({
      tkey: formModel.tkey,
      type: formModel.type,
    });

    if (!formVisible.value || revision !== draftRevision) return;
    applyingTranslation = true;
    try { formModel.translations = normalizeTranslationMap(result); }
    finally { applyingTranslation = false; }
  } finally {
    if (revision === draftRevision) translateLoading.value = false;
  }
}

async function handleTranslate(languages: string[]) {
  if (translateLoading.value || !canModifyCurrentForm.value) return false;
  const type = formModel.type ?? props.type;
  if (type === undefined) {
    ElMessage.warning(t('admin.translation.batch.selectTemplateFirst'));
    return false;
  }

  if (!hasTranslationContent(formModel.tvalue, formModel.prop > 0, formModel.prop === 1)) {
    ElMessage.warning(t('admin.translation.batch.enterChineseFirst'));
    return false;
  }

  if (languages.length === 0) {
    ElMessage.warning(t('admin.translation.batch.enableTargetFirst'));
    return false;
  }

  const revision = ++draftRevision;
  translateLoading.value = true;
  try {
    const result = await translateToAllLanguages({
      languages,
      prop: formModel.prop,
      tvalue: formModel.tvalue,
      type,
    });

    if (!formVisible.value || revision !== draftRevision) return false;
    if (languages.some((language) => !hasTranslationContent(result[language], formModel.prop > 0, formModel.prop === 1))) {
      ElMessage.warning(t('admin.translation.v32.previewFailed'));
      return false;
    }
    applyingTranslation = true;
    try {
      // 显式翻译刷新选中语言，只修改当前草稿；普通运行时补译仍仅补缺失项。
      for (const language of languages) formModel.translations[language] = result[language]!;
    } finally { applyingTranslation = false; }
    ElMessage.success(t('admin.translation.batch.translated'));
    return true;
  } finally {
    if (revision === draftRevision) translateLoading.value = false;
  }
}

function handleTranslateAll() {
  return handleTranslate(targetLanguageValues.value);
}

async function submitForm() {
  if (
    !canModifyCurrentForm.value ||
    submitting.value ||
    translateLoading.value ||
    languageLoading.value
  )
    return;
  await formRef.value?.validate();
  if (
    !hasTranslationContent(
      formModel.translations.en,
      formModel.prop > 0,
      formModel.prop === 1,
    )
  ) {
    // 历史条目可能没有英文。先补当前中文的英文草稿，检查后再次保存，不自动补齐整包。
    if (await handleTranslate(['en'])) {
      activeRichLanguage.value = 'en';
      ElMessage.info(t('admin.translation.v32.englishReady'));
    }
    return;
  }

  const type = formModel.type ?? props.type;
  if (type === undefined) {
    ElMessage.warning(t('admin.translation.batch.templateRequired'));
    return;
  }

  submitting.value = true;

  try {
    const payload = {
      id: formModel.id,
      languages: targetLanguageValues.value,
      prop: formModel.prop,
      title: formModel.title,
      translations: normalizeTranslationMap(formModel.translations),
      tkey: formModel.tkey,
      tvalue: formModel.tvalue,
      type,
      systemMessageEnabled: formModel.systemMessageEnabled,
    };

    if (formMode.value === 'create') {
      await translateAndAddBatch(payload);
      ElMessage.success(t('admin.translation.batch.created'));
    } else {
      await updateTranslatesBatch(payload);
      ElMessage.success(t('admin.translation.batch.saved'));
    }

    if (type === 5) {
      await refreshAdminLocale();
    }

    formVisible.value = false;
    await loadData();
  } finally {
    submitting.value = false;
  }
}

async function removeRow(row: Record<string, any>) {
  await ElMessageBox.confirm(
    t('admin.translation.batch.deleteConfirm'),
    t('admin.translation.batch.deleteTitle'),
    {
      cancelButtonText: t('admin.common.cancel'),
      confirmButtonClass: 'el-button--danger',
      confirmButtonText: t('admin.common.delete'),
      type: 'warning',
    },
  );

  await removeTranslation(row.tkey, Number(row.type ?? currentQueryType()));
  ElMessage.success(t('admin.translation.batch.deleted'));

  if (tableData.value.length === 1 && pager.current > 1) {
    pager.current -= 1;
  }

  await loadData();
}

function contentTypeLabel(value: any) {
  const labelKey =
    translationPropOptions.find((item) => item.value === Number(value))
      ?.labelKey ?? 'admin.translation.contentType.plain';
  return t(labelKey);
}

function languageLabel(language: {
  label?: string;
  labelKey?: string;
  value: string;
}) {
  return language.labelKey
    ? t(language.labelKey)
    : language.label || language.value;
}

function contentTypeTag(value: any) {
  if (Number(value) === 2) return 'success';
  return Number(value) === 1 ? 'warning' : 'info';
}

function previewText(row: Record<string, any>) {
  const text = Number(row.prop) > 0 ? stripHtml(row.tvalue) : row.tvalue;
  return text || '-';
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

const columnOptions = computed<TableColumnSpec[]>(() => [
  {
    key: 'tkey',
    label: 'Key',
    exportValue: (row: Record<string, any>) => row.tkey,
  },
  {
    key: 'title',
    label: t('admin.translation.batch.title'),
    exportValue: (row: Record<string, any>) => row.title,
  },
  ...(showTemplateColumn
    ? [
        {
          key: 'type',
          label: t('admin.translation.batch.template'),
          exportValue: (row: Record<string, any>) => row.type,
        },
      ]
    : []),
  ...(showPropField
    ? [
        {
          key: 'prop',
          label: t('admin.translation.batch.contentType'),
          exportValue: (row: Record<string, any>) => row.prop,
        },
      ]
    : []),
  {
    key: 'tvalue',
    label: t('admin.translation.batch.chineseContent'),
    exportValue: (row: Record<string, any>) => row.tvalue,
  },
  {
    key: 'actionTime',
    label: t('admin.common.updateTime'),
    exportValue: (row: Record<string, any>) => row.actionTime,
  },
]);
const { selectedColumns, visibleColumns } = useTableColumns(
  'translates-components-LocaleBatchPanel',
  columnOptions,
);
const exportQuery = ref<ReturnType<typeof buildQuery>>();
const exportPage = computed(() => {
  const query = JSON.parse(JSON.stringify(exportQuery.value || {}));
  return async (current: number, size: number) => {
    const page = await fetchTranslationList({ ...query, current, size });
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
            v-model="queryModel.actionRange"
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
            maxlength="120"
            :placeholder="t('admin.translation.batch.keySearchPlaceholder')"
            @keyup.enter="search"
          />
          <ElInput
            v-model="queryModel.langTitle"
            class="exp-filter"
            clearable
            maxlength="120"
            :placeholder="t('admin.v6.translation.titleSearch')"
            @keyup.enter="search"
          />
          <ElInput
            v-model="queryModel.content"
            class="exp-filter"
            clearable
            maxlength="120"
            :placeholder="t('admin.v6.translation.contentSearch')"
            @keyup.enter="search"
          />
          <ElSelect
            v-if="showTemplateField"
            v-model="queryModel.type"
            class="exp-filter"
            clearable
            filterable
            :loading="templateLoading"
            :placeholder="t('admin.translation.batch.templateType')"
          >
            <ElOption
              v-for="item in editableTemplates"
              :key="item.id"
              :label="item.tkey"
              :value="Number(item.id)"
            />
          </ElSelect>
          <ElSelect
            v-if="showPropField"
            v-model="queryModel.prop"
            class="exp-filter"
            clearable
            :placeholder="t('admin.translation.batch.contentType')"
          >
            <ElOption
              v-for="item in translationPropOptions"
              :key="item.value"
              :label="t(item.labelKey)"
              :value="item.value"
            />
          </ElSelect>
          <ElButton :loading="queryLoading" type="primary" @click="search">
            {{ t('admin.common.search') }}
          </ElButton>
          <ElButton :loading="resetLoading" @click="resetSearch">
            {{ t('admin.common.reset') }}
          </ElButton>
          <ElButton
            v-if="props.allowCreate"
            v-access:code="
              permissionCodes(operationCode('create'))
            "
            type="primary"
            @click="openCreate"
          >
            {{ t('admin.translation.batch.add') }}
          </ElButton>
          <BusinessTableToolbar
            :fetch-page="exportPage"
            v-model:columns="selectedColumns"
            :column-options="columnOptions"
            :rows="tableData"
            :loading="tableLoading"
            storage-key="translates-components-LocaleBatchPanel"
          />
        </div>
      </div>
    </ElCard>

    <ElCard class="exp-card exp-table-card" shadow="never">
      <ElTable
        v-loading="tableLoading"
        :data="tableData"
        row-key="id"
        size="small"
        stripe
      >
        <template v-for="column in visibleColumns" :key="column.key">
          <ElTableColumn
            v-if="column.key === 'tkey'"
            label="Key"
            min-width="250"
            prop="tkey"
          />
          <ElTableColumn
            v-if="column.key === 'title'"
            :label="t('admin.translation.batch.title')"
            min-width="180"
            prop="title"
          >
            <template #default="{ row }">{{ systemMessageName(row.systemMessageEvent) || row.title }}</template>
          </ElTableColumn>
          <ElTableColumn
            v-if="column.key === 'type' && showTemplateColumn"
            :label="t('admin.translation.batch.template')"
            min-width="160"
            prop="type"
          >
            <template #default="{ row }">
              {{ templateName(templates, row.type) }}
            </template>
          </ElTableColumn>
          <ElTableColumn
            v-if="column.key === 'prop' && showPropField"
            :label="t('admin.translation.batch.contentType')"
            width="110"
            prop="prop"
          >
            <template #default="{ row }">
              <ElTag :type="contentTypeTag(row.prop)">
                {{ contentTypeLabel(row.prop) }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn
            v-if="column.key === 'tvalue'"
            :label="t('admin.translation.batch.chineseContent')"
            min-width="360"
            prop="tvalue"
          >
            <template #default="{ row }">
              <span class="system-translation-text">{{
                previewText(row)
              }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn
            v-if="column.key === 'actionTime'"
            :label="t('admin.common.updateTime')"
            min-width="170"
            prop="actionTime"
            :formatter="adminTimeColumn"
          />
        </template>

        <ElTableColumn
          fixed="right"
          :label="t('admin.common.operation')"
          :width="props.allowDelete ? 170 : 100"
        >
          <template #default="{ row }">
            <ElButton
              v-access:code="
                permissionCodes(
                  operationCode(props.allowEdit ? 'update' : 'read'),
                )
              "
              link
              size="small"
              type="primary"
              @click="openEdit(row)"
            >
              {{
                t(props.allowEdit ? 'admin.common.edit' : 'admin.common.view')
              }}
            </ElButton>
            <ElButton
              v-if="props.allowDelete && !row.systemMessageEvent"
              v-access:code="
                permissionCodes(
                  operationCode('delete'),
                )
              "
              link
              size="small"
              type="danger"
              @click="removeRow(row)"
            >
              {{ t('admin.common.delete') }}
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
      :title="
        t(
          formMode === 'create'
            ? 'admin.translation.batch.add'
            : props.allowEdit
              ? 'admin.translation.batch.edit'
              : 'admin.translation.batch.view',
        )
      "
      width="920px"
    >
      <ElForm
        ref="formRef"
        v-loading="translateLoading || languageLoading"
        class="exp-dialog-form"
        label-position="right"
        label-width="120px"
        :model="formModel"
        :rules="formRules"
      >
        <ElFormItem
          :label="t('admin.translation.batch.translationKey')"
          prop="tkey"
        >
          <ElInput
            v-model="formModel.tkey"
            clearable
            :disabled="formMode === 'edit'"
            :placeholder="t('admin.translation.batch.keyPlaceholder')"
          />
        </ElFormItem>
        <ElFormItem :label="t('admin.translation.batch.title')" prop="title">
          <ElInput
            v-model="formModel.title"
            clearable
            :disabled="!canModifyCurrentForm || !!formModel.systemMessageEvent"
            :placeholder="t('admin.translation.batch.titlePlaceholder')"
          />
        </ElFormItem>
        <ElFormItem
          v-if="showTemplateField"
          :label="t('admin.translation.batch.templateType')"
          prop="type"
        >
          <ElSelect
            v-model="formModel.type"
            class="w-full"
            filterable
            :loading="templateLoading"
            :disabled="formMode === 'edit'"
            :placeholder="t('admin.translation.batch.templatePlaceholder')"
          >
            <ElOption
              v-for="item in editableTemplates"
              :key="item.id"
              :label="item.tkey"
              :value="Number(item.id)"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem
          v-if="showPropField"
          :label="t('admin.translation.batch.contentType')"
          prop="prop"
        >
          <ElRadioGroup
            v-model="formModel.prop"
            :disabled="!canModifyCurrentForm || !!formModel.systemMessageEvent"
          >
            <ElRadio
              v-for="item in translationPropOptions"
              :key="item.value"
              :label="item.value"
            >
              {{ t(item.labelKey) }}
            </ElRadio>
          </ElRadioGroup>
        </ElFormItem>
        <ElFormItem
          v-if="formModel.systemMessageEvent"
          :label="`${systemMessageName(formModel.systemMessageEvent)} · ${t('admin.compute.v7.translation.eventEnabled')}`"
        >
          <ElSwitch
            v-model="formModel.systemMessageEnabled"
            :disabled="!canModifyCurrentForm"
          />
          <p class="form-help">
            {{ t('admin.compute.v24Finance.systemTemplateHelp') }}
          </p>
        </ElFormItem>
        <ElFormItem
          :label="t('admin.translation.batch.chineseContent')"
          prop="tvalue"
        >
          <TelegramTemplateEditor
            v-if="formModel.prop === 2"
            v-model="formModel.tvalue"
            :disabled="!canModifyCurrentForm"
          />
          <TinymceMin
            v-else-if="formModel.prop === 1"
            v-model:bind-html="formModel.tvalue"
            :disabled="!canModifyCurrentForm"
            :height="300"
          />
          <ElInput
            v-else
            v-model="formModel.tvalue"
            :disabled="!canModifyCurrentForm"
            :placeholder="
              t('admin.translation.batch.chineseContentPlaceholder')
            "
            :rows="3"
            type="textarea"
          />
        </ElFormItem>
        <ElFormItem v-if="canModifyCurrentForm">
          <ElButton
            :loading="translateLoading"
            type="primary"
            @click="handleTranslate(['en'])"
          >
            {{ t('admin.translation.v32.translateEnglish') }}
          </ElButton>
          <ElButton
            :loading="translateLoading"
            type="primary"
            @click="handleTranslateAll"
          >
            {{ t('admin.translation.batch.translateAll') }}
          </ElButton>
          <p class="form-help">{{ t('admin.translation.v32.previewHelp') }}</p>
        </ElFormItem>
        <ElFormItem
          v-if="targetLanguages.length > 0 && formModel.prop > 0"
          :label="t('admin.translation.batch.targetLanguages')"
          required
        >
          <ElTabs
            v-model="activeRichLanguage"
            class="system-translation-rich-tabs"
          >
            <ElTabPane
              v-for="lang in targetLanguages"
              :key="lang.value"
              lazy
              :label="languageLabel(lang)"
              :name="lang.value"
            >
              <TelegramTemplateEditor
                v-if="formModel.prop === 2"
                v-model="formModel.translations[lang.value]"
                :disabled="!canModifyCurrentForm"
              />
              <TinymceMin
                v-else
                v-model:bind-html="formModel.translations[lang.value]"
                :disabled="!canModifyCurrentForm"
                :height="280"
              />
            </ElTabPane>
          </ElTabs>
        </ElFormItem>
        <div
          v-else-if="targetLanguages.length > 0"
          class="system-translation-lang-grid"
        >
          <ElFormItem
            v-for="lang in targetLanguages"
            :key="lang.value"
            :label="languageLabel(lang)"
            :required="lang.value === 'en'"
          >
            <ElInput
              v-model="formModel.translations[lang.value]"
              clearable
              :disabled="!canModifyCurrentForm"
              :placeholder="lang.value"
              :rows="2"
              type="textarea"
            />
          </ElFormItem>
        </div>
        <div v-else class="system-translation-empty">
          {{ t('admin.translation.batch.noTargetLanguages') }}
        </div>
      </ElForm>

      <template #footer>
        <ElButton @click="formVisible = false">
          {{
            t(
              canModifyCurrentForm
                ? 'admin.common.cancel'
                : 'admin.common.close',
            )
          }}
        </ElButton>
        <ElButton
          v-if="canModifyCurrentForm"
          :loading="submitting"
          :disabled="translateLoading || languageLoading"
          type="primary"
          @click="submitForm"
        >
          {{ t('admin.common.save') }}
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>
