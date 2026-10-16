<script lang="ts" setup>
import BusinessTableToolbar from '#/components/business/BusinessTableToolbar.vue';
import {
  useTableColumns,
  type TableColumnSpec,
} from '#/components/business/useTableColumns';
import { usePageSelection } from '#/components/business/usePageSelection';
import type { FormInstance, FormRules } from 'element-plus';

import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { useI18n } from '@vben/locales';
import { useUserStore } from '@vben/stores';

import {
  ElButton,
  ElCard,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElProgress,
  ElSelect,
  ElTableColumn,
  ElTag,
} from 'element-plus';
import { ElTable } from '#/adapter/table';

import {
  createTranslationTemplate,
  deleteTranslationTemplate,
  fetchTranslationCompletionTask,
  fetchTranslationTemplateCompletion,
  fetchTranslationTemplates,
  fillMissingTemplateTranslations,
  updateTranslationTemplate,
} from '#/api/system';
import { refreshAdminLocale } from '#/locales';
import { permissionCodes, SYSTEM_PERMISSIONS } from '#/constants/permissions';

import type {
  SystemTranslationCompletion,
  SystemTranslationCompletionTask,
  SystemTranslationCompletionTaskStatus,
} from '#/api/system';

const { t } = useI18n();
const userStore = useUserStore();

const tableData = ref<Array<Record<string, any>>>([]);
const tableLoading = ref(false);
const overviewLoading = ref(false);
const fillMissingLoading = ref(false);
const formVisible = ref(false);
const submitting = ref(false);
const formMode = ref<'create' | 'edit'>('create');
const formRef = ref<FormInstance>();
const completionStats = ref<SystemTranslationCompletion>();
const completionTask = ref<SystemTranslationCompletionTask>();
const taskRefreshLoading = ref(false);
const taskStatusError = ref('');

const TASK_STORAGE_KEY_PREFIX = 'exp:translation-completion-task-id';
const TASK_POLL_INTERVAL = 2000;
const TASK_POLL_TIMEOUT = 2 * 60 * 60_000;
let taskPollFailures = 0;
let taskPollDisposed = false;
let taskPollInFlight = false;
let taskPollStartedAt = 0;
let taskPollTimer: number | undefined;
let terminalTaskId: number | undefined;

const taskActive = computed(() =>
  ['PENDING', 'RUNNING'].includes(completionTask.value?.status || ''),
);
const taskProgress = computed(() => {
  if (completionTask.value?.status === 'SUCCEEDED') return 100;
  const value = Number(completionTask.value?.progressPercent ?? 0);
  return Math.min(100, Math.max(0, Number.isFinite(value) ? value : 0));
});
const taskProgressStatus = computed(() => {
  if (completionTask.value?.status === 'FAILED') return 'exception';
  if (completionTask.value?.status === 'SUCCEEDED') return 'success';
  return undefined;
});
const taskErrorText = computed(() => {
  const message = completionTask.value?.errorMessage?.trim();
  if (message) return message;
  const errorCode = completionTask.value?.errorCode?.trim();
  return errorCode || t('admin.translation.completion.taskNoErrorDetail');
});

const completionTemplates = computed(() =>
  tableData.value.filter((item) => {
    const id = Number(item.id);
    return id > 0 && item.completionAllowed !== false;
  }),
);
const rememberedCompletion = usePageSelection(
  'translation-completion-template',
  () =>
    tableData.value.length
      ? ['', ...completionTemplates.value.map((item) => String(item.id))]
      : [],
  '',
  false,
);
const completionType = computed<number | undefined>({
  get: () =>
    rememberedCompletion.value ? Number(rememberedCompletion.value) : undefined,
  set: (value) => {
    rememberedCompletion.value = value === undefined ? '' : String(value);
  },
});

const formModel = reactive({
  id: undefined as number | undefined,
  tkey: '',
  tvalue: '',
});

const formRules = computed<FormRules>(() => ({
  tkey: [
    {
      message: t('admin.translation.template.nameRequired'),
      required: true,
      trigger: 'blur',
    },
  ],
  tvalue: [
    {
      message: t('admin.translation.template.descriptionRequired'),
      required: true,
      trigger: 'blur',
    },
  ],
}));

async function loadData() {
  tableLoading.value = true;

  try {
    tableData.value = await fetchTranslationTemplates();
    if (
      completionType.value !== undefined &&
      !completionTemplates.value.some(
        (item) => Number(item.id) === completionType.value,
      )
    ) {
      resetCompletion();
    }
  } finally {
    tableLoading.value = false;
  }
}

function resetForm(row?: Record<string, any>) {
  formModel.id = row?.id;
  formModel.tkey = row?.tkey || '';
  formModel.tvalue = row?.tvalue || '';
}

function canEdit(row: Record<string, any>) {
  return row.editable ?? Number(row.id || 0) > 7;
}

function resetCompletion() {
  completionType.value = undefined;
  completionStats.value = undefined;
}

function clearCompletionStats() {
  completionStats.value = undefined;
}

async function loadCompletionOverview() {
  if (completionType.value === undefined) {
    completionStats.value = undefined;
    return;
  }

  const type = completionType.value;
  completionStats.value = undefined;
  overviewLoading.value = true;
  try {
    const result = await fetchTranslationTemplateCompletion(type);
    if (completionType.value === type) {
      completionStats.value = result;
    }
  } finally {
    overviewLoading.value = false;
  }
}

function taskStatusKey(status: SystemTranslationCompletionTaskStatus) {
  const keys: Record<SystemTranslationCompletionTaskStatus, string> = {
    FAILED: 'admin.translation.completion.taskFailed',
    PENDING: 'admin.translation.completion.taskPending',
    RUNNING: 'admin.translation.completion.taskRunning',
    SUCCEEDED: 'admin.translation.completion.taskSucceeded',
  };
  return keys[status];
}

function clearTaskPollTimer() {
  if (taskPollTimer !== undefined) window.clearTimeout(taskPollTimer);
  taskPollTimer = undefined;
}

function scheduleTaskPoll() {
  clearTaskPollTimer();
  if (taskPollDisposed) return;
  taskPollTimer = window.setTimeout(() => {
    void pollCompletionTask();
  }, TASK_POLL_INTERVAL);
}

function saveActiveTask(taskId: number) {
  localStorage.setItem(taskStorageKey(), String(taskId));
}

function clearActiveTask() {
  localStorage.removeItem(taskStorageKey());
  clearTaskPollTimer();
}

function taskStorageKey() {
  const user = userStore.userInfo as Record<string, any> | null;
  const identity = user?.uid ?? user?.userId ?? user?.username ?? 'anonymous';
  return `${TASK_STORAGE_KEY_PREFIX}:${identity}`;
}

async function finishCompletionTask(task: SystemTranslationCompletionTask) {
  clearActiveTask();
  taskStatusError.value = '';

  if (terminalTaskId === task.taskId) return;
  terminalTaskId = task.taskId;

  if (task.status === 'SUCCEEDED') {
    const refreshTasks: Array<Promise<unknown>> = [];
    if (completionType.value === task.templateType) {
      refreshTasks.push(loadCompletionOverview());
    }
    if (task.templateType === 5) refreshTasks.push(refreshAdminLocale());
    await Promise.allSettled(refreshTasks);
    ElMessage.success(
      t('admin.translation.completion.taskSucceededMessage', {
        count: task.inserted ?? 0,
      }),
    );
    return;
  }

  ElMessage.error(
    t('admin.translation.completion.taskFailedMessage', {
      message: taskErrorText.value,
    }),
  );
}

async function pollCompletionTask(manual = false) {
  const taskId = Number(completionTask.value?.taskId);
  if (!Number.isInteger(taskId) || taskId <= 0 || taskPollInFlight) return;

  if (manual) {
    taskPollFailures = 0;
    taskPollStartedAt = Date.now();
  }

  if (
    !manual &&
    taskPollStartedAt > 0 &&
    Date.now() - taskPollStartedAt >= TASK_POLL_TIMEOUT
  ) {
    clearTaskPollTimer();
    taskStatusError.value = t('admin.translation.completion.taskTimeout');
    ElMessage.warning(taskStatusError.value);
    return;
  }

  taskPollInFlight = true;
  if (manual) taskRefreshLoading.value = true;
  try {
    const task = await fetchTranslationCompletionTask(taskId);
    completionTask.value = task;
    taskPollFailures = 0;
    taskStatusError.value = '';

    if (task.status === 'SUCCEEDED' || task.status === 'FAILED') {
      await finishCompletionTask(task);
    } else {
      scheduleTaskPoll();
    }
  } catch {
    taskPollFailures += 1;
    if (manual || taskPollFailures >= 3) {
      clearTaskPollTimer();
      taskStatusError.value = t('admin.translation.completion.taskPollFailed');
      ElMessage.error(taskStatusError.value);
    } else {
      scheduleTaskPoll();
    }
  } finally {
    taskPollInFlight = false;
    taskRefreshLoading.value = false;
  }
}

function startTaskPolling(task: SystemTranslationCompletionTask) {
  completionTask.value = task;
  terminalTaskId = undefined;
  taskPollFailures = 0;
  taskPollStartedAt = Date.now();
  taskStatusError.value = '';
  saveActiveTask(task.taskId);

  if (task.status === 'SUCCEEDED' || task.status === 'FAILED') {
    void finishCompletionTask(task);
  } else {
    scheduleTaskPoll();
  }
}

async function restoreCompletionTask() {
  const taskId = Number(localStorage.getItem(taskStorageKey()));
  if (!Number.isInteger(taskId) || taskId <= 0) return;

  completionTask.value = {
    inserted: 0,
    missingAfter: 0,
    missingBefore: 0,
    processedKeys: 0,
    progressPercent: 0,
    status: 'PENDING',
    taskId,
    templateType: 0,
    totalKeys: 0,
    translatedKeys: 0,
  };
  taskPollStartedAt = Date.now();
  await pollCompletionTask();
}

async function fillMissingLanguages() {
  if (completionType.value === undefined) {
    ElMessage.warning(t('admin.translation.template.selectFirst'));
    return;
  }

  const type = completionType.value;
  fillMissingLoading.value = true;
  try {
    const stats = await fetchTranslationTemplateCompletion(type);
    completionStats.value = stats;
    if (stats.missingCount <= 0) {
      ElMessage.success(t('admin.translation.template.complete'));
      return;
    }

    await ElMessageBox.confirm(
      t('admin.translation.template.fillConfirm', {
        incompleteKeyCount: stats.incompleteKeyCount,
        missingCount: stats.missingCount,
      }),
      t('admin.translation.template.fillTitle'),
      {
        cancelButtonText: t('admin.common.cancel'),
        confirmButtonText: t('admin.translation.template.startFill'),
        type: 'warning',
      },
    );

    try {
      const task = await fillMissingTemplateTranslations(type);
      startTaskPolling(task);
      ElMessage.success(t('admin.translation.completion.taskSubmitted'));
    } catch (error: any) {
      // 重复提交时后端会返回当前活动任务 ID，继续追踪即可。
      const activeTaskId = Number(
        error?.data?.taskId ?? error?.response?.data?.data?.taskId,
      );
      if (!Number.isInteger(activeTaskId) || activeTaskId <= 0) throw error;

      completionTask.value = {
        inserted: 0,
        missingAfter: 0,
        missingBefore: stats.missingCount,
        processedKeys: 0,
        progressPercent: 0,
        status: 'PENDING',
        taskId: activeTaskId,
        templateName: stats.templateName,
        templateType: type,
        totalKeys: stats.keyCount,
        translatedKeys: 0,
      };
      taskPollStartedAt = Date.now();
      saveActiveTask(activeTaskId);
      await pollCompletionTask(true);
    }
  } finally {
    fillMissingLoading.value = false;
  }
}

function openCreate() {
  formMode.value = 'create';
  resetForm();
  formVisible.value = true;
}

function openEdit(row: Record<string, any>) {
  formMode.value = 'edit';
  resetForm(row);
  formVisible.value = true;
}

async function submitForm() {
  await formRef.value?.validate();
  submitting.value = true;

  try {
    if (formMode.value === 'create') {
      await createTranslationTemplate({
        tkey: formModel.tkey,
        tvalue: formModel.tvalue,
      });
      ElMessage.success(t('admin.translation.template.created'));
    } else {
      await updateTranslationTemplate({
        id: formModel.id,
        tkey: formModel.tkey,
        tvalue: formModel.tvalue,
      });
      ElMessage.success(t('admin.translation.template.saved'));
    }

    formVisible.value = false;
    await loadData();
  } finally {
    submitting.value = false;
  }
}

async function removeTemplate(row: Record<string, any>) {
  await ElMessageBox.confirm(
    t('admin.common.confirmDeleteIrreversible'),
    t('admin.translation.template.deleteTitle'),
    {
      cancelButtonText: t('admin.common.cancel'),
      confirmButtonClass: 'el-button--danger',
      confirmButtonText: t('admin.common.delete'),
      type: 'warning',
    },
  );

  await deleteTranslationTemplate(Number(row.id));
  ElMessage.success(t('admin.translation.template.deleted'));
  await loadData();
}

onMounted(async () => {
  taskPollDisposed = false;
  await loadData();
  await restoreCompletionTask();
});

onBeforeUnmount(() => {
  taskPollDisposed = true;
  clearTaskPollTimer();
});

const columnOptions = computed<TableColumnSpec[]>(() => [
  {
    key: 'tkey',
    label: t('admin.translation.template.name'),
    exportValue: (row: Record<string, any>) => row.tkey,
  },
  {
    key: 'tvalue',
    label: t('admin.translation.template.description'),
    exportValue: (row: Record<string, any>) => row.tvalue,
  },
  {
    key: 'column3',
    label: t('admin.translation.template.type'),
    exportValue: (row: Record<string, any>) => row.column3,
  },
]);
const { selectedColumns, visibleColumns } = useTableColumns(
  'translates-components-TemplatePanel',
  columnOptions,
);
</script>

<template>
  <div class="system-translation-panel">
    <ElCard class="exp-card exp-search-card mb-5" shadow="never">
      <div class="exp-toolbar">
        <div class="exp-filter-row">
          <ElSelect
            v-model="completionType"
            class="exp-filter"
            clearable
            filterable
            :loading="tableLoading"
            :placeholder="t('admin.translation.template.selectPlaceholder')"
            @change="clearCompletionStats"
          >
            <ElOption
              v-for="item in completionTemplates"
              :key="item.id"
              :label="item.tkey"
              :value="Number(item.id)"
            />
          </ElSelect>
          <ElButton
            :loading="overviewLoading"
            type="primary"
            @click="loadCompletionOverview"
          >
            {{ t('admin.translation.template.checkCompleteness') }}
          </ElButton>
          <ElButton @click="resetCompletion">
            {{ t('admin.common.reset') }}
          </ElButton>
          <ElButton
            v-access:code="
              permissionCodes(
                SYSTEM_PERMISSIONS.translation.template.completion,
              )
            "
            :disabled="
              !completionStats ||
              completionStats.missingCount === 0 ||
              taskActive
            "
            :loading="fillMissingLoading"
            type="success"
            @click="fillMissingLanguages"
          >
            {{ t('admin.translation.template.fillMissing') }}
          </ElButton>
          <ElButton
            v-access:code="
              permissionCodes(
                SYSTEM_PERMISSIONS.translation.template.create,
              )
            "
            type="primary"
            @click="openCreate"
          >
            {{ t('admin.translation.template.add') }}
          </ElButton>
          <BusinessTableToolbar
            v-model:columns="selectedColumns"
            :column-options="columnOptions"
            :rows="tableData"
            :loading="tableLoading"
            storage-key="translates-components-TemplatePanel"
          />
        </div>
      </div>
      <div v-if="completionStats" class="translation-completion-summary">
        <div>
          <span>{{ t('admin.translation.template.keyCount') }}</span>
          <strong>{{ completionStats.keyCount }}</strong>
        </div>
        <div>
          <span>{{ t('admin.translation.template.enabledLanguages') }}</span>
          <strong>{{ completionStats.enabledLanguageCount }}</strong>
        </div>
        <div>
          <span>{{ t('admin.translation.template.completeKeys') }}</span>
          <strong class="is-success">{{
            completionStats.completeKeyCount
          }}</strong>
        </div>
        <div>
          <span>{{ t('admin.translation.template.missingTranslations') }}</span>
          <strong :class="{ 'is-danger': completionStats.missingCount > 0 }">
            {{ completionStats.missingCount }}
          </strong>
          <small>
            {{
              t('admin.translation.template.countSummary', {
                existing: completionStats.existingCount,
                expected: completionStats.expectedCount,
              })
            }}
          </small>
        </div>
      </div>
      <div v-if="completionTask" class="translation-task-status">
        <div class="translation-task-header">
          <div>
            <strong>
              {{
                completionTask.templateName ||
                t('admin.translation.completion.taskProgress')
              }}
            </strong>
            <ElTag
              :type="
                completionTask.status === 'FAILED'
                  ? 'danger'
                  : completionTask.status === 'SUCCEEDED'
                    ? 'success'
                    : 'primary'
              "
            >
              {{ t(taskStatusKey(completionTask.status)) }}
            </ElTag>
          </div>
          <ElButton
            :loading="taskRefreshLoading"
            size="small"
            @click="pollCompletionTask(true)"
          >
            {{ t('admin.translation.completion.taskRefresh') }}
          </ElButton>
        </div>
        <ElProgress
          :percentage="taskProgress"
          :status="taskProgressStatus"
          :stroke-width="10"
        />
        <small>
          {{
            t('admin.translation.completion.taskProcessed', {
              processed: completionTask.processedKeys,
              total: completionTask.totalKeys,
            })
          }}
        </small>
        <p v-if="completionTask.status === 'FAILED'" class="is-danger">
          {{ taskErrorText }}
        </p>
        <p v-else-if="taskStatusError" class="is-danger">
          {{ taskStatusError }}
        </p>
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
            :label="t('admin.translation.template.name')"
            min-width="220"
            prop="tkey"
          />
          <ElTableColumn
            v-if="column.key === 'tvalue'"
            :label="t('admin.translation.template.description')"
            min-width="260"
            prop="tvalue"
          />
          <ElTableColumn
            v-if="column.key === 'column3'"
            :label="t('admin.translation.template.type')"
            width="120"
          >
            <template #default="{ row }">
              <ElTag :type="canEdit(row) ? 'primary' : 'info'">
                {{
                  t(
                    canEdit(row)
                      ? 'admin.translation.template.custom'
                      : 'admin.translation.template.system',
                  )
                }}
              </ElTag>
            </template>
          </ElTableColumn>
        </template>

        <ElTableColumn
          fixed="right"
          :label="t('admin.common.operation')"
          width="170"
        >
          <template #default="{ row }">
            <ElButton
              v-access:code="
                permissionCodes(
                  SYSTEM_PERMISSIONS.translation.template.update,
                )
              "
              :disabled="!canEdit(row)"
              link
              size="small"
              type="primary"
              @click="openEdit(row)"
            >
              {{ t('admin.common.edit') }}
            </ElButton>
            <ElButton
              v-access:code="
                permissionCodes(
                  SYSTEM_PERMISSIONS.translation.template.delete,
                )
              "
              :disabled="!canEdit(row)"
              link
              size="small"
              type="danger"
              @click="removeTemplate(row)"
            >
              {{ t('admin.common.delete') }}
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDialog
      v-model="formVisible"
      append-to-body
      destroy-on-close
      :title="
        t(
          formMode === 'create'
            ? 'admin.translation.template.add'
            : 'admin.translation.template.edit',
        )
      "
      width="560px"
    >
      <ElForm
        ref="formRef"
        class="exp-dialog-form"
        label-position="right"
        label-width="100px"
        :model="formModel"
        :rules="formRules"
      >
        <ElFormItem :label="t('admin.translation.template.name')" prop="tkey">
          <ElInput
            v-model="formModel.tkey"
            clearable
            :placeholder="t('admin.translation.template.namePlaceholder')"
          />
        </ElFormItem>
        <ElFormItem
          :label="t('admin.translation.template.descriptionLabel')"
          prop="tvalue"
        >
          <ElInput
            v-model="formModel.tvalue"
            :placeholder="
              t('admin.translation.template.descriptionPlaceholder')
            "
            :rows="3"
            type="textarea"
          />
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

<style scoped>
.translation-completion-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-top: 16px;
}

.translation-completion-summary > div {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 12px 14px;
  background: var(--exp-surface-muted);
  border: 1px solid var(--exp-border);
  border-radius: 9px;
}

.translation-completion-summary span,
.translation-completion-summary small {
  font-size: 12px;
  color: var(--exp-text-secondary);
}

.translation-completion-summary strong {
  font-size: 20px;
  color: var(--exp-text);
}

.translation-completion-summary .is-success {
  color: var(--el-color-success);
}

.translation-completion-summary .is-danger {
  color: var(--el-color-danger);
}

.translation-task-status {
  display: grid;
  gap: 10px;
  padding: 14px;
  margin-top: 12px;
  background: var(--exp-surface-muted);
  border: 1px solid var(--exp-border);
  border-radius: 9px;
}

.translation-task-header,
.translation-task-header > div {
  display: flex;
  flex-wrap: wrap;
  min-width: 0;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
}

.translation-task-status small {
  color: var(--exp-text-secondary);
}

.translation-task-status p {
  margin: 0;
  overflow-wrap: anywhere;
}

.translation-task-status .is-danger {
  color: var(--el-color-danger);
}

@media (max-width: 760px) {
  .translation-completion-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
