<script setup lang="ts">
import { computed, onBeforeUnmount, onDeactivated, reactive, ref, shallowRef, watch } from 'vue';
import { useAccessStore } from '@vben/stores';
import { useI18n } from '@vben/locales';
import { ElAlert, ElButton, ElDescriptions, ElDescriptionsItem, ElDialog, ElForm, ElFormItem, ElInput, ElMessage, ElOption, ElSelect } from 'element-plus';
import { createDepositHashRecheck, fetchDepositRecheckOptions, type DepositRecheckOptions, type DepositRecheckRequest, type DepositRecheckResult } from '#/api/compute';
import { useCompute } from '#/components/business/shared';
import { currencyLabel } from '#/utils/currency';

const props = defineProps<{ modelValue: boolean; allowed: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [] }>();
const { c, date } = useCompute();
const access = useAccessStore();
const { locale } = useI18n();
const options = ref<DepositRecheckOptions>();
const optionsLoading = ref(false), optionsFailed = ref(false), saving = ref(false);
const form = reactive({ networkId: '', assetId: '', txHash: '', address: '', transferLocator: '' });
const code = ref('');
const result = ref<DepositRecheckResult>();
type Payload = Omit<DepositRecheckRequest, 'code'>;
// 未决请求只留在当前组件内存，验证码不进入快照或持久化筛选。
const pending = shallowRef<Payload>();
const identity = ref<{ fingerprint: string; requestId: string }>();
const locked = computed(() => saving.value || Boolean(pending.value) || Boolean(result.value));
const networks = computed(() => [...new Map((options.value?.assets || []).map(asset => [asset.networkId, { value: asset.networkId, label: asset.networkName || asset.networkId }])).values()]);
const assets = computed(() => (options.value?.assets || []).filter(asset => asset.networkId === form.networkId));
const asset = computed(() => assets.value.find(item => item.assetId === form.assetId));
const knownStatuses = new Set(['PENDING', 'PROCESSING', 'RETRY', 'RESOLVED', 'REJECTED', 'NOT_FOUND']);
const resultStatus = computed(() => result.value && knownStatuses.has(result.value.status) ? c(`v47Recheck.status.${result.value.status}`) : result.value?.status || '—');
let generation = 0, readVersion = 0, saveVersion = 0, disposed = false;
const context = () => `${access.accessToken}\n${locale.value}`;
function close() { emit('update:modelValue', false); }
function reset() {
  if (saving.value || pending.value) return;
  Object.assign(form, { networkId: '', assetId: '', txHash: '', address: '', transferLocator: '' });
  identity.value = undefined; result.value = undefined; code.value = '';
}
function invalidate(clear: boolean) {
  generation++; readVersion++;
  code.value = ''; optionsLoading.value = false;
  if (clear) {
    saveVersion++; saving.value = false; pending.value = undefined; options.value = undefined;
    optionsFailed.value = false; reset(); close();
  }
}
async function loadOptions() {
  if (disposed || !props.modelValue || !props.allowed || !access.accessToken) return;
  const own = ++readVersion, owner = context(), view = generation;
  const current = () => !disposed && own === readVersion && view === generation && owner === context() && props.modelValue && props.allowed;
  optionsLoading.value = true; optionsFailed.value = false;
  try {
    const value = await fetchDepositRecheckOptions();
    if (current()) {
      options.value = value;
      if (!locked.value && !value.assets.some(item => item.networkId === form.networkId && item.assetId === form.assetId)) form.assetId = '';
    }
  } catch { if (current()) optionsFailed.value = true; }
  finally { if (own === readVersion) optionsLoading.value = false; }
}
watch(() => form.networkId, () => {
  if (!locked.value && !assets.value.some(item => item.assetId === form.assetId)) form.assetId = '';
}, { flush: 'sync' });
watch(() => props.modelValue, value => {
  invalidate(false);
  if (value) void loadOptions();
}, { immediate: true, flush: 'sync' });
watch(() => [access.accessToken, props.allowed], () => invalidate(true), { flush: 'sync' });
watch(locale, () => { invalidate(false); if (props.modelValue) void loadOptions(); }, { flush: 'sync' });
onDeactivated(() => { invalidate(false); close(); });
onBeforeUnmount(() => { disposed = true; invalidate(true); });

async function submit() {
  if (disposed || !props.modelValue || !props.allowed || !access.accessToken || saving.value || optionsLoading.value || optionsFailed.value || !options.value || result.value) return;
  if (!/^\d{6}$/.test(code.value)) return void ElMessage.warning(c('merchant.totpRequired'));
  let payload = pending.value;
  const firstSubmission = !payload;
  if (!payload) {
    const valuesToCheck = [form.assetId, form.networkId, form.address, form.transferLocator];
    if (!asset.value || !form.address.trim() || form.address.trim().length > 200 || form.transferLocator.trim().length > 80
      || form.assetId.length > 128 || form.networkId.length > 128 || valuesToCheck.some(value => /[\u0000-\u001f\u007f-\u009f]/.test(value)))
      return void ElMessage.warning(c('v47Recheck.invalidFields'));
    const txHash = form.txHash.trim();
    if (!(asset.value.family === 'EVM' ? /^0x[\da-f]{64}$/i : /^[\da-f]{64}$/i).test(txHash))
      return void ElMessage.warning(c(asset.value.family === 'EVM' ? 'v47Recheck.invalidEvmHash' : 'v47Recheck.invalidHash'));
    const values = { assetId: form.assetId, networkId: form.networkId, txHash: txHash.toLowerCase(), address: form.address.trim(), ...(form.transferLocator.trim() ? { transferLocator: form.transferLocator.trim() } : {}) };
    const fingerprint = JSON.stringify(values);
    if (identity.value?.fingerprint !== fingerprint) identity.value = { fingerprint, requestId: `DR_${crypto.randomUUID().replaceAll('-', '')}` };
    payload = { ...values, requestId: identity.value.requestId };
    pending.value = payload;
  }
  const own = ++saveVersion, owner = context(), view = generation;
  const current = () => !disposed && own === saveVersion && view === generation && owner === context() && props.modelValue && props.allowed;
  saving.value = true;
  try {
    const response = await createDepositHashRecheck({ ...payload, code: code.value });
    if (!current()) return;
    result.value = response; pending.value = undefined; emit('saved');
  } catch (error) {
    if (!current()) return;
    // 只有首次明确业务拒绝允许编辑；超时或重试中的拒绝不能丢失原核查请求。
    const response = (error as { response?: { data?: { data?: { recheckSubmission?: string } } } })?.response;
    if (firstSubmission && response?.data?.data?.recheckSubmission === 'NOT_SUBMITTED') pending.value = undefined;
  } finally {
    if (own === saveVersion) { saving.value = false; code.value = ''; }
  }
}
</script>

<template>
  <ElDialog :model-value="modelValue" :title="c('v47Recheck.title')" width="min(680px,94vw)" :close-on-click-modal="false" @update:model-value="emit('update:modelValue', $event)">
    <ElAlert class="mb-4" type="info" :closable="false" :title="c('v47Recheck.help')" />
    <ElAlert v-if="optionsFailed" class="mb-4" type="error" :closable="false" :title="c('common.loadFailed')" />
    <ElButton v-if="optionsFailed" class="mb-4" :loading="optionsLoading" @click="loadOptions">{{ c('v11.payment.refresh') }}</ElButton>
    <ElAlert v-if="options && !options.assets.length" class="mb-4" type="warning" :closable="false" :title="c('v47Recheck.noAssets')" />
    <ElAlert v-if="pending && !saving" class="mb-4" type="warning" :closable="false" :title="c('v47Recheck.uncertain')" />
    <ElForm label-position="top" :disabled="locked || optionsLoading || optionsFailed" @submit.prevent="submit">
      <ElFormItem :label="c('v47Recheck.merchant')"><ElInput :model-value="options ? `${options.merchantId} · ${options.environment}` : '—'" readonly /></ElFormItem>
      <ElFormItem :label="c('field.network')"><ElSelect v-model="form.networkId" class="w-full" filterable><ElOption v-for="item in networks" :key="item.value" :label="item.label" :value="item.value" /></ElSelect></ElFormItem>
      <ElFormItem :label="c('field.currency')"><ElSelect v-model="form.assetId" class="w-full" filterable><ElOption v-for="item in assets" :key="item.assetId" :label="currencyLabel(item.displayName || item.symbol)" :value="item.assetId" /></ElSelect></ElFormItem>
      <ElFormItem :label="c('v8.finance.txHash')"><ElInput v-model="form.txHash" maxlength="66" autocomplete="off" /></ElFormItem>
      <ElFormItem :label="c('v47Recheck.address')"><ElInput v-model="form.address" maxlength="200" autocomplete="off" /></ElFormItem>
      <ElFormItem :label="c('v47Recheck.transferLocator')"><ElInput v-model="form.transferLocator" maxlength="80" autocomplete="off" /><small class="text-muted-foreground">{{ c('v47Recheck.locatorHelp') }}</small></ElFormItem>
    </ElForm>
    <ElForm v-if="!result" label-position="top" @submit.prevent="submit">
      <ElFormItem :label="c('merchant.googleCode')"><ElInput v-model="code" maxlength="6" inputmode="numeric" autocomplete="off" :disabled="saving" /></ElFormItem>
    </ElForm>
    <ElDescriptions v-if="identity" :column="1" border class="mb-4"><ElDescriptionsItem :label="c('v47Recheck.requestId')">{{ identity.requestId }}</ElDescriptionsItem></ElDescriptions>
    <template v-if="result">
      <ElAlert class="mb-4" type="info" :closable="false" :title="c('v47Recheck.resultHelp')" />
      <ElDescriptions :column="1" border>
        <ElDescriptionsItem :label="c('v47Recheck.recheckId')">{{ result.recheckId }}</ElDescriptionsItem>
        <ElDescriptionsItem :label="c('field.status')">{{ resultStatus }}</ElDescriptionsItem>
        <ElDescriptionsItem v-if="result.nextRetryAt" :label="c('v47Recheck.nextRetryAt')">{{ date(result.nextRetryAt) }}</ElDescriptionsItem>
        <ElDescriptionsItem v-if="result.resolvedAt" :label="c('v47Recheck.resolvedAt')">{{ date(result.resolvedAt) }}</ElDescriptionsItem>
      </ElDescriptions>
    </template>
    <template #footer>
      <ElButton @click="close">{{ c('common.close') }}</ElButton>
      <ElButton v-if="result" type="primary" @click="reset">{{ c('v47Recheck.newRequest') }}</ElButton>
      <ElButton v-else type="primary" :loading="saving" :disabled="!allowed || optionsLoading || optionsFailed || !options?.assets.length" @click="submit">{{ c(pending ? 'v47Recheck.retry' : 'merchant.submit') }}</ElButton>
    </template>
  </ElDialog>
</template>
