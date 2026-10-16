<script setup lang="ts">
import ReportCurrencyDetails from './ReportCurrencyDetails.vue';
import { reportValue } from './reportAmounts';
import { computed, onMounted, ref } from 'vue';
import Page from '#/components/business/BusinessListPage.vue';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDatePicker,
  ElInput,
  ElOption,
  ElPagination,
  ElSelect,
  ElSwitch,
  ElTableColumn,
} from 'element-plus';
import { ElTable } from '#/adapter/table';
import type { ComputeRecord, PageResource } from '#/api/compute';
import { P, useCompute } from '#/components/business/shared';
import { useFilterRecovery } from '#/components/business/useFilterRecovery';
import { usePaged } from '#/components/business/usePaged';
import { useDictionaries } from '#/components/business/dictionaries';
const { c, can } = useCompute();
const { dictionaries, loadDictionaries } = useDictionaries();
const supportedDepth = computed(() => Math.min(200, Math.max(1, Number(dictionaries.value?.teamSupportedDepth || 3))));
const relationDepth = ref<number | string>('ALL'),
  includeSelf = ref(true),
  parent = ref<number>();
useFilterRecovery('team-scope', { relationDepth, includeSelf, parent });
const {
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
  resize,
} = usePaged(
  computed<PageResource>(() => 'reports/team'),
  () => can(P.teamReportRead),
  () => ({
    relationDepth: relationDepth.value,
    includeSelf: includeSelf.value,
    parentMemberId: parent.value,
  }),
);
const columns = [
  'memberId',
  'username',
  'groupName',
  'memberCount',
  'newMembers',
  'available',
  'frozen',
  'totalDeposits',
  'depositCount',
  'depositMembers',
  'firstDepositAmount',
  'firstDepositMembers',
  'withdrawalAmount',
  'withdrawalFee',
  'withdrawalNetAmount',
  'withdrawalCount',
  'withdrawalMembers',
  'difference',
];
const columnSpecs = computed(() =>
  columns.map((key) => ({
    key,
    label: key === 'memberId' ? 'UID' : c(key === 'withdrawalAmount' ? 'v44Finance.withdrawalGross'
      : key === 'withdrawalNetAmount' ? 'v44Finance.withdrawalNet' : 'field.' + key),
    required: ['memberId', 'username'].includes(key),
    fixed: ['memberId', 'username'].includes(key)
      ? ('left' as const)
      : undefined,
    exportValue: (row: ComputeRecord) => value(row, key),
  })),
);
function value(row: ComputeRecord, key: string) { return key === 'groupName' && !Number(row.groupId) ? c('v44Finance.ungrouped') : reportValue(row, key); }
const visibleColumns = columnSpecs;
async function resetAll() {
  relationDepth.value = 'ALL';
  includeSelf.value = true;
  parent.value = undefined;
  await reset();
}
async function searchReport() {
  parent.value = undefined;
  await search();
}
async function drill(id?: number) {
  parent.value = id;
  query.memberId = '';
  query.username = '';
  await search();
}
onMounted(() => {
  if (can(P.teamReportRead)) void Promise.all([load(), loadDictionaries()]);
});
</script>
<template>
  <Page
    ><div class="compute-page">
      <ElAlert
          :title="c('v38Reports.teamHelp')"
          type="info"
          :closable="false"
          class="mb-4"
        />
      <ElCard shadow="never">
        <div class="compute-toolbar">
          <ElDatePicker
            v-model="dates"
            type="datetimerange"
            class="compute-range"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :start-placeholder="c('common.rangeStart')"
            :end-placeholder="c('field.endsAt')"
          />
          <ElInput
            v-model="query.username"
            clearable
            class="compute-filter"
            :placeholder="c('field.username')"
            @keyup.enter="searchReport"
          />
          <ElSelect
            v-model="relationDepth"
            class="compute-filter"
            filterable
            :placeholder="c('v5.cumulativeDepth')"
            ><ElOption value="ALL" :label="c('v7.allDepths')" /><ElOption
              v-for="depth in supportedDepth"
              :key="depth"
              :value="depth"
              :label="
                c('v5.cumulativeDepthOption') +
                ' ' +
                depth +
                ' ' +
                c('v5.depthSuffix')
              "
          /></ElSelect>
          <ElSwitch
            v-model="includeSelf"
            :active-text="c('field.includeSelf')"
          />
          <ElButton type="primary" :loading="loading" @click="searchReport">{{
            c('common.search')
          }}</ElButton
          ><ElButton @click="resetAll">{{ c('common.reset') }}</ElButton>
        </div></ElCard
      >
      <ElCard class="compute-list-card" shadow="never">
        <div class="compute-toolbar mb-4">
          <ElButton text @click="drill()">{{ c('v6.topMembers') }}</ElButton
          ><template
            v-for="ancestor in metadata.ancestors || []"
            :key="ancestor.id"
            ><span>/</span
            ><ElButton text @click="drill(Number(ancestor.id))">{{
              ancestor.username || '—'
            }}</ElButton></template
          >
        </div>
        <p class="compute-help">{{ c('v7.originalCurrencies') }}</p>
        <ElAlert
          v-if="metadata.truncated"
          :title="c('hint.reportTruncated')"
          type="warning"
          :closable="false"
          class="mb-4"
        /><ElAlert
          v-if="failed"
          :title="c('common.loadFailed')"
          type="error"
          :closable="false"
          class="mb-4"
        />
        <ElTable height="100%"
          v-loading="loading"
          :data="rows"
          stripe
          :empty-text="c('common.empty')"
          ><ElTableColumn type="expand"
            ><template #default="{ row }"
              ><ReportCurrencyDetails
                :rows="row.currencySummaries || []" team /></template></ElTableColumn
          ><ElTableColumn
            v-for="column in visibleColumns"
            :key="column.key"
            :prop="column.key"
            :label="column.label"
            :align="['memberId', 'username', 'groupName'].includes(column.key) ? 'left' : 'right'"
            :fixed="column.fixed"
            :width="
              column.key === 'memberId'
                ? 95
                : column.key === 'username'
                  ? 160
                  : undefined
            "
            min-width="145"
            show-overflow-tooltip
            ><template #default="{ row }">{{
              value(row, column.key)
            }}</template></ElTableColumn
          ><ElTableColumn :label="c('common.actions')" fixed="right" width="95"
            ><template #default="{ row }"
              ><ElButton
                v-if="row.hasChildren"
                link
                type="primary"
                @click="drill(Number(row.memberId || row.id))"
                >{{ c('common.viewChildren') }}</ElButton
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
          />
        </div>
      </ElCard></div
  ></Page>
</template>
