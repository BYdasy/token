<script setup lang="ts">
import { usePageSelection } from '#/components/business/usePageSelection';
import { ElCard, ElTabPane, ElTabs } from 'element-plus';
import ActivitySettings from './settings.vue';
import Participations from './participations.vue';
import WheelMembers from './WheelMembers.vue';
import { useCompute } from '#/components/business/shared';
const props = defineProps<{ activityType: 'WHEEL' | 'RED_PACKET' | 'CHEST' }>();
const { c } = useCompute();
const tab = usePageSelection(
  () => `activity:${props.activityType}`,
  () =>
    props.activityType === 'WHEEL'
      ? ['settings', 'prizes', 'records', 'members']
      : props.activityType === 'CHEST' ? ['settings', 'records', 'members'] : ['settings', 'records'],
  'settings',
);
</script>
<template>
  <div class="business-module">
    <ElCard shadow="never">
      <ElTabs v-model="tab">
        <ElTabPane name="settings" :label="c('common.activitySettings')" />
        <ElTabPane
          v-if="activityType === 'WHEEL'"
          name="prizes"
          :label="c('field.prizes')"
        />
        <ElTabPane name="records" :label="c('nav.participations')" />
        <ElTabPane
          v-if="activityType !== 'RED_PACKET'"
          name="members"
          :label="c(activityType === 'CHEST' ? 'chestV31.members' : 'v6.wheelMembers')"
        />
      </ElTabs>
    </ElCard>
    <KeepAlive><ActivitySettings
      v-if="tab === 'settings' || tab === 'prizes'"
      :key="activityType + tab"
      :activity-type="activityType"
      :prize-only="tab === 'prizes'"
    />
    <WheelMembers v-else-if="tab === 'members'" :key="`${activityType}:members`" :activity-type="activityType === 'CHEST' ? 'CHEST' : 'WHEEL'" />
    <Participations v-else :key="`${activityType}:records`" :activity-type="activityType" />
    </KeepAlive>
  </div>
</template>
<style scoped>
.business-module { display: grid; gap: 20px; min-width: 0; }
</style>
