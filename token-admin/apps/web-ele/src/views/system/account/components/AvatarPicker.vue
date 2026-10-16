<script lang="ts" setup>
import type { UploadRequestOptions } from 'element-plus';

import type { MaterialAsset } from '#/api/material';

import { computed, ref } from 'vue';
import { useI18n } from '@vben/locales';

import { IconifyIcon } from '@vben/icons';

import {
  ElButton,
  ElImage,
  ElMessage,
  ElUpload,
} from 'element-plus';

import { uploadMaterial } from '#/api/material';
import MaterialImageDialog from '#/components/business/MaterialImageDialog.vue';
import { MATERIAL_CATEGORIES } from '#/components/business/materialCategories';
import { imageSource } from '#/utils/image-source';

const { t } = useI18n();

const props = withDefaults(
  defineProps<{
    category?: string;
    disabled?: boolean;
    allowUpload?: boolean;
    allowLibrary?: boolean;
    modelValue?: string;
  }>(),
  {
    category: MATERIAL_CATEGORIES.avatar,
    disabled: false,
    allowUpload: true,
    allowLibrary: true,
    modelValue: '',
  },
);

const emit = defineEmits<{
  'update:modelValue': [value: string];
}>();

const uploading = ref(false);
const pickerVisible = ref(false);
const preview = computed(() => imageSource(props.modelValue));
const hasAvatar = computed(() => Boolean(props.modelValue?.trim()));

async function handleUpload(options: UploadRequestOptions) {
  if (props.disabled || !props.allowUpload) return;
  const file = options.file as File;
  if (!file.type.startsWith('image/')) {
    ElMessage.warning(t('admin.avatar.imageOnly'));
    throw new Error(t('admin.avatar.imageOnlyError'));
  }

  uploading.value = true;
  try {
    const material = await uploadMaterial(file, {
      category: props.category,
      tags: props.category,
    });
    emit('update:modelValue', material.fileUrl);
    ElMessage.success(t('admin.avatar.uploaded'));
    return material;
  } finally {
    uploading.value = false;
  }
}

function openMaterialPicker() {
  if (props.disabled || !props.allowLibrary) return;
  pickerVisible.value = true;
}

function selectMaterial(item: MaterialAsset) {
  if (props.disabled || !props.allowLibrary) return;
  emit('update:modelValue', item.fileUrl);
  pickerVisible.value = false;
  ElMessage.success(t('admin.avatar.selected'));
}

function clearAvatar() {
  emit('update:modelValue', '');
}

</script>

<template>
  <div class="account-avatar-picker">
    <ElUpload
      accept="image/*"
      class="account-avatar-upload"
      drag
      :disabled="disabled || !allowUpload || uploading"
      :http-request="handleUpload"
      :show-file-list="false"
    >
      <div class="account-avatar-dropzone">
        <ElImage
          v-if="preview"
          class="account-avatar-preview"
          fit="cover"
          :src="preview"
        />
        <span v-else class="account-avatar-placeholder">
          <IconifyIcon icon="lucide:image-plus" />
        </span>
        <div class="account-avatar-copy">
          <strong>
            {{ t(uploading ? 'admin.avatar.uploading' : 'admin.avatar.uploadPrompt') }}
          </strong>
          <span>{{ t('admin.avatar.uploadHint') }}</span>
        </div>
      </div>
    </ElUpload>

    <div class="account-avatar-actions">
      <ElButton v-if="allowLibrary" :disabled="disabled" @click="openMaterialPicker">
        {{ t('admin.avatar.chooseFromLibrary') }}
      </ElButton>
      <ElButton :disabled="disabled || !hasAvatar" type="danger" plain @click="clearAvatar">
        {{ t('admin.avatar.clear') }}
      </ElButton>
    </div>

    <MaterialImageDialog v-model="pickerVisible" @select="selectMaterial" />
  </div>
</template>

<style scoped>
.account-avatar-picker {
  width: 100%;
}

.account-avatar-upload {
  width: 100%;
}

.account-avatar-upload :deep(.el-upload),
.account-avatar-upload :deep(.el-upload-dragger) {
  width: 100%;
}

.account-avatar-upload :deep(.el-upload-dragger) {
  padding: 14px 16px;
  background: var(--el-fill-color-lighter);
}

.account-avatar-dropzone {
  display: flex;
  gap: 14px;
  align-items: center;
  text-align: left;
}

.account-avatar-preview,
.account-avatar-placeholder {
  flex: none;
  width: 72px;
  height: 72px;
  border-radius: 14px;
}

.account-avatar-placeholder {
  display: grid;
  font-size: 26px;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  place-items: center;
}

.account-avatar-copy {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}

.account-avatar-copy strong {
  color: var(--el-text-color-primary);
}

.account-avatar-copy span {
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}

.account-avatar-actions {
  display: flex;
  gap: 10px;
  margin-top: 10px;
}

@media (max-width: 640px) {
  .account-avatar-dropzone {
    align-items: flex-start;
  }

  .account-avatar-actions {
    flex-wrap: wrap;
  }

}
</style>
