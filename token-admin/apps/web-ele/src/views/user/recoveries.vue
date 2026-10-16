<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import Page from '#/components/business/BusinessListPage.vue';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElCheckbox,
  ElDatePicker,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElInput,
  ElMessage,
  ElPagination,
  ElTableColumn,
} from 'element-plus';
import { ElTable } from '#/adapter/table';
import {
  issueComputeRecovery,
  type ComputeRecord,
  type PageResource,
} from '#/api/compute';
import { P, useCompute } from '#/components/business/shared';
import { usePaged } from '#/components/business/usePaged';
import BusinessTableToolbar from '#/components/business/BusinessTableToolbar.vue';
import { useTableColumns } from '#/components/business/useTableColumns';
const { c, can, enumLabel, date } = useCompute();
const allowed = () => can(P.recoveryRead, P.recoveryWrite);
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
  computed<PageResource>(() => 'recoveries'),
  allowed,
);
const columns = computed(() =>
  [
    'username',
    'method',
    'account',
    'status',
    'reviewerUsername',
    'createdAt',
  ].map((key) => ({
    key,
    label: c(
      key === 'reviewerUsername' ? 'v7.reviewedByUsername' : `field.${key}`,
    ),
    exportValue: (row: ComputeRecord) =>
      key.endsWith('At')
        ? date(row[key])
        : key === 'username' || key.endsWith('Username')
          ? row[key] || '—'
          : enumLabel(row[key]),
  })),
);
const { selectedColumns, visibleColumns } = useTableColumns(
  'user:recoveries',
  columns,
);
const dialog = ref(false),
  saving = ref(false),
  verified = ref(false),
  selected = ref<ComputeRecord>({});
const credential = ref<{ token: string; expiresAt: string }>();
function open(row: ComputeRecord) {
  selected.value = row;
  credential.value = undefined;
  verified.value = false;
  dialog.value = true;
}
async function issue() {
  if (!can(P.recoveryWrite) || !verified.value || credential.value || saving.value) return;
  saving.value = true;
  try {
    credential.value = await issueComputeRecovery(Number(selected.value.id));
    await load();
  } finally {
    saving.value = false;
  }
}
async function copy() {
  if (!credential.value) return;
  try {
    await navigator.clipboard.writeText(credential.value.token);
    ElMessage.success(c('common.copied'));
  } catch {
    ElMessage.warning(c('common.copyManually'));
  }
}
onMounted(load);
</script>
<template>
  <Page
    ><div class="compute-page">
      <ElAlert :title="c('hint.recovery')" type="warning" :closable="false" />
      <ElCard shadow="never"
        ><div class="compute-toolbar">
          <ElDatePicker
            v-model="dates"
            type="datetimerange"
            class="compute-range"
            :start-placeholder="c('common.rangeStart')"
            :end-placeholder="c('field.endsAt')"
            value-format="YYYY-MM-DDTHH:mm:ss"
          /><ElInput
            v-model="query.keyword"
            class="compute-filter"
            clearable
            :placeholder="c('field.account')"
            @keyup.enter="search"
          /><ElButton :loading="loading" type="primary" @click="search">{{
            c('common.search')
          }}</ElButton
          ><ElButton @click="reset">{{ c('common.reset') }}</ElButton>
        </div></ElCard
      >
      <ElCard class="compute-list-card" shadow="never"
        ><BusinessTableToolbar
          v-model:columns="selectedColumns"
          storage-key="user:recoveries"
          :column-options="columns"
          :rows="rows"
          :loading="loading"
          :fetch-page="exportPage" /><ElAlert
          v-if="failed"
          :title="c('common.loadFailed')"
          type="error"
          :closable="false" /><ElTable height="100%"
          v-loading="loading"
          :data="rows"
          stripe
          :empty-text="c('common.empty')"
          ><ElTableColumn
            v-for="column in visibleColumns"
            :key="column.key"
            :label="column.label"
            min-width="140"
            ><template #default="{ row }">{{
              column.exportValue?.(row)
            }}</template></ElTableColumn
          ><ElTableColumn :label="c('common.actions')" fixed="right" width="150"
            ><template #default="{ row }"
              ><ElButton
                v-if="can(P.recoveryWrite) && row.status === 'PENDING'"
                link
                type="warning"
                @click="open(row)"
                >{{ c('common.issueRecovery') }}</ElButton
              ></template
            ></ElTableColumn
          ></ElTable
        >
        <div class="compute-pagination">
          <ElPagination
            v-model:current-page="pager.page"
            v-model:page-size="pager.size"
            :total="pager.total"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            @current-change="load()"
            @size-change="resize"
          /></div
      ></ElCard>
      <ElDialog
        v-model="dialog"
        :title="c('common.issueRecovery')"
        width="min(650px, 96vw)"
        :close-on-click-modal="false"
        :close-on-press-escape="!saving"
        :show-close="!saving"
        @closed="credential = undefined"
        ><ElAlert
          :title="c('hint.recovery')"
          type="warning"
          :closable="false"
          class="mb-4"
        /><ElDescriptions :column="1" border
          ><ElDescriptionsItem :label="c('field.username')">{{
            selected.username || '—'
          }}</ElDescriptionsItem
          ><ElDescriptionsItem :label="c('field.account')">{{
            selected.account
          }}</ElDescriptionsItem></ElDescriptions
        ><template v-if="credential"
          ><ElAlert
            :title="c('hint.tokenOnce')"
            type="success"
            :closable="false"
            class="my-4"
          /><ElInput
            :model-value="credential.token"
            readonly
            type="textarea"
            :rows="3"
            :aria-label="c('field.token')"
          />
          <p class="compute-help">
            {{ c('field.expiresAt') }}: {{ date(credential.expiresAt) }}
          </p>
          <ElButton @click="copy">{{ c('common.copy') }}</ElButton></template
        ><ElCheckbox v-else v-model="verified" class="mt-4">{{
          c('common.identityVerified')
        }}</ElCheckbox
        ><template #footer
          ><ElButton :disabled="saving" @click="dialog = false">{{
            c('common.close')
          }}</ElButton
          ><ElButton
            v-if="!credential"
            :disabled="!verified"
            :loading="saving"
            type="primary"
            @click="issue"
            >{{ c('common.issueRecovery') }}</ElButton
          ></template
        ></ElDialog
      >
    </div></Page
  >
</template>
