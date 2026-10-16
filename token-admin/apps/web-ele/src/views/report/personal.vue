<script setup lang="ts">
import { computed, onMounted } from 'vue';
import Page from '#/components/business/BusinessListPage.vue';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDatePicker,
  ElDescriptions,
  ElDescriptionsItem,
  ElInput,
  ElPagination,
  ElTableColumn,
} from 'element-plus';
import { ElTable } from '#/adapter/table';
import type { PageResource } from '#/api/compute';
import BusinessSearchFields from '#/components/business/BusinessSearchFields.vue';
import { useDictionaries } from '#/components/business/dictionaries';
import { P, useCompute } from '#/components/business/shared';
import { usePaged } from '#/components/business/usePaged';
import AnalyticsChart from '#/views/dashboard/AnalyticsChart.vue';
import ReportCurrencyDetails from './ReportCurrencyDetails.vue';
import ReportInsights from './ReportInsights.vue';
import { reportValue } from './reportAmounts';
import { currencyOptionLabel } from '#/utils/currency';
const { c, can, date } = useCompute();
const { loadDictionaries, options } = useDictionaries();
const {
  rows,
  metadata,
  query,
  dates,
  pager,
  loading,
  failed,
  load,
  search,
  reset,
  resize,
} = usePaged(
  computed<PageResource>(() => 'reports/personal'),
  () => can(P.personalReportRead),
);
const fields = computed(() => [
  { key: 'groupId', options: options('groups') },
  { key: 'inviterName', labelKey: 'v5.directSuperior' },
  { key: 'topName', labelKey: 'v5.topAgent' },
  { key: 'teamUsername', labelKey: 'v6.teamUsername' },
]);
const columns = [
  'id',
  'username',
  'groupName',
  'directCount',
  'teamCount',
  'available',
  'frozen',
  'totalDeposits',
  'depositCount',
  'withdrawalAmount',
  'withdrawalFee',
  'withdrawalNetAmount',
  'withdrawalCount',
  'productCount',
  'productAmount',
  'totalIncome',
  'autoCount',
  'autoAmount',
  'autoIncome',
  'teamRewards',
  'activityRewards',
  'difference',
];
const label = (key: string) =>
  key === 'id'
    ? 'UID'
    : c(
        [
          'directCount',
          'teamCount',
          'autoCount',
          'autoAmount',
          'autoIncome',
        ].includes(key)
          ? 'v6.' + key
          : ['productCount', 'productAmount'].includes(key)
            ? 'v5.' + key
            : 'field.' + key,
      );
const series = computed(() => [
  { key: 'totalDeposits', name: c('field.totalDeposits'), color: '#10b981' },
  {
    key: 'totalWithdrawals',
    name: c('field.withdrawalNetAmount'),
    color: '#f59e0b',
  },
  { key: 'totalIncome', name: c('field.totalIncome'), color: '#3b82f6' },
  { key: 'autoIncome', name: c('v6.autoIncome'), color: '#8b5cf6' },
]);
onMounted(() => {
  if (can(P.personalReportRead)) void Promise.all([loadDictionaries(), load()]);
});
</script>
<template>
  <Page
    ><div class="compute-page">
        <ElAlert
          :title="c('v38Reports.personalHelp')"
          type="info"
          :closable="false"
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
            class="compute-filter"
            clearable
            :placeholder="c('field.username')"
            @keyup.enter="search"
          />
          <BusinessSearchFields
            :model="query"
            :fields="fields"
            @search="search"
          />
          <ElButton type="primary" :loading="loading" @click="search">{{
            c('common.search')
          }}</ElButton
          ><ElButton @click="reset">{{ c('common.reset') }}</ElButton>
        </div>
      </ElCard>
      <ReportInsights :rows="rows" :total="pager.total" />
      <ElCard shadow="never"
        ><template #header
          >{{ c('v6.personalTrend') }} · {{ metadata.timeZone }}</template
        >
        <ElAlert
          v-if="failed"
          :title="c('common.loadFailed')"
          type="error"
          :closable="false"
        />
        <p class="compute-help">{{ c('v7.originalCurrencies') }}</p>
        <div
          v-for="group in metadata.trendByCurrency || []"
          :key="String(group.currency)"
        >
          <p class="font-semibold">{{ currencyOptionLabel(group.currency) }}</p>
          <AnalyticsChart :rows="group.trend || []" :series="series" />
        </div>
        <p v-if="!metadata.trendByCurrency?.length" class="compute-help">
          {{ c('common.empty') }}
        </p>
      </ElCard>
      <ElCard class="compute-list-card" shadow="never">
        <ElTable height="100%"
          v-loading="loading"
          :data="rows"
          stripe
          :empty-text="c('common.empty')"
        >
          <ElTableColumn type="expand"
            ><template #default="{ row }">
              <ElDescriptions :column="3" border class="m-6">
                <ElDescriptionsItem
                  v-for="key in [
                    'email',
                    'phone',
                    'levelName',
                    'inviteCode',
                    'inviterName',
                    'topName',
                  ]"
                  :key="key"
                  :label="
                    c(
                      key === 'inviterName'
                        ? 'v5.directSuperior'
                        : key === 'topName'
                          ? 'v5.topAgent'
                          : 'field.' + key,
                    )
                  "
                  >{{ row[key] || '—' }}</ElDescriptionsItem
                >
                <ElDescriptionsItem :label="c('field.enabled')">{{
                  c(row.enabled ? 'common.enabled' : 'common.disabled')
                }}</ElDescriptionsItem>
                <ElDescriptionsItem :label="c('v5.memberType')">{{
                  c(row.isTest ? 'v5.testMember' : 'v5.regularMember')
                }}</ElDescriptionsItem>
                <ElDescriptionsItem :label="c('field.createdAt')">{{
                  date(row.createdAt)
                }}</ElDescriptionsItem>
              </ElDescriptions>
              <ReportCurrencyDetails
                :rows="row.currencySummaries || []"
              /> </template
          ></ElTableColumn>
          <ElTableColumn
            v-for="key in columns"
            :key="key"
            :label="label(key)"
            :align="['id', 'username', 'groupName'].includes(key) ? 'left' : 'right'"
            :fixed="['id', 'username'].includes(key) ? 'left' : undefined"
            min-width="145"
            show-overflow-tooltip
            ><template #default="{ row }">{{
              reportValue(row, key)
            }}</template></ElTableColumn
          >
        </ElTable>
        <ElPagination
          v-model:current-page="pager.page"
          v-model:page-size="pager.size"
          :total="pager.total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          class="mt-4"
          @current-change="load()"
          @size-change="resize"
        />
      </ElCard></div
  ></Page>
</template>
