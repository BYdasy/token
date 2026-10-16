<script setup lang="ts">
import { Page } from '@vben/common-ui';
import type { ConfigResource } from '#/api/compute';
import ConfigManager from './components/ResourceManager.vue';
import { usePageSelection } from '#/components/business/usePageSelection';
import { ElTabs, ElTabPane } from 'element-plus';
import { useCompute } from '#/components/business/shared';
const props = defineProps<{ resource: ConfigResource }>();
const tab = usePageSelection(
  'catalog-models',
  ['models', 'providers'],
  'models',
  props.resource === 'models' ? 'tab' : false,
);
const { c } = useCompute();
</script>
<template>
  <Page>
    <div class="compute-page">
      <template v-if="resource === 'models'">
        <ElTabs v-model="tab" class="catalog-tabs"
          ><ElTabPane name="models" :label="c('resource.models')" /><ElTabPane
            name="providers"
            :label="c('resource.providers')"
        /></ElTabs>
        <KeepAlive><ConfigManager
          :key="tab"
          :resource="tab === 'models' ? 'models' : 'providers'"
        /></KeepAlive>
      </template>
      <ConfigManager v-else :key="resource" :resource="resource" />
    </div>
  </Page>
</template>
<style scoped>
.catalog-tabs { width: 100%; min-width: 0; }
.catalog-tabs :deep(.el-tabs__header) { padding: 12px 16px 0; margin-bottom: 20px; background: var(--el-bg-color); border: 1px solid var(--el-border-color-lighter); border-radius: 12px; }
.catalog-tabs :deep(.el-tabs__nav-wrap::after) { display: none; }
.catalog-tabs :deep(.el-tabs__item) { height: 42px; padding: 0 18px; font-weight: 600; }
.catalog-tabs :deep(.el-tabs__active-bar) { height: 3px; border-radius: 3px; }
</style>
