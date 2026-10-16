<script setup lang="ts">
import { reactive } from 'vue';
import { ElInput, ElOption, ElSelect } from 'element-plus';
import { useCompute } from './shared';
interface SearchField {
  key: string;
  labelKey?: string;
  options?: Array<{ label: string; value: string | number; searchKeywords?: string }>;
}
defineProps<{ model: Record<string, any>; fields: SearchField[] }>();
defineEmits<{ search: [] }>();
const { c } = useCompute();
const optionQueries = reactive<Record<string, string>>({});
function filteredOptions(field: SearchField) {
  const query = (optionQueries[field.key] || '').trim().toLocaleLowerCase();
  return !query ? field.options : field.options?.filter((option) =>
    `${option.label} ${option.searchKeywords || ''}`.toLocaleLowerCase().includes(query));
}
</script>
<template>
  <template v-for="field in fields" :key="field.key">
    <ElSelect
      v-if="field.options"
      v-model="model[field.key]"
      class="compute-filter"
      clearable
      filterable
      :filter-method="field.options.some(option => option.searchKeywords) ? (value: string) => optionQueries[field.key] = value : undefined"
      :placeholder="c(field.labelKey || `field.${field.key}`)"
      :aria-label="c(field.labelKey || `field.${field.key}`)"
      @visible-change="(visible: boolean) => { if (!visible) optionQueries[field.key] = ''; }"
    >
      <ElOption
        v-for="option in filteredOptions(field)"
        :key="option.value"
        :label="option.label"
        :value="option.value"
      />
    </ElSelect>
    <ElInput
      v-else
      v-model="model[field.key]"
      class="compute-filter"
      clearable
      :placeholder="c(field.labelKey || `field.${field.key}`)"
      :aria-label="c(field.labelKey || `field.${field.key}`)"
      @keyup.enter="$emit('search')"
    />
  </template>
</template>
