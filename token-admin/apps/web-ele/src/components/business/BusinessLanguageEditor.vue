<script setup lang="ts">
import type { ComputeRecord } from '#/api/compute';
import { computed, ref, watch } from 'vue';
import { SUPPORT_LANGUAGES } from '@vben/constants';
import { useI18n } from '@vben/locales';
import {
  ElAlert,
  ElButton,
  ElEmpty,
  ElFormItem,
  ElInput,
  ElTag,
} from 'element-plus';
import TinymceMin from '#/views/Tinymcemin.vue';
import { sanitizeNotificationHtml } from '#/utils/notification-html';
import { prepareBusinessContent } from './businessContent';
import { orderedBusinessLanguages } from './businessLanguages';
import { usePageSelection } from './usePageSelection';
const props = withDefaults(
  defineProps<{
    model: ComputeRecord;
    disabled?: boolean;
    titleKey?: string;
    bodyKey?: string;
  }>(),
  { disabled: false, titleKey: 'title', bodyKey: 'bodyHtml' },
);
const { t } = useI18n();
const lang = usePageSelection(
  'business-content-language',
  () => orderedBusinessLanguages(SUPPORT_LANGUAGES).map((item) => item.value),
  'zh',
  false,
);
const keyword = ref('');
const tr = (key: string) => t(`admin.compute.contentV5.${key}`);
watch(
  () => [
    props.model.id,
    props.model[props.titleKey],
    props.model[props.bodyKey],
    props.model.sourceLanguage,
  ],
  () => prepareBusinessContent(props.model, props.titleKey, props.bodyKey),
  { immediate: true },
);
const languages = computed(() => {
  return orderedBusinessLanguages(SUPPORT_LANGUAGES)
    .filter((row) =>
      row.searchText
        .toLowerCase()
        .includes(keyword.value.toLowerCase()),
    );
});
const current = computed<ComputeRecord>(() => {
  props.model.translations ||= {};
  return (props.model.translations[lang.value] ||= { title: '', bodyHtml: '' });
});
const ready = (language: string) =>
  Boolean(
    String(props.model.translations?.[language]?.title || '').trim() &&
    String(props.model.translations?.[language]?.bodyHtml || '').trim(),
  );
function clear() {
  if (props.disabled || lang.value === 'en') return;
  props.model.translations[lang.value] = { title: '', bodyHtml: '' };
  props.model.clearTranslationLanguages = [
    ...new Set([...(props.model.clearTranslationLanguages || []), lang.value]),
  ];
}
</script>
<template>
  <ElAlert
    :title="t('admin.compute.v7.translation.englishRequired')"
    type="info"
    :closable="false"
    class="language-help"
  />
  <div class="language-editor">
    <aside class="language-sidebar">
      <ElInput
        v-model="keyword"
        clearable
        :placeholder="tr('searchLanguage')"
        :aria-label="tr('searchLanguage')"
      />
      <div class="language-list" role="tablist" :aria-label="tr('languages')">
        <button
          v-for="item in languages"
          :key="item.value"
          type="button"
          role="tab"
          :aria-selected="lang === item.value"
          :class="{ selected: lang === item.value }"
          @click="lang = item.value"
        >
          <span
            >{{ item.label }}<small>{{ item.value }}</small></span
          >
          <ElTag v-if="item.value === 'en'" size="small" type="warning">{{
            tr('required')
          }}</ElTag>
          <ElTag v-else-if="ready(item.value)" size="small" type="success">{{
            tr('filled')
          }}</ElTag>
        </button>
        <ElEmpty
          v-if="!languages.length"
          :description="tr('noLanguage')"
          :image-size="50"
        />
      </div>
    </aside>
    <section class="language-body">
      <ElFormItem
        :label="t('admin.compute.field.title')"
        :required="lang === 'en'"
      >
        <ElInput v-model="current.title" :disabled="disabled" maxlength="200" />
      </ElFormItem>
      <ElFormItem
        :label="t('admin.compute.field.bodyHtml')"
        :required="lang === 'en'"
      >
        <div
          v-if="disabled"
          class="language-preview"
          v-html="sanitizeNotificationHtml(current.bodyHtml || '')"
        />
        <TinymceMin
          v-else
          :key="lang"
          v-model:bind-html="current.bodyHtml"
          :height="360"
        />
      </ElFormItem>
      <ElButton
        v-if="lang !== 'en' && !disabled"
        type="warning"
        plain
        @click="clear"
        >{{ t('admin.compute.translation.clear') }}</ElButton
      >
      <slot />
    </section>
  </div>
</template>
<style scoped>
.language-help {
  margin: 16px 0;
}
.language-editor {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  gap: 20px;
}
.language-sidebar {
  min-width: 0;
}
.language-list {
  height: 480px;
  overflow-y: auto;
  margin-top: 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
}
.language-list button {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  text-align: left;
  padding: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.language-list button.selected {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.language-list small {
  display: block;
  color: var(--el-text-color-secondary);
}
.language-body {
  min-width: 0;
}
.language-preview {
  min-height: 300px;
  max-width: 100%;
  overflow-wrap: anywhere;
}
.language-preview :deep(img) {
  max-width: 100%;
}
@media (max-width: 720px) {
  .language-editor {
    grid-template-columns: 1fr;
  }
  .language-list {
    height: 150px;
  }
  .language-body {
    width: 100%;
  }
}
</style>
