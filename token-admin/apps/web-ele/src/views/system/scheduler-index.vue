<script setup lang="ts">
import { usePageSelection } from '#/components/business/usePageSelection';
import { ElCard, ElTabPane, ElTabs } from 'element-plus';
import Scheduler from './scheduler.vue';
import { useCompute } from '#/components/business/shared';
const { c } = useCompute();
const tab = usePageSelection<'jobs' | 'logs'>(
  'scheduler',
  ['jobs', 'logs'],
  'jobs',
);
</script>
<template>
  <div class="business-module">
    <ElCard shadow="never">
      <ElTabs v-model="tab">
        <ElTabPane name="jobs" :label="c('nav.jobs')" />
        <ElTabPane name="logs" :label="c('nav.job-runs')" />
      </ElTabs>
    </ElCard>
    <KeepAlive><Scheduler :key="tab" :section="tab" /></KeepAlive>
  </div>
</template>
