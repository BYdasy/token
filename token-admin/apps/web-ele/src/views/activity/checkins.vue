<script setup lang="ts">
import { useFilterRecovery } from '#/components/business/useFilterRecovery';
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { ElAlert, ElButton, ElCard, ElInput, ElPagination, ElTableColumn,ElTabs,ElTabPane } from 'element-plus';
import { ElTable } from '#/adapter/table';
import CheckinSettings from './CheckinSettings.vue';
import { usePageSelection } from '#/components/business/usePageSelection';
import { getCheckins } from '#/api/user-features';
import type { ComputeRecord } from '#/api/compute';
import { P, useCompute } from '#/components/business/shared';
const { c, can, date } = useCompute();
const tab=usePageSelection('activity:checkin',()=>['settings','records'],'settings');
const rows = ref<ComputeRecord[]>([]), username = ref(''), page = ref(1), total = ref(0), loading = ref(false), failed = ref(false);
useFilterRecovery('checkins', { username, page });
let listGeneration = 0;
onBeforeUnmount(() => { ++listGeneration; });
async function load() {
  if (!can(P.checkinRead, P.checkinWrite)) return;
  const current = ++listGeneration;
  loading.value = true; failed.value = false;
  try {
    const data = await getCheckins({ username: username.value, page: page.value, size: 20 });
    if (current !== listGeneration) return;
    rows.value = data.records; total.value = data.total;
  }
  catch { if (current === listGeneration) failed.value = true; }
  finally { if (current === listGeneration) loading.value = false; }
}
function search() { page.value = 1; void load(); }
function reset() { username.value = ''; search(); }
onMounted(load);
</script>
<template>
  <div class="compute-page">
    <ElCard shadow="never"><ElTabs v-model="tab"><ElTabPane name="settings" :label="c('common.activitySettings')"/><ElTabPane name="records" :label="c('nav.participations')"/></ElTabs></ElCard>
    <CheckinSettings v-if="tab==='settings'" />
    <template v-else>
    <ElCard shadow="never"><div class="compute-toolbar"><ElInput v-model="username" class="checkin-search" :placeholder="c('field.username')" clearable @keyup.enter="search" /><ElButton type="primary" @click="search">{{ c('common.search') }}</ElButton><ElButton @click="reset">{{ c('common.reset') }}</ElButton></div></ElCard>
    <ElCard shadow="never" v-loading="loading">
      <ElAlert v-if="failed" :title="c('common.loadFailed')" type="error" :closable="false" />
      <ElTable :data="rows">
        <ElTableColumn prop="orderNo" :label="c('field.orderNo')" min-width="180" />
        <ElTableColumn prop="username" :label="c('field.username')" min-width="160" />
        <ElTableColumn :label="c('v5.memberType')" min-width="110"><template #default="{ row }">{{ c(row.isTest ? 'v5.testMember' : 'v5.regularMember') }}</template></ElTableColumn>
        <ElTableColumn prop="checkinDate" :label="c('v11.features.checkinDate')" min-width="130" />
        <ElTableColumn prop="streakDays" :label="c('v11.features.streakDays')" min-width="120" />
        <ElTableColumn prop="rewardToken" :label="c('v11.features.rewardToken')" min-width="170" />
        <ElTableColumn :label="c('field.createdAt')" min-width="190"><template #default="{ row }">{{ date(row.createdAt) }}</template></ElTableColumn>
      </ElTable>
      <ElPagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @current-change="load" />
    </ElCard>
    </template>
  </div>
</template>
<style scoped>.checkin-search { width: 240px; } @media(max-width:700px) { .checkin-search { width:100%; } }</style>
