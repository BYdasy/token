<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { useI18n } from '@vben/locales';
import { ElAlert, ElButton, ElDialog, ElForm, ElFormItem, ElInput, ElMessage, ElOption, ElSelect } from 'element-plus';
import type { NotificationTemplate } from '#/api/operations';
import { fetchTelegramSendOptions, previewTelegramTemplate, testTelegramTemplate, type TelegramSendOptions, type TelegramTemplatePreview } from '#/api/telegram-notification';
import { chineseLanguageOption } from '#/components/business/businessLanguages';
import { sanitizeNotificationHtml } from '#/utils/notification-html';

const props = defineProps<{ modelValue: boolean; template?: NotificationTemplate }>();
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>();
const { t } = useI18n();
const n = (key: string) => t(`admin.notification.templateTest.${key}`);
const loading = ref(false), previewLoading = ref(false), sending = ref(false);
const loadFailed = ref(false), previewFailed = ref(false), sendFailed = ref(false);
const options = ref<TelegramSendOptions>({ channels: [], bindings: [], templates: [] });
const preview = ref<TelegramTemplatePreview>();
const previewSignature = ref('');
const form = reactive({ channelId: undefined as number | undefined, bindingId: undefined as number | undefined, language: 'zh', variables: {} as Record<string, string> });
const variableNames = ref<string[]>([]);
const languages = computed(() => {
  const rows = props.template?.enabledLanguages?.length ? props.template.enabledLanguages : [{ lang: 'zh' }, { lang: 'en' }];
  return rows.map(row => ({ ...row, label: chineseLanguageOption({ value: row.lang, label: row.lang, ...row }).label }));
});
const bindings = computed(() => options.value.bindings.filter(item => item.channelId === form.channelId && Number(item.enabled) === 1));
const payload = () => ({ templateId: Number(props.template?.id), language: form.language, variables: { ...form.variables } });
const previewCurrent = computed(() => Boolean(preview.value) && previewSignature.value === JSON.stringify(payload()));
const canSend = computed(() => !loading.value && !previewLoading.value && !loadFailed.value && !sending.value
  && previewCurrent.value && !!form.channelId && bindings.value.some(item => item.id === form.bindingId)
  && variableNames.value.every(key => Boolean(form.variables[key]?.trim())));
let loadGeneration = 0, previewGeneration = 0;

function defaultLanguage() {
  const rows = languages.value;
  const normalized = (language: string) => language.toLowerCase().replaceAll('_', '-');
  return rows.find(item => normalized(item.lang) === 'zh')?.lang
    || rows.find(item => normalized(item.lang) === 'zh-cn')?.lang
    || rows.find(item => normalized(item.lang).startsWith('zh-'))?.lang
    || rows.find(item => normalized(item.lang) === 'en')?.lang
    || rows[0]?.lang || 'zh';
}
function sampleValue(key: string) {
  if (/amount|balance|threshold|fee|token/i.test(key)) return '100.00';
  if (key === 'minutes') return '30';
  if (/username|memberName/i.test(key)) return 'test_user';
  if (/currency/i.test(key)) return 'USDT';
  if (/uid|memberId/i.test(key)) return '10001';
  if (/order/i.test(key)) return 'TEST-ORDER';
  return 'TEST';
}
function invalidatePreview() {
  ++previewGeneration;
  previewLoading.value = false;
  previewSignature.value = '';
  sendFailed.value = false;
}
async function refreshPreview() {
  if (!props.modelValue || !props.template?.id || sending.value) return;
  const run = ++previewGeneration;
  previewLoading.value = true;
  previewFailed.value = false;
  previewSignature.value = '';
  try {
    let request = payload();
    let result = await previewTelegramTemplate(request);
    if (run !== previewGeneration || !props.modelValue) return;
    const variables = Object.fromEntries(result.variables.map(key => [key, form.variables[key] ?? sampleValue(key)]));
    variableNames.value = result.variables;
    if (JSON.stringify(variables) !== JSON.stringify(form.variables)) {
      form.variables = variables;
      request = payload();
      result = await previewTelegramTemplate(request);
      if (run !== previewGeneration || !props.modelValue) return;
    }
    preview.value = result;
    previewSignature.value = JSON.stringify(request);
  } catch {
    if (run === previewGeneration) previewFailed.value = true;
  } finally {
    if (run === previewGeneration) previewLoading.value = false;
  }
}
async function load() {
  const run = ++loadGeneration;
  loading.value = true;
  loadFailed.value = false;
  try {
    const result = await fetchTelegramSendOptions();
    if (run !== loadGeneration || !props.modelValue) return;
    options.value = result;
    await refreshPreview();
  } catch {
    if (run === loadGeneration) loadFailed.value = true;
  } finally {
    if (run === loadGeneration) loading.value = false;
  }
}
async function send() {
  if (!canSend.value) return;
  sending.value = true;
  sendFailed.value = false;
  try {
    await testTelegramTemplate({ template: payload(), channelId: form.channelId!, bindingId: form.bindingId! });
    ElMessage.success(n('sent'));
    emit('update:modelValue', false);
  } catch {
    sendFailed.value = true;
  } finally {
    sending.value = false;
  }
}
watch(() => form.channelId, () => { form.bindingId = undefined; });
watch(() => props.modelValue, (open) => {
  ++loadGeneration;
  invalidatePreview();
  if (!open) return;
  preview.value = undefined;
  variableNames.value = [];
  Object.assign(form, { channelId: undefined, bindingId: undefined,
    language: defaultLanguage(), variables: {} });
  void load();
}, { immediate: true });
onBeforeUnmount(() => { ++loadGeneration; ++previewGeneration; });
</script>

<template>
  <ElDialog :model-value="modelValue" :title="n('title')" width="min(760px, calc(100vw - 32px))" append-to-body destroy-on-close
    :close-on-click-modal="!sending" :close-on-press-escape="!sending" :show-close="!sending" @update:model-value="!sending && emit('update:modelValue', $event)">
    <div v-loading="loading" class="template-test">
      <p class="template-test-name">{{ template?.shortCode || `T${template?.id}` }} · {{ template?.name }}</p>
      <p class="template-test-help">{{ n('help') }}</p>
      <template v-if="loadFailed">
        <ElAlert :title="n('loadFailed')" type="error" :closable="false" show-icon />
        <ElButton class="template-test-retry" @click="load">{{ t('admin.compute.v12sound.retry') }}</ElButton>
      </template>
      <ElForm v-else label-position="top" :disabled="loading || sending">
        <div class="template-test-targets">
          <ElFormItem :label="t('admin.compute.v51Notifications.channel')">
            <ElSelect v-model="form.channelId" filterable clearable :placeholder="n('chooseChannel')"><ElOption v-for="item in options.channels" :key="item.id" :value="item.id" :label="item.name" /></ElSelect>
          </ElFormItem>
          <ElFormItem :label="n('recipient')">
            <ElSelect v-model="form.bindingId" filterable clearable :disabled="!form.channelId" :placeholder="n('chooseRecipient')"><ElOption v-for="item in bindings" :key="item.id" :value="item.id" :label="`${item.name} · ${item.chatId}`" /></ElSelect>
          </ElFormItem>
        </div>
        <ElAlert v-if="!loading && (!options.channels.length || form.channelId && !bindings.length)" :title="n('noTargets')" type="info" :closable="false" show-icon class="template-test-notice" />
        <ElFormItem :label="n('language')"><ElSelect v-model="form.language" filterable @change="invalidatePreview(); refreshPreview()"><ElOption v-for="item in languages" :key="item.lang" :label="item.label" :value="item.lang" /></ElSelect></ElFormItem>
        <div v-if="variableNames.length" class="template-test-variables">
          <ElFormItem v-for="variable in variableNames" :key="variable" :label="variable"><ElInput v-model="form.variables[variable]" maxlength="2000" @input="invalidatePreview" /></ElFormItem>
        </div>
        <div class="template-test-preview-heading"><span>{{ n('preview') }}</span><ElButton :loading="previewLoading" @click="refreshPreview">{{ n('refreshPreview') }}</ElButton></div>
        <ElAlert v-if="previewFailed" :title="n('previewFailed')" type="error" :closable="false" show-icon class="template-test-notice" />
        <section v-if="preview" class="template-test-preview" :aria-label="n('preview')" aria-live="polite">
          <strong class="template-test-preview-title">{{ preview.title }}</strong>
          <div class="template-test-preview-content" v-html="sanitizeNotificationHtml(preview.content)" />
        </section>
        <p v-if="preview && !previewCurrent && !previewLoading" class="template-test-help" role="status">{{ n('previewStale') }}</p>
        <ElAlert v-if="sendFailed" :title="n('sendFailed')" type="warning" :closable="false" show-icon class="template-test-notice" />
      </ElForm>
    </div>
    <template #footer><ElButton :disabled="sending" @click="emit('update:modelValue', false)">{{ t('admin.common.cancel') }}</ElButton><ElButton type="primary" :disabled="!canSend" :loading="sending" @click="send">{{ n('sendOne') }}</ElButton></template>
  </ElDialog>
</template>

<style scoped>
.template-test { min-height: 180px; }
.template-test-name { margin: 0 0 8px; font-weight: 600; overflow-wrap: anywhere; }
.template-test-help { margin: 0 0 20px; color: var(--exp-text-secondary); line-height: 1.6; }
.template-test-targets, .template-test-variables { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 20px; }
.template-test :deep(.el-form-item__label) { height: auto; overflow-wrap: anywhere; }
.template-test :deep(.el-select) { width: 100%; }
.template-test-preview-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 8px 0 12px; font-weight: 600; }
.template-test-preview { max-height: 300px; overflow: auto; padding: 16px; border: 1px solid var(--el-border-color); border-radius: 12px; line-height: 1.65; overflow-wrap: anywhere; }
.template-test-preview-title { display: block; margin-bottom: 8px; white-space: pre-wrap; }
.template-test-preview-content { white-space: pre-wrap; }
.template-test-notice { margin: 0 0 16px; }
.template-test-retry { margin-top: 12px; }
@media (max-width: 600px) { .template-test-targets, .template-test-variables { grid-template-columns: minmax(0, 1fr); } }
</style>
