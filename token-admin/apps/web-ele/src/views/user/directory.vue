<script setup lang="ts">
import { usePageSelection } from '#/components/business/usePageSelection';
import { ElTabPane, ElTabs } from 'element-plus';
import { useCompute } from '#/components/business/shared';
import GroupDefinitions from './GroupDefinitions.vue';
import GroupMembers from './GroupMembers.vue';
const { c } = useCompute();
const tab = usePageSelection('groups', ['members', 'definitions'], 'members');
</script>
<template>
  <div class="p-4">
    <ElTabs v-model="tab">
      <ElTabPane name="members" :label="c('v5.groupMembers')"
        ><KeepAlive><GroupMembers v-if="tab === 'members'"
      /></KeepAlive></ElTabPane>
      <ElTabPane name="definitions" :label="c('v5.groupDefinitions')"
        ><KeepAlive><GroupDefinitions v-if="tab === 'definitions'"
      /></KeepAlive></ElTabPane>
    </ElTabs>
  </div>
</template>
