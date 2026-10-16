<script setup lang="ts">
import { computed, ref, watch } from 'vue';

import { useI18n } from '@vben/locales';
import { useAccess } from '@vben/access';

import Editor from '@tinymce/tinymce-vue';

import { uploadMaterial, type MaterialAsset } from '#/api/material';
import MaterialImageDialog from '#/components/business/MaterialImageDialog.vue';
import { MATERIAL_CATEGORIES } from '#/components/business/materialCategories';

const props = withDefaults(
  defineProps<{
    bindHtml?: string;
    disabled?: boolean;
    height?: number;
  }>(),
  {
    bindHtml: '',
    disabled: false,
    height: 300,
  },
);

const emit = defineEmits<{
  'update:bindHtml': [value: string];
}>();

const { locale, t } = useI18n();
const { hasAccessByCodes, hasAccessByRoles } = useAccess();
const canUpload = computed(() => hasAccessByCodes(['60501','super']) || hasAccessByRoles(['60501','super']));
const canBrowse = computed(() => hasAccessByCodes(['60500','super']) || hasAccessByRoles(['60500','super']));
const materialVisible = ref(false);
let imageCallback: ((url: string, options: { alt: string }) => void) | undefined;
function selectImage(item: MaterialAsset) {
  const callback = imageCallback;
  imageCallback = undefined;
  if (canBrowse.value && !props.disabled) callback?.(item.fileUrl, { alt: item.name });
}
const appBaseUrl = import.meta.env.BASE_URL;
const tinymceScriptSrc = `${appBaseUrl}tinymce/tinymce.min.js`;

const tinymceLanguageMap: Record<string, string> = {
  ar: 'ar',
  de: 'de',
  en: 'en',
  'en-us': 'en',
  es: 'es',
  fr: 'fr',
  ja: 'ja',
  ko: 'ko',
  pt: 'pt',
  'pt-br': 'pt_BR',
  ru: 'ru',
  'zh-cn': 'zh_CN',
  zh: 'zh_CN',
};

const editorHtml = ref(props.bindHtml);

async function imageUploadHandler(blobInfo: any) {
  if (!canUpload.value) throw new Error(t('admin.compute.hint.materialUploadPermission'));
  const blob = blobInfo.blob();
  const file = new File(
    [blob],
    blobInfo.filename?.() || 'tinymce-image.png',
    {
      type: blob.type || 'image/png',
    },
  );
  const material = await uploadMaterial(file, {
    category: MATERIAL_CATEGORIES.richText,
    name: file.name,
    tags: 'tinymce',
  });

  if (!material.fileUrl) {
    throw new Error(t('admin.editor.missingUploadUrl'));
  }

  return material.fileUrl;
}

const editorInit = computed(() => {
  const normalizedLocale = String(locale.value)
    .trim()
    .replaceAll('_', '-')
    .toLowerCase();
  const baseLocale = normalizedLocale.split('-')[0] || '';
  const language =
    tinymceLanguageMap[normalizedLocale] ||
    tinymceLanguageMap[baseLocale] ||
    'en';

  return {
    branding: false,
    content_style:
      'body { font-family: Helvetica, Arial, sans-serif; font-size: 14px; line-height: 1.7; }',
    height: props.height,
    images_upload_handler: canUpload.value ? imageUploadHandler : undefined,
    automatic_uploads: canUpload.value,
    paste_data_images: canUpload.value,
    image_uploadtab: canUpload.value,
    file_picker_types: 'image',
    file_picker_callback: canBrowse.value ? (callback: (url: string, options: { alt: string }) => void) => {
      if (props.disabled) return;
      imageCallback = callback;
      materialVisible.value = true;
    } : undefined,
    language,
    language_url:
      language === 'en'
        ? undefined
        : `${appBaseUrl}tinymce/langs/${language}.js`,
    license_key: 'gpl',
    menubar: false,
    plugins: 'link lists image code table wordcount fullscreen',
    promotion: false,
    relative_urls: false,
    remove_script_host: false,
    toolbar:
      'code undo redo | blocks fontfamily fontsize | forecolor backcolor bold italic underline strikethrough | alignleft aligncenter alignright | link image | numlist bullist table | fullscreen',
  };
});

watch(editorHtml, (value) => {
  emit('update:bindHtml', value || '');
});

watch(
  () => props.bindHtml,
  (value) => {
    if ((value || '') !== editorHtml.value) {
      editorHtml.value = value || '';
    }
  },
);
</script>

<template>
  <div class="exp-tinymce" :class="{ 'is-disabled': disabled }">
    <Editor
      v-model="editorHtml"
      :disabled="disabled"
      :init="editorInit"
      :tinymce-script-src="tinymceScriptSrc"
    />
    <MaterialImageDialog v-model="materialVisible" @select="selectImage" />
  </div>
</template>

<style>
.exp-tinymce {
  width: 100%;
  overflow: hidden;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
}

.exp-tinymce:focus-within {
  border-color: var(--el-color-primary);
}

.exp-tinymce.is-disabled {
  opacity: 0.72;
}

.exp-tinymce .tox-tinymce {
  border: 0 !important;
}

.tox-tinymce-aux {
  z-index: 3001 !important;
}
</style>
