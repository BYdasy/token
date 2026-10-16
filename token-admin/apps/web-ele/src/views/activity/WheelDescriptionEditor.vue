<script setup lang="ts">
import type { ComputeRecord } from '#/api/compute';
import { computed, ref, watch } from 'vue';
import { SUPPORT_LANGUAGES } from '@vben/constants';
import { ElAlert, ElFormItem, ElOption, ElSelect } from 'element-plus';
import { orderedBusinessLanguages } from '#/components/business/businessLanguages';
import { useCompute } from '#/components/business/shared';
import TinymceMin from '#/views/Tinymcemin.vue';
const props = defineProps<{ model: ComputeRecord; disabled?: boolean }>();
const { c } = useCompute();
const language = ref('zh');
const keyword = ref('');
const languages = computed(() => orderedBusinessLanguages(SUPPORT_LANGUAGES).filter(
  item => `${item.label} ${item.value}`.toLowerCase().includes(keyword.value.toLowerCase()),
));
watch(() => props.model, model => {
  model.descriptionTranslations ||= {};
}, { immediate: true });
const body = computed({
  get: () => language.value === 'zh' ? String(props.model.descriptionHtml || '') : String(props.model.descriptionTranslations?.[language.value] || ''),
  set: (value: string) => {
    if (language.value === 'zh') props.model.descriptionHtml = value;
    else {
      props.model.descriptionTranslations ||= {};
      if (value.trim()) props.model.descriptionTranslations[language.value] = value;
      else delete props.model.descriptionTranslations[language.value];
    }
  },
});
</script>
<template>
  <ElFormItem :label="c('wheelUpdate.description')" required class="wheel-description">
    <ElAlert :title="c('wheelUpdate.descriptionHelp')" type="info" :closable="false" class="mb-3" />
    <div class="wheel-language-select">
      <ElSelect v-model="language" :disabled="disabled" filterable :filter-method="value => keyword = value" @visible-change="keyword = ''">
        <ElOption v-for="item in languages" :key="item.value" :value="item.value" :label="item.label" />
      </ElSelect>
    </div>
    <TinymceMin :key="language" v-model:bind-html="body" :disabled="disabled" :height="360" />
  </ElFormItem>
</template>
<style scoped>
.wheel-description :deep(.el-form-item__content) { display: block; }
.wheel-language-select { margin-bottom: 14px; }
.wheel-language-select .el-select { width: 260px; max-width: 100%; }
</style>
