<script lang="ts" setup>
import { useFilterRecovery } from '#/components/business/useFilterRecovery';
import type { UploadRequestOptions } from 'element-plus';

import type { MaterialAsset } from '#/api/material';

import { onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { useI18n } from '@vben/locales';

import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import {
  ElButton,
  ElCard,
  ElDatePicker,
  ElDialog,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElImage,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElPagination,
  ElSelect,
  ElUpload,
} from 'element-plus';

import { deleteMaterial, fetchMaterials, updateMaterial, uploadMaterial } from '#/api/material';
import MaterialCategorySelect from '#/components/business/MaterialCategorySelect.vue';
import { MATERIAL_CATEGORIES, materialCategoryLabel } from '#/components/business/materialCategories';
import { permissionCodes, SYSTEM_PERMISSIONS } from '#/constants/permissions';

defineOptions({ name: 'SystemMaterial' });

const { t } = useI18n();

const loading = ref(false);
const uploading = ref(false);
const items = ref<MaterialAsset[]>([]);
const editVisible = ref(false);

const filters = reactive({
  category: '',
  createdRange: [] as string[] | null,
  fileName: '',
  mediaType: '',
  name: '',
  tags: '',
});
const pager = reactive({ current: 1, size: 24, total: 0 });
useFilterRecovery('materials', { filters, pager });
const editForm = reactive({
  category: '',
  id: 0,
  name: '',
  remark: '',
  tags: '',
});

onMounted(() => void loadData());
let listGeneration = 0;
onBeforeUnmount(() => { ++listGeneration; });

async function loadData() {
  const current = ++listGeneration;
  loading.value = true;
  try {
    const page = await fetchMaterials({
      filterParams: {
        category: filters.category || undefined,
        createdFrom: filters.createdRange?.[0],
        createdTo: filters.createdRange?.[1],
        fileName: filters.fileName || undefined,
        mediaType: filters.mediaType || undefined,
        name: filters.name || undefined,
        tags: filters.tags || undefined,
      },
      page: { current: pager.current, size: pager.size },
    });
    if (current !== listGeneration) return;
    items.value = page.items;
    pager.total = page.total;
  } finally {
    if (current === listGeneration) loading.value = false;
  }
}

async function handleUpload(options: UploadRequestOptions) {
  uploading.value = true;
  try {
    const result = await uploadMaterial(options.file as File, { category: filters.category || MATERIAL_CATEGORIES.general });
    ElMessage.success(t('admin.material.uploadSuccess', { name: options.file.name }));
    pager.current = 1;
    await loadData();
    return result;
  } finally {
    uploading.value = false;
  }
}

function openEdit(item: MaterialAsset) {
  Object.assign(editForm, {
    category: item.category || '',
    id: item.id,
    name: item.name,
    remark: item.remark || '',
    tags: item.tags || '',
  });
  editVisible.value = true;
}

async function saveEdit() {
  await updateMaterial(editForm.id, editForm);
  editVisible.value = false;
  ElMessage.success(t('admin.material.updated'));
  await loadData();
}

async function removeItem(item: MaterialAsset) {
  await ElMessageBox.confirm(
    t('admin.material.deleteConfirm', { name: item.name }),
    t('admin.material.deleteTitle'),
    { confirmButtonText: t('admin.common.delete'), type: 'warning' }
  );
  await deleteMaterial(item.id);
  ElMessage.success(t('admin.material.deleted'));
  if (items.value.length === 1 && pager.current > 1) pager.current -= 1;
  await loadData();
}

function resetFilters() {
  Object.assign(filters, {
    category: '',
    createdRange: [],
    fileName: '',
    mediaType: '',
    name: '',
    tags: '',
  });
  pager.current = 1;
  void loadData();
}

function search() {
  pager.current = 1;
  void loadData();
}

function fileSize(value: number) {
  const bytes = Number(value || 0);
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

</script>

<template>
  <Page>
    <div class="material-page">
      <header class="material-heading">
        <div>
          <small>{{ t('admin.material.eyebrow') }}</small>
          <h1>{{ t('admin.material.title') }}</h1>
          <p>{{ t('admin.material.description') }}</p>
        </div>
        <span class="material-count">
          {{ t('admin.material.count', { count: pager.total }) }}
        </span>
      </header>

      <ElCard class="exp-card exp-search-card material-search-card" shadow="never">
        <div class="exp-filter-row">
          <ElDatePicker
            v-model="filters.createdRange"
            class="exp-time-filter"
            clearable
            :end-placeholder="t('admin.material.uploadEndTime')"
            :start-placeholder="t('admin.material.uploadStartTime')"
            type="datetimerange"
            value-format="YYYY-MM-DDTHH:mm:ss"
          />
          <ElInput
            v-model="filters.name"
            class="exp-filter"
            clearable
            :placeholder="t('admin.material.name')"
            @keyup.enter="search"
          />
          <ElInput
            v-model="filters.tags"
            class="exp-filter"
            clearable
            :placeholder="t('admin.material.tags')"
            @keyup.enter="search"
          />
          <ElInput
            v-model="filters.fileName"
            class="exp-filter"
            clearable
            :placeholder="t('admin.material.fileName')"
            @keyup.enter="search"
          />
          <MaterialCategorySelect v-model="filters.category" class="exp-filter" />
          <ElSelect
            v-model="filters.mediaType"
            class="exp-filter"
            clearable
            :placeholder="t('admin.material.type')"
          >
            <ElOption :label="t('admin.material.image')" value="IMAGE" />
            <ElOption :label="t('admin.material.video')" value="VIDEO" />
          </ElSelect>
          <ElButton type="primary" @click="search">
            {{ t('admin.common.search') }}
          </ElButton>
          <ElButton @click="resetFilters">
            {{ t('admin.common.reset') }}
          </ElButton>
          <ElUpload
            accept="image/*,video/mp4,video/webm,video/quicktime"
            class="material-upload-action"
            multiple
            :disabled="uploading"
            :http-request="handleUpload"
            :show-file-list="false"
          >
            <ElButton
              v-access:code="permissionCodes(SYSTEM_PERMISSIONS.material.create)"
              :loading="uploading"
              type="primary"
            >
              <IconifyIcon icon="lucide:cloud-upload" />
              {{ t('admin.material.upload') }}
            </ElButton>
          </ElUpload>
        </div>
      </ElCard>

      <ElCard class="exp-card exp-table-card material-results" shadow="never">
        <div v-loading="loading" class="material-grid">
          <ElCard
            v-for="item in items"
            :key="item.id"
            :body-style="{ padding: '0' }"
            class="material-card"
            shadow="hover"
          >
            <div class="media-preview">
              <video
                v-if="item.mediaType === 'VIDEO'"
                controls
                preload="metadata"
                :src="item.fileUrl"
              ></video>
              <ElImage v-else fit="cover" :preview-src-list="[item.fileUrl]" :src="item.fileUrl" />
              <span>
                {{
                  t(item.mediaType === 'VIDEO' ? 'admin.material.video' : 'admin.material.image')
                }}
              </span>
            </div>
            <div class="material-copy">
              <strong :title="item.name">{{ item.name }}</strong>
              <small>
                {{ fileSize(item.fileSize) }}
                <template v-if="item.width"> · {{ item.width }}×{{ item.height }}</template>
              </small>
              <div class="material-meta">
                <span v-if="item.category">{{ materialCategoryLabel(item.category, t) }}</span>
                <span v-if="item.tags">{{ item.tags }}</span>
              </div>
              <div class="material-actions">
                <ElButton
                  v-access:code="permissionCodes(SYSTEM_PERMISSIONS.material.update)"
                  link
                  type="primary"
                  @click="openEdit(item)"
                >
                  {{ t('admin.common.edit') }}
                </ElButton>
                <ElButton
                  v-access:code="permissionCodes(SYSTEM_PERMISSIONS.material.delete)"
                  link
                  type="danger"
                  @click="removeItem(item)"
                >
                  {{ t('admin.common.delete') }}
                </ElButton>
              </div>
            </div>
          </ElCard>
        </div>

        <ElEmpty v-if="!loading && !items.length" :description="t('admin.material.empty')" />
        <ElPagination
          v-model:current-page="pager.current"
          v-model:page-size="pager.size"
          background
          class="pagination"
          layout="total, sizes, prev, pager, next"
          :page-sizes="[12, 24, 48, 96]"
          :total="pager.total"
          @current-change="loadData"
          @size-change="loadData"
        />
      </ElCard>
    </div>

    <ElDialog
      v-model="editVisible"
      append-to-body
      :title="t('admin.material.editTitle')"
      width="520px"
    >
      <ElForm label-position="top" :model="editForm">
        <ElFormItem :label="t('admin.material.name')"
          ><ElInput v-model="editForm.name"
        /></ElFormItem>
        <ElFormItem :label="t('admin.material.category')"
          ><MaterialCategorySelect v-model="editForm.category" allow-create class="w-full" /></ElFormItem>
        <ElFormItem :label="t('admin.material.tags')"
          ><ElInput v-model="editForm.tags" :placeholder="t('admin.material.tagsPlaceholder')"
        /></ElFormItem>
        <ElFormItem :label="t('admin.common.remarks')"
          ><ElInput v-model="editForm.remark" :rows="3" type="textarea"
        /></ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="editVisible = false">
          {{ t('admin.common.cancel') }}
        </ElButton>
        <ElButton type="primary" @click="saveEdit">
          {{ t('admin.common.save') }}
        </ElButton>
      </template>
    </ElDialog>
  </Page>
</template>

<style scoped>
.material-results :deep(.el-card__body) {
  padding: 20px;
}
.material-page {
  width: 100%;
  padding: 24px;
}
.material-heading {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 20px;
}
.material-heading small {
  font-size: 12px;
  font-weight: 800;
  color: var(--el-color-primary);
  letter-spacing: 0.16em;
}
.material-heading h1 {
  margin: 6px 0 4px;
  font-size: 26px;
}
.material-heading p {
  margin: 0;
  color: var(--el-text-color-secondary);
}
.material-count {
  padding: 7px 12px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-radius: 999px;
}
.material-search-card {
  margin-bottom: 22px;
}
.material-upload-action {
  flex: 0 0 auto;
}
.material-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 240px), 1fr));
  gap: 18px;
  min-height: 160px;
}
.material-card {
  overflow: hidden;
  border-radius: 14px;
}
.media-preview {
  position: relative;
  height: 180px;
  overflow: hidden;
  background: var(--el-fill-color-light);
}
.media-preview video,
.media-preview :deep(.el-image) {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.media-preview > span {
  position: absolute;
  top: 10px;
  right: 10px;
  padding: 4px 8px;
  font-size: 11px;
  color: #fff;
  background: rgb(15 23 42 / 72%);
  border-radius: 999px;
}
.material-copy {
  padding: 15px;
}
.material-copy > strong {
  display: block;
  overflow: hidden;
  font-size: 15px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.material-copy > small {
  display: block;
  margin-top: 6px;
  color: var(--el-text-color-secondary);
}
.material-meta {
  display: flex;
  gap: 6px;
  min-height: 23px;
  margin-top: 10px;
  overflow: hidden;
}
.material-meta span {
  padding: 3px 7px;
  overflow: hidden;
  font-size: 11px;
  color: var(--el-text-color-regular);
  text-overflow: ellipsis;
  white-space: nowrap;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}
.material-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 9px;
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 8px;
}
.pagination {
  justify-content: flex-end;
  margin-top: 24px;
}
@media (max-width: 640px) {
  .material-page {
    padding: 14px;
  }
  .material-heading {
    display: block;
  }
  .material-count {
    display: inline-block;
    margin-top: 12px;
  }
  .material-upload-action {
    flex: 1 1 100%;
  }
  .material-upload-action,
  .material-upload-action :deep(.el-upload),
  .material-upload-action .el-button {
    width: 100%;
  }
}
</style>
