<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDatePicker,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElPagination,
  ElSelect,
  ElTableColumn,
  ElTag,
} from 'element-plus';
import { ElTable } from '#/adapter/table';
import type { ComputeRecord, PageResource } from '#/api/compute';
import { fetchComputeJobs, runComputeJob, saveComputeJob } from '#/api/compute';
import ComputeForm from '#/components/business/BusinessForm.vue';
import CronScheduleEditor from '#/components/CronScheduleEditor.vue';
import { P, useCompute } from '#/components/business/shared';
import { usePaged } from '#/components/business/usePaged';
import { useFilterRecovery } from '#/components/business/useFilterRecovery';
import { useDictionaries } from '#/components/business/dictionaries';
import BusinessTableToolbar from '#/components/business/BusinessTableToolbar.vue';
import { useTableColumns } from '#/components/business/useTableColumns';
const props = withDefaults(defineProps<{ section?: 'jobs' | 'logs' }>(), {
  section: 'jobs',
});
const { c, can, enumLabel, date } = useCompute();
const { dictionaries, loadDictionaries } = useDictionaries();
const jobs = ref<ComputeRecord[]>([]),
  jobLoading = ref(false),
  jobFailed = ref(false),
  saving = ref(false),
  editOpen = ref(false),
  logOpen = ref(false);
const runningId = ref<number | null>(null),
  selectedJob = ref<number | undefined>();
const selected = ref<ComputeRecord>({});
useFilterRecovery(`scheduler:${props.section}`, { selectedJob });
const form = reactive<ComputeRecord>({ enabled: true, cronExpression: '' });
const formRef = ref<InstanceType<typeof ComputeForm>>();
const canWrite = computed(
  () => dictionaries.value.scope.global && can(P.jobWrite),
);
const resource = ref<PageResource>('job-runs');
const {
  rows,
  query,
  pager,
  dates,
  loading,
  failed,
  load,
  search,
  reset,
  resize,
  exportPage,
} = usePaged(
  resource,
  () =>
    dictionaries.value.scope.global &&
    props.section === 'logs' &&
    can(P.jobRead, P.jobWrite),
  () => ({ jobId: selectedJob.value }),
);
const fields = [
  { key: 'enabled', kind: 'boolean' as const, hint: 'jobEnabled' },
];
const jobColumns = [
  'jobCode',
  'name',
  'cronExpression',
  'nextRunAt',
  'lastRunAt',
];
const logColumns = [
  'jobCode',
  'status',
  'processedCount',
  'createdAt',
  'startedAt',
  'finishedAt',
];
const jobKeyword = ref(''),
  appliedJobKeyword = ref('');
const filteredJobs = computed(() =>
  jobs.value.filter(
    (row) =>
      !appliedJobKeyword.value ||
      [row.name, row.jobCode, row.cronExpression]
        .join(' ')
        .toLowerCase()
        .includes(appliedJobKeyword.value.toLowerCase()),
  ),
);
const columnSpecs = computed(() =>
  [
    'id',
    ...(props.section === 'jobs' ? [...jobColumns, 'enabled'] : logColumns),
  ].map((key) => ({
    key,
    label: c(`field.${key}`),
    exportValue: (row: ComputeRecord) =>
      key.endsWith('At')
        ? date(row[key])
        : key === 'enabled'
          ? c(row.enabled ? 'common.enabled' : 'common.disabled')
          : enumLabel(row[key]),
  })),
);
const storageKey = computed(() => `system:scheduler:${props.section}`);
const { selectedColumns, visibleColumns } = useTableColumns(
  storageKey,
  columnSpecs,
);
async function resetJobs() {
  jobKeyword.value = '';
  appliedJobKeyword.value = '';
  await loadJobs();
}
async function loadJobs() {
  if (!dictionaries.value.scope.global || !can(P.jobRead, P.jobWrite)) return;
  jobLoading.value = true;
  jobFailed.value = false;
  try {
    jobs.value = await fetchComputeJobs();
  } catch {
    jobFailed.value = true;
  } finally {
    jobLoading.value = false;
  }
}
function openEdit(row: ComputeRecord) {
  selected.value = row;
  Object.assign(form, {
    enabled: Boolean(row.enabled),
    cronExpression: row.cronExpression,
  });
  editOpen.value = true;
}
async function save() {
  if (!canWrite.value || !(await formRef.value?.validate())) return;
  if (String(form.cronExpression).trim().split(/\s+/).length !== 6)
    return void ElMessage.warning(c('validation.cron'));
  saving.value = true;
  try {
    await saveComputeJob(Number(selected.value.id), {
      enabled: Boolean(form.enabled),
      cronExpression: String(form.cronExpression).trim(),
    });
    ElMessage.success(c('common.saved'));
    editOpen.value = false;
    await loadJobs();
  } finally {
    saving.value = false;
  }
}
async function run(row: ComputeRecord) {
  if (!canWrite.value || runningId.value !== null) return;
  try {
    await ElMessageBox.confirm(c('hint.runConfirm'), c('common.run'), {
      type: 'warning',
      confirmButtonText: c('common.run'),
      cancelButtonText: c('common.cancel'),
    });
  } catch {
    return;
  }
  runningId.value = Number(row.id);
  try {
    const result = await runComputeJob(Number(row.id));
    ElMessage.success(
      `${c('common.taskSubmitted')} ${result.id ?? result.taskId ?? ''}`,
    );
    selectedJob.value = Number(row.id);
    await Promise.all([loadJobs(), search()]);
  } finally {
    runningId.value = null;
  }
}
async function resetLogs() {
  selectedJob.value = undefined;
  await reset();
}
onMounted(async () => {
  if (!can(P.jobRead, P.jobWrite)) return;
  await loadDictionaries();
  await Promise.all([loadJobs(), load()]);
});
watch(
  () => props.section,
  () => {
    selectedJob.value = undefined;
    void reset();
  },
);
</script>
<template>
  <Page>
    <div class="compute-page">
      <ElAlert
        v-if="!dictionaries.scope.global"
        :title="c('hint.globalConfiguration')"
        type="info"
        :closable="false"
      />
      <ElAlert
        v-if="section === 'jobs'"
        :title="c('hint.jobs')"
        type="info"
        :closable="false"
      />
      <ElCard v-if="section === 'jobs'" shadow="never">
        <h2 class="compute-section-title">{{ c('resource.jobs') }}</h2>
        <div class="compute-toolbar">
          <ElInput
            v-model="jobKeyword"
            class="compute-filter"
            clearable
            :placeholder="c('common.keyword')"
            @keyup.enter="appliedJobKeyword = jobKeyword.trim()"
          />
          <ElButton
            type="primary"
            :loading="jobLoading"
            @click="appliedJobKeyword = jobKeyword.trim()"
          >
            {{ c('common.search') }}
          </ElButton>
          <ElButton @click="resetJobs">{{ c('common.reset') }}</ElButton>
        </div>
      </ElCard>
      <ElCard v-if="section === 'jobs'" shadow="never">
        <BusinessTableToolbar
          v-model:columns="selectedColumns"
          :storage-key="storageKey"
          :column-options="columnSpecs"
          :rows="filteredJobs"
          :loading="jobLoading"
        />
        <ElAlert
          v-if="jobFailed"
          type="error"
          :closable="false"
          :title="c('common.loadFailed')"
          class="mb-4"
        />
        <ElTable
          v-loading="jobLoading"
          :data="filteredJobs"
          stripe
          :empty-text="c('common.empty')"
        >
          <ElTableColumn
            v-for="key in visibleColumns.map((column) => column.key)"
            :key="key"
            :label="c(`field.${key}`)"
            :min-width="key.endsWith('At') ? 180 : 160"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              {{
                key.endsWith('At')
                  ? date(row[key])
                  : key === 'enabled'
                    ? c(row.enabled ? 'common.enabled' : 'common.disabled')
                    : row[key]
              }}
            </template>
          </ElTableColumn>
          <ElTableColumn
            v-if="canWrite"
            fixed="right"
            :label="c('common.actions')"
            width="185"
          >
            <template #default="{ row }">
              <ElButton link type="primary" @click="openEdit(row)">
                {{ c('common.edit') }}
              </ElButton>
              <ElButton
                link
                type="warning"
                :loading="runningId === row.id"
                :disabled="runningId !== null"
                @click="run(row)"
              >
                {{ c('common.run') }}
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
      </ElCard>
      <ElCard v-if="section === 'logs'" shadow="never">
        <h2 class="compute-section-title">{{ c('resource.job-runs') }}</h2>
        <div class="compute-toolbar">
          <ElDatePicker
            v-model="dates"
            type="datetimerange"
            class="compute-range"
            :start-placeholder="c('common.rangeStart')"
            :end-placeholder="c('field.endsAt')"
            value-format="YYYY-MM-DDTHH:mm:ss"
          />
          <ElSelect
            v-model="selectedJob"
            class="compute-filter"
            clearable
            :placeholder="c('field.jobId')"
          >
            <ElOption
              v-for="job in jobs"
              :key="job.id"
              :label="job.name"
              :value="Number(job.id)"
            />
          </ElSelect>
          <ElSelect
            v-model="query.status"
            class="compute-filter"
            clearable
            :placeholder="c('field.status')"
          >
            <ElOption
              v-for="status in ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED']"
              :key="status"
              :value="status"
              :label="enumLabel(status)"
            />
          </ElSelect>
          <ElButton type="primary" :loading="loading" @click="search">
            {{ c('common.search') }}
          </ElButton>
          <ElButton @click="resetLogs">{{ c('common.reset') }}</ElButton>
        </div>
      </ElCard>
      <ElCard v-if="section === 'logs'" shadow="never">
        <BusinessTableToolbar
          v-model:columns="selectedColumns"
          :storage-key="storageKey"
          :column-options="columnSpecs"
          :rows="rows"
          :loading="loading"
          :fetch-page="exportPage"
        />
        <ElAlert
          v-if="failed"
          type="error"
          :closable="false"
          :title="c('common.loadFailed')"
          class="mb-4"
        />
        <ElTable
          v-loading="loading"
          :data="rows"
          stripe
          :empty-text="c('common.empty')"
        >
          <ElTableColumn
            v-for="key in visibleColumns.map((column) => column.key)"
            :key="key"
            :label="c(`field.${key}`)"
            :min-width="key.endsWith('At') ? 180 : 140"
          >
            <template #default="{ row }">
              <ElTag
                v-if="key === 'status'"
                :type="
                  row.status === 'FAILED'
                    ? 'danger'
                    : row.status === 'SUCCEEDED'
                      ? 'success'
                      : 'warning'
                "
              >
                {{ enumLabel(row.status) }}
              </ElTag>
              <span v-else>
                {{ key.endsWith('At') ? date(row[key]) : row[key] }}
              </span>
            </template>
          </ElTableColumn>
          <ElTableColumn :label="c('common.actions')" width="100">
            <template #default="{ row }">
              <ElButton
                link
                type="primary"
                @click="
                  selected = row;
                  logOpen = true;
                "
              >
                {{ c('common.details') }}
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
        <div class="compute-pagination">
          <ElPagination
            v-model:current-page="pager.page"
            v-model:page-size="pager.size"
            :total="pager.total"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            @current-change="load()"
            @size-change="resize"
          />
        </div>
      </ElCard>
      <ElDialog
        v-model="editOpen"
        width="660px"
        class="compute-dialog"
        :title="c('common.edit')"
        :close-on-click-modal="false"
      >
        <ElAlert
          :title="String(selected.name ?? '')"
          :closable="false"
          class="mb-5"
        />
        <ComputeForm
          ref="formRef"
          :model="form"
          :fields="fields"
          :disabled="saving"
        />
        <CronScheduleEditor v-model="form.cronExpression" :disabled="saving" />
        <template #footer>
          <ElButton :disabled="saving" @click="editOpen = false">
            {{ c('common.cancel') }}
          </ElButton>
          <ElButton type="primary" :loading="saving" @click="save">
            {{ c('common.save') }}
          </ElButton>
        </template>
      </ElDialog>
      <ElDialog
        v-model="logOpen"
        width="650px"
        class="compute-dialog"
        :title="c('common.details')"
      >
        <ElDescriptions :column="1" border>
          <ElDescriptionsItem
            v-for="key in ['id', ...logColumns, 'errorKey']"
            :key="key"
            :label="c(`field.${key}`)"
          >
            <span class="compute-break">
              {{
                key.endsWith('At')
                  ? date(selected[key])
                  : enumLabel(selected[key])
              }}
            </span>
          </ElDescriptionsItem>
        </ElDescriptions>
      </ElDialog>
    </div>
  </Page>
</template>
