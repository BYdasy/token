<script setup lang="ts">
import { computed } from 'vue';
import { useAccess } from '@vben/access';
import { useI18n } from '@vben/locales';
import { ElAlert, ElImage } from 'element-plus';
import { MATERIAL_CATEGORIES } from './materialCategories';
import AvatarPicker from '#/views/system/account/components/AvatarPicker.vue';
import { imageSource } from '#/utils/image-source';
const props = withDefaults(defineProps<{ modelValue?: string; disabled?: boolean }>(), {
  modelValue: '',
  disabled: false,
});
const emit = defineEmits<{ 'update:modelValue': [value: string] }>();
const { hasAccessByCodes, hasAccessByRoles } = useAccess();
const { t } = useI18n();
const allowed = (code: string) =>
  hasAccessByCodes([code, 'super']) || hasAccessByRoles([code, 'super']);
const upload = computed(() => allowed('60501'));
const library = computed(() => allowed('60500'));
const preview = computed(() => imageSource(props.modelValue));
</script>
<template>
  <ElImage
    v-if="disabled && preview"
    :src="preview"
    fit="contain"
    class="business-image"
  />
  <template v-else-if="!disabled">
    <AvatarPicker
      :model-value="modelValue"
      :category="MATERIAL_CATEGORIES.general"
      :allow-upload="upload"
      :allow-library="library"
      @update:model-value="emit('update:modelValue', $event)"
    />
    <ElAlert
      v-if="!upload || !library"
      :title="t('admin.compute.contentV5.imagePermission')"
      type="info"
      :closable="false"
    />
  </template>
</template>
<style scoped>
.business-image {
  width: 160px;
  height: 120px;
}
</style>
