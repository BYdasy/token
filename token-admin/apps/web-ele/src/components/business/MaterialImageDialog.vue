<script setup lang="ts">
import { onBeforeUnmount, reactive, ref, watch } from 'vue';
import { useI18n } from '@vben/locales';
import { ElButton, ElDialog, ElEmpty, ElImage, ElInput, ElPagination } from 'element-plus';
import { fetchMaterials, type MaterialAsset } from '#/api/material';
import MaterialCategorySelect from './MaterialCategorySelect.vue';
import { materialCategoryLabel } from './materialCategories';
import { imageSource } from '#/utils/image-source';
const props = defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; select: [value: MaterialAsset] }>();
const { t } = useI18n();
const loading = ref(false);
const keyword = ref('');
const category = ref('');
const materials = ref<MaterialAsset[]>([]);
const pager = reactive({ current: 1, size: 12, total: 0 });
let generation = 0;
async function load() {
  const current = ++generation;
  loading.value = true;
  try {
    const page = await fetchMaterials({ filterParams: { mediaType: 'IMAGE', name: keyword.value.trim() || undefined, category: category.value || undefined }, page: { current: pager.current, size: pager.size } });
    if (current !== generation || !props.modelValue) return;
    materials.value = page.items.filter(item => item.mediaType === 'IMAGE');
    pager.total = page.total;
  } catch {
    if (current === generation) { materials.value = []; pager.total = 0; }
  } finally { if (current === generation) loading.value = false; }
}
function search() { pager.current = 1; void load(); }
function reset() { keyword.value = ''; category.value = ''; search(); }
function select(item: MaterialAsset) { emit('select', item); emit('update:modelValue', false); }
watch(() => props.modelValue, value => {
  if (value) search();
  else { generation++; loading.value = false; }
}, { immediate: true });
onBeforeUnmount(() => generation++);
</script>
<template>
  <ElDialog :model-value="modelValue" append-to-body destroy-on-close :title="t('admin.avatar.dialogTitle')" width="820px" @update:model-value="emit('update:modelValue', $event)">
    <div class="material-picker-toolbar">
      <MaterialCategorySelect v-model="category" class="exp-filter" />
      <ElInput v-model="keyword" class="exp-filter" clearable :placeholder="t('admin.avatar.searchPlaceholder')" @keyup.enter="search" />
      <ElButton type="primary" @click="search">{{ t('admin.common.search') }}</ElButton>
      <ElButton @click="reset">{{ t('admin.common.reset') }}</ElButton>
    </div>
    <div v-loading="loading" class="material-picker-grid">
      <button v-for="item in materials" :key="item.id" type="button" @click="select(item)">
        <ElImage v-if="imageSource(item.fileUrl)" fit="cover" :src="imageSource(item.fileUrl)" />
        <span :title="item.name">{{ item.name }}</span>
        <small v-if="item.category">{{ materialCategoryLabel(item.category, t) }}</small>
      </button>
    </div>
    <ElEmpty v-if="!loading && !materials.length" :description="t('admin.avatar.empty')" :image-size="72" />
    <ElPagination v-model:current-page="pager.current" class="material-picker-pagination" background layout="total, prev, pager, next" :page-size="pager.size" :total="pager.total" @current-change="load" />
  </ElDialog>
</template>
<style scoped>
.material-picker-toolbar { display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 16px; }
.material-picker-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; min-height: 180px; }
.material-picker-grid button { overflow: hidden; border: 1px solid var(--el-border-color); border-radius: 8px; text-align: left; padding-bottom: 8px; }
.material-picker-grid button:hover { border-color: var(--el-color-primary); }
.material-picker-grid :deep(.el-image) { width: 100%; height: 118px; }
.material-picker-grid span,.material-picker-grid small { display: block; padding: 3px 8px; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.material-picker-grid small { color: var(--el-text-color-secondary); }
.material-picker-pagination { justify-content: flex-end; margin-top: 18px; }
@media(max-width: 640px) { .material-picker-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
