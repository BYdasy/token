<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useI18n } from '@vben/locales';
import { ElOption, ElSelect } from 'element-plus';
import { fetchMaterialCategories } from '#/api/material';
import { MATERIAL_CATEGORIES, materialCategoryLabel } from './materialCategories';
const props = withDefaults(defineProps<{ modelValue?: string; allowCreate?: boolean }>(), { modelValue: '', allowCreate: false });
const emit = defineEmits<{ 'update:modelValue': [value: string] }>();
const { t } = useI18n();
const categories = ref<string[]>([]);
const loading = ref(false);
let generation = 0;
const options = computed(() => [...new Set([...Object.values(MATERIAL_CATEGORIES), ...categories.value, props.modelValue].filter(Boolean))].map(value => ({ value, label: materialCategoryLabel(value, t) })));
async function load() {
  const current = ++generation;
  loading.value = true;
  try {
    const result = await fetchMaterialCategories();
    if (current === generation) categories.value = result;
  } catch {
    // 请求层已显示错误；保留标准分类及当前值，避免临时查询失败清空编辑内容。
  } finally { if (current === generation) loading.value = false; }
}
onMounted(() => void load());
onBeforeUnmount(() => generation++);
</script>
<template>
  <ElSelect :model-value="modelValue" :allow-create="allowCreate" clearable filterable default-first-option :loading="loading" :placeholder="t('admin.material.category')" @update:model-value="emit('update:modelValue', $event || '')" @visible-change="visible => { if (visible) void load(); }">
    <ElOption v-for="option in options" :key="option.value" :value="option.value" :label="option.label" />
  </ElSelect>
</template>
