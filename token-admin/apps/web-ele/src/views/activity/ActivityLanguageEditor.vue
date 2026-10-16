<script setup lang="ts">
import type { ComputeRecord } from '#/api/compute';
import { computed, ref } from 'vue';
import { SUPPORT_LANGUAGES } from '@vben/constants';
import { ElAlert, ElButton, ElFormItem, ElInput, ElTag } from 'element-plus';
import { orderedBusinessLanguages } from '#/components/business/businessLanguages';
import { useBusinessFormTranslation } from '#/components/business/useBusinessFormTranslation';
import { useCompute } from '#/components/business/shared';
import { startActivityTranslation, fetchActivityTranslation } from '#/api/activity-translations';
import TinymceMin from '#/views/Tinymcemin.vue';
const props = defineProps<{ model: ComputeRecord; disabled?: boolean; active: boolean }>();
const { c } = useCompute();
const language = ref('zh'), keyword = ref('');
const languages = computed(() => orderedBusinessLanguages(SUPPORT_LANGUAGES).filter(item =>
  item.searchText.toLowerCase().includes(keyword.value.toLowerCase())));
const fields = ['title', 'description', 'content'] as const;
type Field = typeof fields[number];
const sourceValue = (field: Field) => String(field === 'title' ? props.model.name || '' : field === 'description' ? props.model.description || '' : props.model.wheelConfig?.descriptionHtml || '');
function get(field: Field) {
  return language.value === 'zh' ? sourceValue(field) : String(props.model.wheelConfig?.translations?.[language.value]?.[field] || '');
}
function set(field: Field, value: string) {
  props.model.wheelConfig.translations ||= {};
  const current = (props.model.wheelConfig.translations[language.value] ||= {});
  current[field] = value;
  if (language.value === 'zh') {
    if (field === 'title') props.model.name = value;
    else if (field === 'description') props.model.description = value;
    else props.model.wheelConfig.descriptionHtml = value;
  }
}
const title = computed({ get: () => get('title'), set: value => set('title', value) });
const description = computed({ get: () => get('description'), set: value => set('description', value) });
const content = computed({ get: () => get('content'), set: value => set('content', value) });
const translation = useBusinessFormTranslation({
  model: props.model,
  resource: () => 'CONTENT',
  active: () => props.active && !props.disabled,
  adapter: {
    snapshot: () => JSON.stringify(props.model),
    payload: () => ({
      type: props.model.type, sourceId: props.model.id, sourceLanguage: 'zh',
      title: sourceValue('title'), description: sourceValue('description'), content: sourceValue('content'),
      translations: JSON.parse(JSON.stringify(props.model.wheelConfig.translations || {})),
    }),
    valid: body => Boolean(String(body.title || '').trim() && String(body.content || '').trim()),
    merge: (result, source, targets) => {
      props.model.wheelConfig.translations ||= {};
      for (const [locale, raw] of Object.entries(result.translations || {})) {
        if (locale === source || (targets && !targets.has(locale))) continue;
        const translated = raw as ComputeRecord;
        for (const field of fields) {
          if (typeof translated[field] === 'string')
            (props.model.wheelConfig.translations[locale] ||= {})[field] = translated[field];
        }
      }
    },
    start: startActivityTranslation,
    fetch: fetchActivityTranslation,
  },
});
const ready = (locale: string) => fields.every(field => !sourceValue(field).trim() || Boolean(String(props.model.wheelConfig.translations?.[locale]?.[field] || '').trim()));
</script>
<template>
  <div class="activity-language-editor">
  <ElAlert :title="c('activityV30.languageHelp')" type="info" :closable="false" class="mb-4" />
  <div class="activity-language-toolbar">
    <ElButton :disabled="disabled || translation.running.value" :loading="translation.loading.value" @click="translation.start(['en'])">{{ c('v6.translateEnglish') }}</ElButton>
    <ElButton :disabled="disabled || translation.running.value" :loading="translation.loading.value" @click="translation.start()">{{ c('v6.translateAll') }}</ElButton>
    <ElButton v-if="translation.running.value" :disabled="translation.loading.value" @click="translation.refresh()">{{ c('activityV30.refreshTranslation') }}</ElButton>
    <ElTag v-if="translation.running.value">{{ c('activityV30.translating') }}</ElTag>
    <ElTag v-else-if="translation.task.value.status === 'FAILED'" type="danger">{{ c('common.loadFailed') }}</ElTag>
  </div>
  <div class="activity-languages">
    <aside>
      <ElInput v-model="keyword" clearable :placeholder="c('contentV5.searchLanguage')" />
      <div class="activity-language-list" role="tablist">
        <button v-for="item in languages" :key="item.value" type="button" role="tab" :aria-selected="language === item.value" :class="{ selected: language === item.value }" @click="language = item.value">
          <span>{{ item.label }}<small>{{ item.value }}</small></span>
          <ElTag v-if="item.value === 'en'" size="small" type="warning">{{ c('contentV5.required') }}</ElTag>
          <ElTag v-else-if="ready(item.value)" size="small" type="success">{{ c('contentV5.filled') }}</ElTag>
        </button>
      </div>
    </aside>
    <section>
      <ElFormItem :label="c('field.title')" :required="language === 'zh' || language === 'en'"><ElInput v-model="title" maxlength="160" :disabled="disabled" /></ElFormItem>
      <ElFormItem :label="c('field.description')"><ElInput v-model="description" type="textarea" :rows="3" maxlength="2000" :disabled="disabled" /></ElFormItem>
      <ElFormItem :label="c('wheelUpdate.description')" :required="language === 'zh' || language === 'en'">
        <TinymceMin :key="language" v-model:bind-html="content" :height="360" :disabled="disabled" />
      </ElFormItem>
    </section>
  </div>
  </div>
</template>
<style scoped>
.activity-language-editor { min-width:0; container:activity-editor / inline-size; }
.activity-language-toolbar { display:flex; gap:10px; align-items:center; margin-bottom:16px; flex-wrap:wrap; }
.activity-language-toolbar :deep(.el-button + .el-button) { margin-left:0; }
.activity-languages { display:grid; grid-template-columns:200px minmax(0, 1fr); gap:18px; margin-bottom:20px; }
.activity-languages aside,.activity-languages section { min-width:0; }
.activity-language-list { height:470px; overflow-y:auto; margin-top:12px; border:1px solid var(--el-border-color); border-radius:6px; }
.activity-language-list button { width:100%; display:flex; align-items:center; justify-content:space-between; gap:8px; text-align:left; padding:12px; border-bottom:1px solid var(--el-border-color-lighter); }
.activity-language-list button > span:first-child { min-width:0; overflow-wrap:anywhere; }
.activity-language-list :deep(.el-tag) { flex-shrink:0; }
.activity-language-list button.selected { color:var(--el-color-primary); background:var(--el-color-primary-light-9); }
.activity-language-list small { display:block; color:var(--el-text-color-secondary); }
@container activity-editor (max-width:640px) { .activity-languages { grid-template-columns:minmax(0,1fr); } .activity-language-list { height:150px; } }
</style>
