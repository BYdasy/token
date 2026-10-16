<script lang="ts" setup>
import { computed } from 'vue';
import { usePageSelection } from '#/components/business/usePageSelection';
import { useI18n } from '@vben/locales';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';

import { ElEmpty, ElTabPane, ElTabs } from 'element-plus';

import KeyValuePanel from './components/KeyValuePanel.vue';
import LanguageConfigPanel from './components/LanguageConfigPanel.vue';
import LocaleBatchPanel from './components/LocaleBatchPanel.vue';
import TemplatePanel from './components/TemplatePanel.vue';

const { hasAccessByCodes, hasAccessByRoles } = useAccess();
const { t } = useI18n();

function canAccess(code: string) {
  const readCodes: Record<string, string> = {
    '60601': '6060101',
    '60602': '6060201',
    '60603': '6060301',
    '60604': '6060401',
    '60605': '6060501',
  };
  const authorities = [readCodes[code] || code, 'super'];
  return hasAccessByCodes(authorities) || hasAccessByRoles(authorities);
}

const props = defineProps<{ section?: string }>();
const tabs = computed(() =>
  [
    {
      code: '60601',
      label: t('admin.translation.tabs.template'),
      name: 'template',
    },
    {
      code: '60602',
      label: t('admin.translation.tabs.keyValue'),
      name: 'kv',
    },
    {
      code: '60603',
      label: t('admin.translation.tabs.backendPackage'),
      name: 'locales',
    },
    {
      code: '60604',
      label: t('admin.translation.tabs.frontendPackage'),
      name: 'frontend',
    },
    {
      code: '60605',
      label: t('admin.translation.tabs.languageConfig'),
      name: 'language',
    },
  ].filter((item) => canAccess(item.code) && (!props.section || item.name === props.section))
);

const activeTab = usePageSelection(
  () => `translations:${props.section || 'all'}`,
  () => tabs.value.map((item) => item.name),
  props.section || 'template',
);
</script>

<template>
  <Page>
    <div class="system-page exp-page system-translation-page">
      <ElTabs
        v-if="tabs.length > 0"
        v-model="activeTab"
        class="system-translation-tabs"
        :class="{ 'operations-single-tab': section }"
      >
        <ElTabPane v-for="tab in tabs" :key="tab.name" :label="tab.label" :name="tab.name">
          <KeepAlive><TemplatePanel v-if="activeTab === 'template' && tab.name === 'template'" /></KeepAlive>
          <KeepAlive><KeyValuePanel v-if="activeTab === 'kv' && tab.name === 'kv'" /></KeepAlive>
          <KeepAlive>
          <LocaleBatchPanel
            v-if="activeTab === 'locales' && tab.name === 'locales'"
            access-code="60603"
            :allow-delete="false"
            :type="5"
          />
          </KeepAlive>
          <KeepAlive>
          <LocaleBatchPanel
            v-if="activeTab === 'frontend' && tab.name === 'frontend'"
            access-code="60604"
            :allow-delete="false"
            :type="7"
          />
          </KeepAlive>
          <KeepAlive><LanguageConfigPanel v-if="activeTab === 'language' && tab.name === 'language'" /></KeepAlive>
        </ElTabPane>
      </ElTabs>
      <div v-else class="system-translation-no-access">
        <ElEmpty :description="t('admin.translation.noAccess')" :image-size="88" />
      </div>
    </div>
  </Page>
</template>

<style>
.system-translation-page {
  width: 100%;
  min-width: 0;
}

.system-translation-tabs > .el-tabs__header {
  padding: 12px 16px 0;
  margin-bottom: 20px;
  background: var(--exp-surface);
  border: 1px solid var(--exp-border);
  border-radius: var(--exp-radius);
  box-shadow: var(--exp-shadow);
}

.system-translation-tabs .el-tabs__nav-wrap::after {
  display: none;
}

.system-translation-tabs .el-tabs__item {
  height: 42px;
  padding: 0 18px;
  font-weight: 600;
  color: var(--exp-text-regular);
}

.system-translation-tabs .el-tabs__item.is-active {
  color: var(--exp-primary);
}

.system-translation-tabs .el-tabs__active-bar {
  height: 3px;
  border-radius: 999px 999px 0 0;
}

.system-translation-panel {
  width: 100%;
}

.system-translation-text {
  display: -webkit-box;
  max-height: 46px;
  overflow: hidden;
  -webkit-line-clamp: 2;
  color: var(--exp-text-regular);
  -webkit-box-orient: vertical;
}

.system-translation-lang-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 12px;
}

.system-translation-rich-tabs {
  width: 100%;
  min-width: 0;
}

.system-translation-empty {
  padding: 14px 16px;
  color: var(--exp-text-secondary);
  background: var(--exp-soft);
  border: 1px dashed var(--exp-border);
  border-radius: 8px;
}

.system-translation-no-access {
  display: grid;
  place-items: center;
  min-height: 360px;
  background: var(--exp-surface);
  border: 1px solid var(--exp-border);
  border-radius: var(--exp-radius);
  box-shadow: var(--exp-shadow);
}

@media (max-width: 900px) {
  .system-translation-lang-grid {
    grid-template-columns: 1fr;
  }

  .system-translation-tabs > .el-tabs__header {
    padding-inline: 10px;
  }

  .system-translation-tabs .el-tabs__item {
    padding: 0 12px;
  }
}
</style>
