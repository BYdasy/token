<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Page } from '@vben/common-ui';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDatePicker,
  ElInput,
  ElOption,
  ElSelect,
  ElMessageBox,
  ElPagination,
  ElTableColumn,
} from 'element-plus';
import { ElTable } from '#/adapter/table';
import {
  redeemWheelParticipation,
  type ComputeRecord,
  type PageResource,
} from '#/api/compute';
import BusinessSearchFields from '#/components/business/BusinessSearchFields.vue';
import { useCompute } from '#/components/business/shared';
import { usePaged } from '#/components/business/usePaged';
import { currencyOptionLabel } from '#/utils/currency';
import { ACTIVITY_PERMISSIONS } from '#/constants/permissions';
const props = defineProps<{ activityType: 'WHEEL' | 'RED_PACKET' | 'CHEST' }>();
const activityPermissions = computed(() => ACTIVITY_PERMISSIONS[props.activityType]);
const { c, can, date, enumLabel } = useCompute();
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
} = usePaged(
  computed<PageResource>(() => 'activity-participations'),
  () => can(activityPermissions.value.read, activityPermissions.value.write),
  () => ({ type: props.activityType }),
);
const columns = [
  'orderNo',
  'activityName',
  'prizeName',
  'username',
  'amount',
  'status',
  'createdAt',
  ...(props.activityType === 'WHEEL'
    ? ['redemptionCode', 'redemptionType', 'redeemedAt', 'redeemedByUsername']
    : []),
];
const redeeming = ref(false);
async function refresh() {
  if (!can(activityPermissions.value.read, activityPermissions.value.write)) return;
  await load();
}
async function redeem(row: ComputeRecord, mode: 'AUTO' | 'MANUAL') {
  if (!can(activityPermissions.value.write) || redeeming.value) return;
  try {
    await ElMessageBox.confirm(
      c(mode === 'AUTO' ? 'v6.autoRedeemHelp' : 'v6.manualRedeemHelp'),
      c(`v6.${mode}`),
      { type: 'warning' },
    );
  } catch {
    return;
  }
  redeeming.value = true;
  try {
    await redeemWheelParticipation(row.id, mode);
    await refresh();
  } finally {
    redeeming.value = false;
  }
}
const display = (key: string, row: ComputeRecord) =>
  key === 'prizeName' ? row[key] || '—' : key === 'currency' ? currencyOptionLabel(row[key]) : key.endsWith('At')
    ? date(row[key])
    : ['PENDING_REDEMPTION', 'MANUAL_REDEEMED', 'AUTO', 'MANUAL'].includes(
          row[key],
        )
      ? c(`v6.${row[key]}`)
      : enumLabel(row[key]);
const columnSpecs = computed(() =>
  columns.map((key) => ({
    key,
    label: c(
      ['redemptionCode', 'redemptionType', 'redeemedAt'].includes(key)
        ? `v6.${key}`
        : key === 'redeemedByUsername'
          ? 'v7.translation.redeemedByUsername'
          : key === 'prizeName'
            ? 'v35Ledger.fact.prizeName'
          : `field.${key}`,
    ),
    exportValue: (row: ComputeRecord) => display(key, row),
  })),
);
onMounted(refresh);
</script>
<template>
  <Page>
    <div class="compute-page">
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
            v-model="query.keyword"
            clearable
            class="compute-filter"
            :placeholder="c('common.keyword')"
            @keyup.enter="search"
          />
          <BusinessSearchFields
            :model="query"
            :fields="[{ key: 'activityId' }]"
            @search="search"
          />
          <ElInput
            v-if="activityType === 'WHEEL'"
            v-model="query.redemptionCode"
            :placeholder="c('v6.redemptionCode')"
            class="compute-filter"
          />
          <ElSelect
            v-model="query.status"
            clearable
            :placeholder="c('field.status')"
            class="compute-filter"
            ><ElOption
              v-for="status in [
                'PENDING_REDEMPTION',
                'PAID',
                'MANUAL_REDEEMED',
                'NO_PRIZE',
              ]"
              :key="status"
              :value="status"
              :label="c(`v6.${status}`)"
          /></ElSelect>
          <ElButton type="primary" :loading="loading" @click="search">
            {{ c('common.search') }}
          </ElButton>
          <ElButton @click="reset">{{ c('common.reset') }}</ElButton>
        </div>
      </ElCard>
      <ElCard shadow="never">
        <ElAlert
          v-if="failed"
          :title="c('common.loadFailed')"
          type="error"
          :closable="false"
          class="mb-4"
        />
        <ElTable
          v-loading="loading"
          :data="rows"
          stripe
          :empty-text="c('common.empty')"
        >
          <ElTableColumn
            v-for="column in columnSpecs"
            :key="column.key"
            :label="column.label"
            :min-width="column.key.endsWith('At') ? 180 : 140"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              {{ display(column.key, row) }}
            </template>
          </ElTableColumn>
          <ElTableColumn
            v-if="activityType === 'WHEEL' && can(activityPermissions.write)"
            :label="c('common.actions')"
            fixed="right"
            width="190"
            ><template #default="{ row }"
              ><template v-if="row.status === 'PENDING_REDEMPTION'"
                ><ElButton
                  link
                  type="primary"
                  :disabled="redeeming"
                  @click="redeem(row, 'AUTO')"
                  >{{ c('v6.AUTO') }}</ElButton
                ><ElButton
                  link
                  type="warning"
                  :disabled="redeeming"
                  @click="redeem(row, 'MANUAL')"
                  >{{ c('v6.MANUAL') }}</ElButton
                ></template
              ></template
            ></ElTableColumn
          >
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
    </div>
  </Page>
</template>
