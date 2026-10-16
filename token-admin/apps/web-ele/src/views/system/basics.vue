<script setup lang="ts">
import { computed } from 'vue';
import { useAccess } from '@vben/access';
import { usePageSelection } from '#/components/business/usePageSelection';
import { ElCard, ElTabPane, ElTabs } from 'element-plus';
import SystemSettings from './settings/index.vue';
import PlatformSettings from './platform.vue';
import UserPresentation from './user-presentation.vue';
import RewardSettings from './rewards.vue';
import ResourceManager from '../operation/components/ResourceManager.vue';
import { P, useCompute } from '#/components/business/shared';
const { c, can } = useCompute();
const { hasAccessByCodes, hasAccessByRoles } = useAccess();
const canSystem = () =>
  hasAccessByCodes(['60101', '60102', 'super']) ||
  hasAccessByRoles(['60101', '60102', 'super']);
const tabs = computed(() => [
  ...(canSystem() ? ['system'] : []),
  ...(can(P.platformSettingsRead, P.platformSettingsWrite) ? ['platform'] : []),
  ...(can(P.presentationSettingsRead, P.presentationSettingsWrite) ? ['user-presentation'] : []),
  ...(can(P.rewardSettingsRead, P.rewardSettingsWrite) ? ['rewards'] : []),
  ...(can(P.withdrawalRulesRead, P.withdrawalRulesWrite) ? ['fee-rules'] : []),
  ...(can(P.transferRulesRead, P.transferRulesWrite) ? ['transfer-rules'] : []),
]);
const tab = usePageSelection('basics', tabs, 'system');
</script>
<template>
  <div v-if="tabs.includes(tab)" class="business-module">
    <ElCard shadow="never">
      <ElTabs v-model="tab">
        <ElTabPane
          v-for="key in tabs"
          :key="key"
          :name="key"
          :label="key === 'user-presentation' ? c('ui.presentation') : key === 'rewards' ? c('reward.title') : c(`nav.${key}`)"
        />
      </ElTabs>
    </ElCard>
    <KeepAlive>
    <SystemSettings v-if="tab === 'system'" />
    <PlatformSettings v-else-if="tab === 'platform'" />
    <UserPresentation v-else-if="tab === 'user-presentation'" />
    <RewardSettings v-else-if="tab === 'rewards'" />
    <ResourceManager
      v-else-if="tab === 'fee-rules' || tab === 'transfer-rules'"
      :key="tab"
      :resource="tab"
    />
    </KeepAlive>
  </div>
</template>
<style scoped>
.business-module { display: grid; gap: 20px; min-width: 0; }
</style>
