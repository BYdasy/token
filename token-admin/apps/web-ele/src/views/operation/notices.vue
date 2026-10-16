<script setup lang="ts">
import { computed } from 'vue';
import { usePageSelection } from '#/components/business/usePageSelection';
import { ElCard, ElTabPane, ElTabs } from 'element-plus';
import Content from './content.vue';
import CompanyFiles from './company-files.vue';
import { P, useCompute } from '#/components/business/shared';
const { c, can } = useCompute();
const tabs = computed(() => [
  ...(can(P.announcementRead, P.announcementWrite) ? ['ANNOUNCEMENT' as const] : []),
  ...(can(P.articleRead, P.articleWrite) ? ['ARTICLE' as const] : []),
  ...(can(P.companyFileRead, P.companyFileWrite) ? ['COMPANY_FILES' as const] : []),
]);
const tab = usePageSelection<'ANNOUNCEMENT' | 'ARTICLE' | 'COMPANY_FILES'>(
  'notices',
  tabs,
  'ANNOUNCEMENT',
);
</script>
<template>
  <div v-if="tabs.includes(tab)" class="business-module">
    <ElCard shadow="never">
      <ElTabs v-model="tab">
        <ElTabPane v-if="tabs.includes('ANNOUNCEMENT')" name="ANNOUNCEMENT" :label="c('nav.announcements')" />
        <ElTabPane v-if="tabs.includes('ARTICLE')" name="ARTICLE" :label="c('nav.articles')" />
        <ElTabPane v-if="tabs.includes('COMPANY_FILES')" name="COMPANY_FILES" :label="c('companyFile.title')" />
      </ElTabs>
    </ElCard>
    <KeepAlive><CompanyFiles v-if="tab === 'COMPANY_FILES'" />
    <Content v-else :key="tab" :kind="tab" />
    </KeepAlive>
  </div>
</template>
