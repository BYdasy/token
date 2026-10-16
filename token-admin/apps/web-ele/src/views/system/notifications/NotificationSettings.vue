<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useAccess } from '@vben/access';
import { ElAlert, ElButton, ElCard, ElForm, ElFormItem, ElInput, ElInputNumber, ElMessage, ElOption, ElSelect, ElSwitch } from 'element-plus';
import { useCompute } from '#/components/business/shared';
import { fetchNotificationRules, saveNotificationRules, type NotificationRuleKey, type NotificationRulesSettings } from '#/api/notification-rules';
import { fetchTelegramSendOptions, type TelegramSendOptions } from '#/api/telegram-notification';
import FinanceSoundSettings from '#/views/finance/FinanceSoundSettings.vue';

const { c } = useCompute();
const { hasAccessByCodes, hasAccessByRoles } = useAccess();
const editable = computed(() => data.value?.global !== false && (hasAccessByCodes(['super', '60102']) || hasAccessByRoles(['super'])));
const data = ref<NotificationRulesSettings>();
const targets = ref<TelegramSendOptions>({ channels: [], bindings: [], templates: [] });
const loading = ref(false), saving = ref(false), failed = ref(false), targetsFailed = ref(false);
const ruleKeys: NotificationRuleKey[] = ['largeDeposit', 'largeWithdrawal', 'withdrawalOverdue', 'energyLow'];
const severities = ['INFO', 'SUCCESS', 'WARNING', 'ERROR'];
async function loadTargets() {
  targetsFailed.value = false;
  try { targets.value = await fetchTelegramSendOptions(); } catch { targetsFailed.value = true; }
}
async function load() {
  loading.value = true; failed.value = false;
  try { data.value = await fetchNotificationRules(); if (data.value.global !== false) await loadTargets(); }
  catch { failed.value = true; } finally { loading.value = false; }
}
function channelChanged(key: NotificationRuleKey) {
  const rule = data.value?.rules[key]; if (rule) { rule.bindingIds = []; rule.channelId ||= null; }
}
async function save() {
  if (!data.value || !editable.value || saving.value) return;
  for (const key of ruleKeys) {
    const rule = data.value.rules[key];
    if (key === 'withdrawalOverdue' ? !Number.isInteger(rule.minutes) || Number(rule.minutes) < 1 || Number(rule.minutes) > 525600 : !/^\d{1,20}(?:\.\d{1,8})?$/.test(rule.threshold ?? '')) {
      ElMessage.warning(c('v51Notifications.invalidRule')); return;
    }
    if (rule.enabled && ((!rule.popup && !rule.channelId) || (rule.channelId && rule.targetMode === 'SELECTED' && !rule.bindingIds.length))) {
      ElMessage.warning(c('v51Notifications.deliveryRequired')); return;
    }
  }
  saving.value = true;
  try {
    const rules = Object.fromEntries(ruleKeys.map(key => [key, { ...data.value!.rules[key], bindingIds: data.value!.rules[key].targetMode === 'ALL' ? [] : data.value!.rules[key].bindingIds }])) as NotificationRulesSettings['rules'];
    data.value = await saveNotificationRules({ version: data.value.version, rules }); ElMessage.success(c('common.saved'));
  }
  finally { saving.value = false; }
}
onMounted(load);
</script>
<template>
  <div class="notification-settings">
    <FinanceSoundSettings />
    <ElCard class="exp-card" shadow="never" v-loading="loading">
      <template #header>{{ c('v51Notifications.automatic') }}</template>
      <p class="settings-help">{{ c('v51Notifications.automaticHelp') }}</p>
      <ElButton v-if="failed" @click="load">{{ c('v12sound.retry') }}</ElButton>
      <ElAlert v-else-if="data?.global === false" :title="c('v51Notifications.globalOnly')" type="info" :closable="false" />
      <ElForm v-else-if="data" label-position="top" :disabled="!editable || saving || loading">
        <section v-for="key in ruleKeys" :key="key" class="notification-rule">
          <div class="rule-heading"><div><h3>{{ c(`v51Notifications.rule.${key}`) }}</h3><p>{{ c(`v51Notifications.rule.${key}Help`) }}</p></div><ElSwitch v-model="data.rules[key].enabled" :aria-label="c(`v51Notifications.rule.${key}`)" /></div>
          <div v-if="data.rules[key].enabled" class="rule-fields">
            <ElFormItem :label="c(key === 'withdrawalOverdue' ? 'v51Notifications.minutes' : 'v51Notifications.threshold')">
              <ElInputNumber v-if="key === 'withdrawalOverdue'" v-model="data.rules[key].minutes" :min="1" :max="525600" :precision="0" :aria-label="c('v51Notifications.minutes')" />
              <ElInput v-else v-model="data.rules[key].threshold" inputmode="decimal" maxlength="29" :aria-label="c('v51Notifications.threshold')"><template #append>{{ key === 'energyLow' ? data.rules[key].currency : 'Token' }}</template></ElInput>
            </ElFormItem>
            <ElFormItem v-if="key === 'energyLow'" :label="c('v51Notifications.energyCurrency')"><ElSelect v-model="data.rules[key].currency"><ElOption label="TRX" value="TRX" /><ElOption label="USDT" value="USDT" /></ElSelect></ElFormItem>
            <div class="rule-delivery">
              <div class="delivery-heading"><span>{{ c('v51Notifications.internal') }}</span><ElSwitch v-model="data.rules[key].popup" :aria-label="c('v51Notifications.internal')" /></div>
              <div v-if="data.rules[key].popup" class="delivery-fields">
                <ElFormItem :label="c('v51Notifications.template')"><ElSelect v-model="data.rules[key].templateCode" filterable><ElOption v-for="item in data.templates.filter(item => item.channelType === 'INTERNAL')" :key="item.code" :label="`${item.shortCode} · ${item.name}`" :value="item.code" :disabled="!item.enabled" /></ElSelect></ElFormItem>
                <ElFormItem :label="c('v51Notifications.popupStyle')"><ElSelect v-model="data.rules[key].severity"><ElOption v-for="severity in severities" :key="severity" :label="c(`v51Notifications.severity.${severity}`)" :value="severity" /></ElSelect></ElFormItem>
              </div>
            </div>
            <div class="rule-delivery">
              <div class="delivery-heading"><span>Telegram</span></div>
              <p v-if="targetsFailed" class="settings-help">{{ c('v51Notifications.targetsFailed') }} <ElButton link type="primary" @click="loadTargets">{{ c('v12sound.retry') }}</ElButton></p>
              <ElFormItem :label="c('v51Notifications.channel')"><ElSelect v-model="data.rules[key].channelId" clearable filterable :placeholder="c('v51Notifications.telegramOff')" :disabled="targetsFailed" @change="channelChanged(key)"><ElOption v-for="item in targets.channels" :key="item.id" :value="item.id" :label="item.name" /></ElSelect></ElFormItem>
              <div v-if="data.rules[key].channelId" class="delivery-fields">
                <ElFormItem :label="c('v51Notifications.template')"><ElSelect v-model="data.rules[key].telegramTemplateCode" filterable><ElOption v-for="item in data.templates.filter(item => item.channelType === 'TELEGRAM')" :key="item.code" :label="`${item.shortCode} · ${item.name}`" :value="item.code" :disabled="!item.enabled" /></ElSelect></ElFormItem>
                <ElFormItem :label="c('v51Notifications.recipients')"><ElSelect v-model="data.rules[key].targetMode"><ElOption :label="c('v51Notifications.allBindings')" value="ALL" /><ElOption :label="c('v51Notifications.selectedBindings')" value="SELECTED" /></ElSelect></ElFormItem>
                <ElFormItem v-if="data.rules[key].targetMode === 'SELECTED'" class="full-field" :label="c('v51Notifications.bindingSelect')"><ElSelect v-model="data.rules[key].bindingIds" filterable multiple><ElOption v-for="item in targets.bindings.filter(item => item.channelId === data!.rules[key].channelId)" :key="item.id" :value="item.id" :label="`${item.name} · ${item.chatId}`" /></ElSelect></ElFormItem>
              </div>
            </div>
          </div>
        </section>
        <ElButton v-if="editable" type="primary" :loading="saving" @click="save">{{ c('v51Notifications.saveRules') }}</ElButton>
      </ElForm>
    </ElCard>
  </div>
</template>
<style scoped>
.notification-settings { display: grid; gap: 20px; }
.settings-help, .rule-heading p { color: var(--exp-text-secondary); line-height: 1.65; margin: 0 0 20px; }
.notification-rule { padding: 24px 0; border-top: 1px solid var(--el-border-color-light); }
.rule-heading { display: flex; justify-content: space-between; gap: 24px; align-items: flex-start; }
.rule-heading h3 { margin: 0 0 6px; font-size: 15px; font-weight: 600; color: var(--el-text-color-primary); }
.rule-heading p { margin-bottom: 0; font-size: 13px; }
.rule-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 24px; margin-top: 24px; max-width: 1040px; }
.rule-delivery { grid-column: 1 / -1; padding: 16px 20px 0; margin-bottom: 16px; background: var(--el-fill-color-light); border-radius: var(--el-border-radius-base); }
.delivery-heading { display:flex; align-items: center; justify-content: space-between; gap:16px; margin-bottom:16px; font-weight:500; }
.delivery-fields { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:0 20px; }
.full-field { grid-column:1 / -1; }
@media(max-width:700px){.rule-fields,.delivery-fields{grid-template-columns:minmax(0,1fr);}.rule-delivery{padding:16px 12px 0;}.rule-heading{gap:16px;}}
</style>
