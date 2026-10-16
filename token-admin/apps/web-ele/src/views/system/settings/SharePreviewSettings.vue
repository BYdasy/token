<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { useAccess } from '@vben/access';
import { useI18n } from '@vben/locales';
import { ElButton, ElCard, ElForm, ElFormItem, ElInput, ElMessage } from 'element-plus';
import { fetchSiteSettings, updateSiteSettings } from '#/api/operations';
import BusinessImagePicker from '#/components/business/BusinessImagePicker.vue';
import { SYSTEM_PERMISSIONS } from '#/constants/permissions';
import { savedSharePreviewLinks } from './sharePreviewLinks';

const { t } = useI18n();
const { hasAccessByCodes, hasAccessByRoles } = useAccess();
const canEdit = computed(() => hasAccessByRoles(['super']) || hasAccessByCodes(['super', SYSTEM_PERMISSIONS.settings.update]));
const canRead = computed(() => canEdit.value || hasAccessByCodes([SYSTEM_PERMISSIONS.settings.read]));
const loading = ref(false), saving = ref(false), loaded = ref(false), loadFailed = ref(false);
const shareLink = ref('');
const previewLink = ref('');
function updateSavedLinks(data: typeof model) {
  const links = savedSharePreviewLinks(data);
  shareLink.value = links.shareLink;
  previewLink.value = links.previewLink;
}
const model = reactive({ userShareEntryUrl: '', userShareTitle: '', userShareDescription: '', userShareImage: '' });
let active = true;
onBeforeUnmount(() => { active = false; });
async function load() {
  if (!canRead.value || loading.value) return;
  loading.value = true; loadFailed.value = false;
  try {
    const data = await fetchSiteSettings();
    if (!active) return;
    model.userShareEntryUrl = data.userShareEntryUrl || '';
    model.userShareTitle = data.userShareTitle || '';
    model.userShareDescription = data.userShareDescription || '';
    model.userShareImage = data.userShareImage || '';
    updateSavedLinks(model);
    loaded.value = true;
  } catch { if (active) { loaded.value = false; loadFailed.value = true; } }
  finally { if (active) loading.value = false; }
}
async function save() {
  if (!loaded.value || !canEdit.value || saving.value) return;
  saving.value = true;
  try {
    const data = await updateSiteSettings({ ...model });
    if (active) {
      updateSavedLinks({ userShareEntryUrl: data.userShareEntryUrl || '', userShareTitle: data.userShareTitle || '', userShareDescription: data.userShareDescription || '', userShareImage: data.userShareImage || '' });
      ElMessage.success(t('admin.settings.saved'));
    }
  } finally { if (active) saving.value = false; }
}
async function copyLink() {
  try {
    await navigator.clipboard.writeText(shareLink.value);
    ElMessage.success(t('admin.sharePreview.copied'));
  } catch {
    ElMessage.warning(t('admin.sharePreview.copyManually'));
  }
}
onMounted(load);
</script>

<template>
  <ElCard v-if="canRead" v-loading="loading" class="exp-card mt-5" shadow="never">
    <template #header>{{ t('admin.sharePreview.title') }}</template>
    <ElButton v-if="loadFailed" @click="load">{{ t('admin.session.idleRetry') }}</ElButton>
    <ElForm v-else label-position="top" :disabled="!loaded || !canEdit || loading || saving">
      <ElFormItem :label="t('admin.sharePreview.entryUrl')"><ElInput v-model="model.userShareEntryUrl" maxlength="2048" placeholder="https://www.example.com/" /></ElFormItem>
      <div class="share-settings-grid">
        <section>
          <ElFormItem :label="t('admin.sharePreview.heading')"><ElInput v-model="model.userShareTitle" maxlength="120" show-word-limit /></ElFormItem>
          <ElFormItem :label="t('admin.sharePreview.description')"><ElInput v-model="model.userShareDescription" type="textarea" :rows="3" maxlength="300" show-word-limit /></ElFormItem>
        </section>
        <ElFormItem :label="t('admin.sharePreview.image')"><BusinessImagePicker v-model="model.userShareImage" /></ElFormItem>
      </div>
      <p class="share-help">{{ t('admin.sharePreview.v52.deploymentHelp') }}</p>
      <ElButton v-if="canEdit" type="primary" :loading="saving" @click="save">{{ t('admin.common.save') }}</ElButton>
    </ElForm>
    <ElInput v-if="loaded" class="share-link-input" :model-value="shareLink" readonly :aria-label="t('admin.sharePreview.copyLink')" @focus="($event.target as HTMLInputElement).select()" />
    <a v-if="loaded" class="share-preview-link" :href="previewLink" target="_blank" rel="noopener noreferrer">{{ t('admin.sharePreview.openSaved') }}</a>
    <ElButton class="share-link-button" :disabled="!loaded" @click="copyLink">{{ t('admin.sharePreview.copyLink') }}</ElButton>
  </ElCard>
</template>

<style scoped>
.share-settings-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 280px), 1fr)); gap: 24px; }
.share-settings-grid > * { min-width: 0; }
.share-help { margin-bottom: 16px; color: var(--el-text-color-secondary); line-height: 1.7; }
.share-link-button, .share-link-input { margin-top: 12px; }
.share-preview-link { display: inline-block; max-width: 100%; margin: 12px 16px 0 0; color: var(--el-color-primary); overflow-wrap: anywhere; }
@media (max-width: 720px) { .share-settings-grid { grid-template-columns: 1fr; gap: 0; } }
</style>
