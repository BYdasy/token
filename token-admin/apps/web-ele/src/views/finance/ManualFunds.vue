<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, shallowRef, watch } from 'vue';
import { useAccessStore } from '@vben/stores';
import { useI18n } from '@vben/locales';
import {
  ElAlert,
  ElButton,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElSelect,
} from 'element-plus';
import {
  createManualDeposit,
  createManualWithdrawal,
  quoteManualWithdrawal,
  type ComputeRecord,
  type ComputeWithdrawalQuote,
} from '#/api/compute';
import { P, useCompute } from '#/components/business/shared';
import { currencyLabel } from '#/utils/currency';
const props = defineProps<{
  modelValue: boolean;
  resource: 'deposits' | 'withdrawals';
  defaultCurrency: string;
}>();
const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  saved: [];
}>();
const { c, can, enumLabel } = useCompute();
const access = useAccessStore();
const { locale } = useI18n();
const withdrawal = computed(() => props.resource === 'withdrawals');
const allowed = () =>
  can(withdrawal.value ? P.withdrawalWrite : P.depositWrite);
const form = reactive({
  username: '',
  amount: '',
  walletType: 'COMPUTE',
  remark: '',
});
type ManualAttempt = {
  resource: 'deposits' | 'withdrawals';
  payload: ComputeRecord;
  quote?: ComputeWithdrawalQuote;
  form: typeof form;
  uncertain: boolean;
};
const attempts = shallowRef<Partial<Record<'deposits' | 'withdrawals', ManualAttempt>>>({});
const pendingAttempt = computed(() => attempts.value[props.resource]);
const saving = ref(false),
  quoting = ref(false),
  quote = ref<ComputeWithdrawalQuote>();
const inputLocked = computed(() => saving.value || Boolean(pendingAttempt.value));
const hasAccountingQuote = computed(() => Boolean(quote.value && quote.value.accountingCurrency === 'USD'
  && Number(quote.value.accountingRate) > 0 && Number(quote.value.accountingAmount) > 0));
let generation = 0,
  quoteGeneration = 0,
  saveVersion = 0,
  requestId = '';
let quoteTimer: ReturnType<typeof setTimeout> | undefined;
function cancelQuote(clear = true) {
  clearTimeout(quoteTimer);
  quoteTimer = undefined;
  quoteGeneration++;
  if (clear) quote.value = undefined;
  quoting.value = false;
}
const quoteInput = () => ({
  username: form.username.trim(),
  amount: form.amount,
});
const quoteKey = () => JSON.stringify(quoteInput());
const quoteContext = () => `${access.accessToken}\n${locale.value}`;
watch(
  () => [props.modelValue, props.resource],
  () => {
    generation++;
    cancelQuote(!pendingAttempt.value);
    if (!props.modelValue) return;
    if (pendingAttempt.value) {
      Object.assign(form, pendingAttempt.value.form);
      quote.value = pendingAttempt.value.quote;
      requestId = String(pendingAttempt.value.payload.requestId);
      return;
    }
    Object.assign(form, {
      username: '',
      amount: '',
      walletType: 'COMPUTE',
      remark: '',
    });
    requestId = crypto.randomUUID();
  },
  { immediate: true, flush: 'sync' },
);
watch(
  () => [form.username, form.amount],
  () => {
    if (pendingAttempt.value) return;
    cancelQuote();
    if (props.modelValue && withdrawal.value && valid())
      quoteTimer = setTimeout(() => void calculate(), 400);
  },
  { flush: 'sync' },
);
function valid() {
  return (
    !!form.username.trim() &&
    /^\d{1,18}(\.\d{1,8})?$/.test(form.amount) &&
    !/^0+(\.0+)?$/.test(form.amount)
  );
}
async function calculate() {
  if (
    !props.modelValue ||
    !allowed() ||
    !withdrawal.value ||
    quoting.value ||
    saving.value ||
    pendingAttempt.value ||
    !valid()
  )
    return;
  const current = ++quoteGeneration,
    key = quoteKey(), context = quoteContext();
  quoting.value = true;
  try {
    const result = await quoteManualWithdrawal(quoteInput());
    if (current === quoteGeneration && props.modelValue && key === quoteKey() && context === quoteContext()) {
      if (result.accountingCurrency !== 'USD' || !(Number(result.accountingRate) > 0) || !(Number(result.accountingAmount) > 0)) {
        ElMessage.warning(c('v22Accounting.quoteIncomplete'));
        return;
      }
      quote.value = { ...result, key };
    }
  } catch {
    // 请求客户端已展示业务错误；自动报价失败后保持不可提交，输入变化后重新报价。
    if (current === quoteGeneration && context === quoteContext()) quote.value = undefined;
  } finally {
    if (current === quoteGeneration && context === quoteContext()) quoting.value = false;
  }
}
watch(() => access.accessToken, () => {
  generation++; saveVersion++; attempts.value = {}; saving.value = false; cancelQuote();
  if (props.modelValue) emit('update:modelValue', false);
}, { flush: 'sync' });
watch(locale, () => {
  generation++; cancelQuote(!pendingAttempt.value);
  if (!pendingAttempt.value && props.modelValue && withdrawal.value && valid())
    quoteTimer = setTimeout(() => void calculate(), 400);
}, { flush: 'sync' });
onBeforeUnmount(() => {
  generation++;
  cancelQuote();
});
async function save() {
  if (!allowed() || saving.value) return;
  if (pendingAttempt.value) {
    await sendAttempt(pendingAttempt.value, false);
    return;
  }
  if (!valid() || !form.remark.trim())
    return void ElMessage.warning(c('validation.required'));
  if (withdrawal.value && (!quote.value || !hasAccountingQuote.value || quote.value.key !== quoteKey()))
    return void ElMessage.warning(c('v5.quoteRequired'));
  const current = generation,
    key = quoteKey(),
    confirmed = quote.value, context = quoteContext();
  const payload: ComputeRecord = {
    ...quoteInput(),
    requestId,
    remark: form.remark.trim(),
  };
  if (withdrawal.value) {
    for (const field of [
      'currency',
      'network',
      'addressId',
      'address',
      'memo',
      'fields',
    ])
      payload[field] = confirmed![field];
    payload.expectedFee = String(confirmed!.fee);
    payload.expectedNetAmount = String(confirmed!.netAmount);
    payload.expectedAccountingRate = String(confirmed!.accountingRate);
    payload.expectedAccountingAmount = String(confirmed!.accountingAmount);
  } else {
    payload.walletType = form.walletType;
    payload.currency = props.defaultCurrency;
  }
  try {
    await ElMessageBox.confirm(
      `${form.username} · ${form.amount} ${currencyLabel(withdrawal.value ? confirmed?.currency : props.defaultCurrency)}${withdrawal.value ? ' · 1 ' + currencyLabel(confirmed?.currency) + ' = ' + confirmed?.accountingRate + ' USD · ' + c('v22Accounting.debitAmount') + ' ' + confirmed?.accountingAmount + ' USD · ' + c('field.fee') + ' ' + confirmed?.fee + ' · ' + c('field.netAmount') + ' ' + confirmed?.netAmount : ''}`,
      c(withdrawal.value ? 'v5.manualWithdrawal' : 'v5.manualDeposit'),
      { type: 'warning' },
    );
  } catch {
    return;
  }
  if (
    saving.value ||
    current !== generation ||
    context !== quoteContext() ||
    !props.modelValue ||
    key !== quoteKey() ||
    (withdrawal.value && confirmed !== quote.value)
  )
    return;
  const attempt = JSON.parse(JSON.stringify({ resource: props.resource, payload, quote: confirmed, form: { ...form }, uncertain: false })) as ManualAttempt;
  attempts.value = { ...attempts.value, [attempt.resource]: attempt };
  cancelQuote(false);
  await sendAttempt(attempt, true);
}
async function sendAttempt(attempt: ManualAttempt, firstSubmission: boolean) {
  const token = access.accessToken, current = ++saveVersion;
  const belongs = () => token === access.accessToken && attempts.value[attempt.resource]?.payload.requestId === attempt.payload.requestId;
  saving.value = true;
  try {
    if (attempt.resource === 'withdrawals') await createManualWithdrawal(attempt.payload);
    else await createManualDeposit(attempt.payload);
    if (!belongs()) return;
    const next = { ...attempts.value }; delete next[attempt.resource]; attempts.value = next;
    if (props.resource === attempt.resource) emit('update:modelValue', false);
    emit('saved');
    ElMessage.success(c('common.saved'));
  } catch (cause) {
    if (!belongs()) return;
    const response = (cause as { response?: { status?: number; data?: { code?: number } } })?.response;
    const status = Number(response?.status);
    const rejected = firstSubmission && !attempt.uncertain && Number.isFinite(status)
      && status >= 200 && status < 500 && ![401, 403, 428].includes(status)
      && typeof response?.data?.code === 'number' && response.data.code !== 0
      && ![401, 403, 428].includes(response.data.code) && response.data.code < 500;
    if (rejected) {
      const next = { ...attempts.value }; delete next[attempt.resource]; attempts.value = next;
      if (props.resource === attempt.resource) {
        requestId = crypto.randomUUID(); cancelQuote();
        if (props.modelValue && withdrawal.value && valid()) quoteTimer = setTimeout(() => void calculate(), 400);
      }
    } else attempts.value = { ...attempts.value, [attempt.resource]: { ...attempt, uncertain: true } };
    // 请求客户端已经显示错误；未知结果始终保留原号、原字段和原报价，不能生成新提款。
  } finally {
    if (current === saveVersion && token === access.accessToken) saving.value = false;
  }
}
</script>
<template>
  <ElDialog
    :model-value="modelValue"
    :title="c(withdrawal ? 'v5.manualWithdrawal' : 'v5.manualDeposit')"
    width="min(720px,96vw)"
    :close-on-click-modal="false"
    :close-on-press-escape="!saving"
    :show-close="!saving"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <ElAlert
      :title="c('v6.manualFundsHelp')"
      type="info"
      :closable="false"
      class="mb-4"
    />
    <ElAlert v-if="pendingAttempt && !saving" :title="c('v22Accounting.pendingFunds')" type="warning" :closable="false" class="mb-4" />
    <ElForm label-position="top" :disabled="inputLocked"
      ><div class="compute-form-grid">
        <ElFormItem :label="c('field.username')" required
          ><ElInput v-model="form.username" maxlength="40"
        /></ElFormItem>
        <ElFormItem v-if="!withdrawal" :label="c('field.walletType')" required
          ><ElSelect v-model="form.walletType"
            ><ElOption
              v-for="value in ['COMPUTE', 'PROJECT']"
              :key="value"
              :value="value"
              :label="enumLabel(value)" /></ElSelect
        ></ElFormItem>
        <ElFormItem :label="c('field.amount')" required
          ><ElInput
            v-model="form.amount"
            inputmode="decimal"
            maxlength="27"
            :placeholder="c('hint.positiveAmount')"
        /></ElFormItem>
        <ElFormItem
          :label="c('v5.creationRemark')"
          required
          class="compute-wide"
          ><ElInput
            v-model="form.remark"
            type="textarea"
            :rows="2"
            maxlength="500"
        /></ElFormItem></div
    ></ElForm>
    <template v-if="withdrawal">
      <p class="compute-help" role="status">
        {{ c(quoting ? 'v7.quoting' : 'v7.automaticQuote') }}
      </p>
      <ElDescriptions v-if="quote" :column="2" border class="mt-4">
        <ElDescriptionsItem :label="c('field.currency')">{{
          currencyLabel(quote.currency)
        }}</ElDescriptionsItem>
        <ElDescriptionsItem :label="c('field.network')">{{
          quote.network
        }}</ElDescriptionsItem>
        <ElDescriptionsItem :label="c('v22Accounting.rate')">1 {{ currencyLabel(quote.currency) }} = {{ quote.accountingRate }} USD</ElDescriptionsItem>
        <ElDescriptionsItem :label="c('v22Accounting.debitAmount')">{{ quote.accountingAmount }} USD</ElDescriptionsItem>
        <ElDescriptionsItem v-if="quote.availableUsd != null" :label="c('v22Accounting.availableUsd')">{{ quote.availableUsd }}</ElDescriptionsItem>
        <ElDescriptionsItem v-if="quote.availableAmount != null" :label="c('v22Accounting.available')">{{ quote.availableAmount }} {{ currencyLabel(quote.currency) }}</ElDescriptionsItem>
        <ElDescriptionsItem :label="c('field.fee')"
          >{{ quote.fee }} {{ currencyLabel(quote.currency) }}</ElDescriptionsItem
        >
        <ElDescriptionsItem :label="c('field.netAmount')"
          >{{ quote.netAmount }} {{ currencyLabel(quote.currency) }}</ElDescriptionsItem
        >
        <ElDescriptionsItem v-if="quote.accountingFee != null" :label="c('v22Accounting.feeAmount')">{{ quote.accountingFee }} USD</ElDescriptionsItem>
        <ElDescriptionsItem v-if="quote.accountingNetAmount != null" :label="c('v22Accounting.netAmount')">{{ quote.accountingNetAmount }} USD</ElDescriptionsItem>
        <ElDescriptionsItem :label="c('field.address')">{{
          quote.address
        }}</ElDescriptionsItem>
        <ElDescriptionsItem v-if="quote.memo" :label="c('field.memo')">{{
          quote.memo
        }}</ElDescriptionsItem>
        <ElDescriptionsItem
          v-for="(value, key) in quote.fields || {}"
          :key="key"
          :label="c('currency.addressField.' + key)"
          >{{ value }}</ElDescriptionsItem
        >
      </ElDescriptions>
    </template>
    <template #footer
      ><ElButton :disabled="saving" @click="emit('update:modelValue', false)">{{
        c('common.cancel')
      }}</ElButton
      ><ElButton
        type="primary"
        :loading="saving"
        :disabled="!pendingAttempt && withdrawal && !hasAccountingQuote"
        @click="save"
        >{{ c(pendingAttempt ? 'v22Accounting.checkFunds' : 'common.save') }}</ElButton
      ></template
    >
  </ElDialog>
</template>
