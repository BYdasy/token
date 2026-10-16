<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from '@vben/locales';
import { ElAlert, ElButton, ElForm, ElFormItem, ElInput, ElMessage, ElOption, ElRadioButton, ElRadioGroup, ElSelect, ElSwitch } from 'element-plus';
import { fetchNotificationTargets, sendNotification, type NotificationSeverity, type NotificationTargetType } from '#/api/operations';
import { fetchTelegramSendOptions, sendTelegramNotification, type TelegramSendOptions } from '#/api/telegram-notification';
import TinymceMin from '#/views/Tinymcemin.vue';
import TelegramTemplateEditor from './components/TelegramTemplateEditor.vue';
import { hasTranslationContent } from '#/utils/translation-content';

const emit = defineEmits<{ sent: [] }>();
const { t } = useI18n();
const n = (key: string) => t(`admin.compute.v51Notifications.${key}`);
const kind = ref<'INTERNAL' | 'TELEGRAM'>('INTERNAL');
const loading = ref(false), sending = ref(false), failed = ref(false);
const accounts = ref<{uid:number;username:string}[]>([]), roles = ref<{id:number;name:string}[]>([]);
const options = ref<TelegramSendOptions>({ channels: [], bindings: [], templates: [] });
const internal = reactive({ title:'',content:'',popup:true,severity:'INFO' as NotificationSeverity,targetType:'ALL' as NotificationTargetType,targetIds:[] as number[] });
const telegram = reactive({ channelId:undefined as number|undefined,targetMode:'ALL' as 'ALL'|'SELECTED',bindingIds:[] as number[],templateCode:'',title:'',content:'',variables:{} as Record<string,string> });
const activeTemplate = computed(() => options.value.templates.find(item => item.code === telegram.templateCode));
const bindings = computed(() => options.value.bindings.filter(item => item.channelId === telegram.channelId));
const severities: NotificationSeverity[] = ['INFO','SUCCESS','WARNING','ERROR'];
const severityType = computed(() => internal.severity === 'ERROR' ? 'error' : internal.severity === 'SUCCESS' ? 'success' : internal.severity === 'WARNING' ? 'warning' : 'info');
let loadVersion = 0;
async function load() {
  const run = ++loadVersion, channel = kind.value;
  loading.value = true; failed.value = false;
  try {
    if (channel === 'INTERNAL') { const result = await fetchNotificationTargets(); if (run === loadVersion) { accounts.value=result.accounts;roles.value=result.roles; } }
    else { const result = await fetchTelegramSendOptions(); if (run === loadVersion) options.value=result; }
  } catch { if (run === loadVersion) failed.value=true; }
  finally { if (run === loadVersion) loading.value=false; }
}
async function send() {
  if (sending.value || loading.value || failed.value) return;
  if (kind.value === 'INTERNAL') {
    if (!internal.title.trim() || !hasTranslationContent(internal.content) || (internal.targetType !== 'ALL' && !internal.targetIds.length)) { ElMessage.warning(n('sendRequired'));return; }
  } else {
    if (!telegram.channelId || (telegram.targetMode === 'SELECTED' ? !telegram.bindingIds.length : !bindings.value.length)
      || (telegram.templateCode ? !activeTemplate.value || activeTemplate.value.variables.some(key => !telegram.variables[key]?.trim()) : !telegram.title.trim() || !hasTranslationContent(telegram.content))) { ElMessage.warning(n('sendRequired'));return; }
  }
  sending.value=true;
  try {
    if(kind.value==='INTERNAL') await sendNotification({...internal});
    else await sendTelegramNotification({ ...telegram,channelId:telegram.channelId!,templateCode:telegram.templateCode || undefined,bindingIds:telegram.targetMode==='ALL'?[]:telegram.bindingIds,
      variables:Object.fromEntries((activeTemplate.value?.variables ?? []).map(key => [key,telegram.variables[key] ?? ''])) });
    ElMessage.success(t('admin.notification.sent')); emit('sent');
  } finally { sending.value=false; }
}
watch(kind,load);
watch(() => internal.targetType,() => { internal.targetIds=[]; });
watch(() => telegram.channelId,() => { telegram.bindingIds=[]; });
watch(() => telegram.templateCode,() => { telegram.variables={}; });
onMounted(load);
</script>
<template>
  <div class="notification-send" v-loading="loading">
    <ElRadioGroup v-model="kind" :disabled="sending" :aria-label="n('deliveryChannel')" class="send-channel"><ElRadioButton value="INTERNAL">{{ n('internal') }}</ElRadioButton><ElRadioButton value="TELEGRAM">Telegram</ElRadioButton></ElRadioGroup>
    <p class="send-help">{{ n(kind === 'INTERNAL' ? 'internalHelp' : 'telegramHelp') }}</p>
    <ElButton v-if="failed" @click="load">{{ t('admin.compute.v12sound.retry') }}</ElButton>
    <ElForm v-else label-position="top" :disabled="loading || sending" class="send-form">
      <template v-if="kind === 'INTERNAL'">
        <ElFormItem :label="t('admin.notification.targetType')"><div class="send-targets"><ElSelect v-model="internal.targetType"><ElOption :label="t('admin.notification.allUsers')" value="ALL"/><ElOption :label="t('admin.notification.targetUsers')" value="USER"/><ElOption :label="t('admin.notification.targetRoles')" value="ROLE"/></ElSelect><ElSelect v-if="internal.targetType !== 'ALL'" v-model="internal.targetIds" filterable multiple :aria-label="t('admin.notification.targetType')"><ElOption v-for="item in internal.targetType === 'USER' ? accounts.map(a=>({id:a.uid,name:a.username})) : roles" :key="item.id" :label="item.name" :value="item.id" /></ElSelect></div></ElFormItem>
        <ElFormItem :label="t('admin.notification.title')"><ElInput v-model="internal.title" maxlength="120"/></ElFormItem>
        <ElFormItem :label="t('admin.notification.content')"><TinymceMin v-model:bind-html="internal.content" /></ElFormItem>
        <div class="popup-options"><ElFormItem :label="t('admin.notification.popupAlso')"><ElSwitch v-model="internal.popup" :aria-label="t('admin.notification.popupAlso')" /></ElFormItem><ElFormItem v-if="internal.popup" :label="n('popupStyle')"><ElRadioGroup v-model="internal.severity"><ElRadioButton v-for="severity in severities" :key="severity" :value="severity">{{ n(`severity.${severity}`) }}</ElRadioButton></ElRadioGroup></ElFormItem></div>
        <ElAlert v-if="internal.popup" class="popup-preview" :title="internal.title || n('popupPreview')" :description="n(`severity.${internal.severity}`)" :type="severityType" show-icon :closable="false" />
      </template>
      <template v-else>
        <ElFormItem :label="n('channel')"><ElSelect v-model="telegram.channelId" filterable><ElOption v-for="item in options.channels" :key="item.id" :label="item.name" :value="item.id" /></ElSelect></ElFormItem>
        <ElFormItem :label="n('recipients')"><ElRadioGroup v-model="telegram.targetMode"><ElRadioButton value="ALL">{{ n('allBindings') }}</ElRadioButton><ElRadioButton value="SELECTED">{{ n('selectedBindings') }}</ElRadioButton></ElRadioGroup></ElFormItem>
        <ElFormItem v-if="telegram.targetMode === 'SELECTED'" :label="n('bindingSelect')"><ElSelect v-model="telegram.bindingIds" filterable multiple :disabled="!telegram.channelId"><ElOption v-for="item in bindings" :key="item.id" :label="`${item.name} · ${item.chatId}`" :value="item.id" /></ElSelect></ElFormItem>
        <ElAlert v-if="telegram.channelId && !bindings.length" :title="n('noBindings')" type="info" :closable="false" class="popup-preview" />
        <ElFormItem :label="n('template')"><ElSelect v-model="telegram.templateCode" filterable clearable :placeholder="n('customContent')"><ElOption v-for="item in options.templates" :key="item.code" :value="item.code" :label="`${item.shortCode} · ${item.name}`" /></ElSelect></ElFormItem>
        <template v-if="activeTemplate"><ElFormItem v-for="variable in activeTemplate.variables" :key="variable" :label="variable"><ElInput v-model="telegram.variables[variable]" maxlength="2000" /></ElFormItem></template>
        <template v-else><ElFormItem :label="t('admin.notification.title')"><ElInput v-model="telegram.title" maxlength="120" /></ElFormItem><ElFormItem :label="t('admin.notification.content')"><TelegramTemplateEditor v-model="telegram.content" :disabled="sending" /></ElFormItem></template>
      </template>
      <ElButton type="primary" :loading="sending" @click="send">{{ t('admin.notification.sendNow') }}</ElButton>
    </ElForm>
  </div>
</template>
<style scoped>
.notification-send { max-width: 1040px; }
.send-channel { margin-bottom:16px; }
.send-help { color:var(--exp-text-secondary); line-height:1.65; margin:0 0 24px; }
.send-targets { display:grid; grid-template-columns:200px minmax(0,1fr); gap:12px; width:100%; }
.popup-options { display:flex; flex-wrap:wrap; gap:0 32px; }
.popup-preview { margin-bottom:24px; }
@media(max-width:700px){.send-targets{grid-template-columns:minmax(0,1fr);}.send-channel{max-width:100%;}.popup-options :deep(.el-radio-group){flex-wrap:wrap;}}
</style>
