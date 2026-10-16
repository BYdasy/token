<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { showConfirmDialog, showSuccessToast } from "vant";
import { api, ApiError, requestId } from "@/api";
import type { Address, Quote, Wallet, WithdrawalBalance } from "@/api/types";
import { useCurrencies } from "@/composables/useCurrencies";
import { useSession } from "@/stores/session";
import { useToken } from '@/composables/useToken';
import PaymentPasswordField from "./PaymentPasswordField.vue";
import AddressPicker from "./AddressPicker.vue";
import { useTask } from "@/composables/useTask";
import { showTaskPrompt } from "@/composables/useTaskPrompt";
import { money, positiveAmount, validPassword } from "@/utils/format";
import { currencyLabel } from "@/utils/currency";
import { usePendingWithdrawal, type PendingWithdrawal } from '@/utils/withdrawal-pending';
import { usableWithdrawalAddress } from '@/utils/withdrawal-address';
const props = defineProps<{ wallets: Wallet[] }>();
const emit = defineEmits<{ changed: []; addresses: [] }>();
const { t, locale } = useI18n();
const session = useSession();
const { unitName } = useToken();
const { pending: pendingAttempt, save: saveAttempt, clear: clearAttempt } = usePendingWithdrawal(session);
const { loading, error, run } = useTask();
const form = reactive({
  currency: "",
  network: "",
  amount: "",
  address: "",
  memo: "",
  fields: {} as Record<string, string>,
  payPassword: "",
});
const {
  channels,
  task: channelTask,
  refresh,
} = useCurrencies("WITHDRAW", "EARNINGS");
const selectedChannel = computed(() =>
  channels.value.find(
    (item) => item.currency === form.currency && item.network === form.network,
  ),
);
const receivingValid = computed(() => {
  const channel = selectedChannel.value;
  return Boolean(
    addressesLoaded.value && boundAddress.value && channel &&
    (!channel.needMemo || form.memo.trim()) &&
    (channel.assetType === "FIAT"
      ? channel.requiredFields.every((key) => form.fields[key]?.trim())
      : form.address.trim()),
  );
});
const addresses = ref<Address[]>([]);
const addressesLoaded = ref(false);
const addressLoadFailed = ref(false);
const availableAddresses = computed(() => addresses.value.filter((address) => usableWithdrawalAddress(address, channels.value)));
const quote = ref<Quote>();
const quoteLoading = ref(false);
const quoteError = ref("");
const currencyBalance = ref<WithdrawalBalance>();
const balanceLoading = ref(false);
const balanceError = ref('');
const hasAccountingQuote = computed(() => Boolean(quote.value
  && quote.value.accountingCurrency === 'USD'
  && Number(quote.value.accountingRate) > 0
  && Number(quote.value.accountingAmount) > 0));
const confirming = ref(false);
const busy = computed(() => loading.value || confirming.value);
const inputLocked = computed(() => busy.value || Boolean(pendingAttempt.value));
let disposed = false;
let addressController = new AbortController();
let quoteVersion = 0;
let balanceVersion = 0;
let quoteTimer: ReturnType<typeof setTimeout> | undefined;
let quoteController: AbortController | undefined;
let balanceTimer: ReturnType<typeof setTimeout> | undefined;
let balanceController: AbortController | undefined;
const idempotency = ref<string>(requestId());
const selectedAddress = ref("");
const boundAddress = computed(() => availableAddresses.value.find((address) => String(address.id) === selectedAddress.value
  && address.currency === form.currency && address.network === form.network));
const payPasswordReady = computed(() => Boolean(session.member?.hasPayPassword) && validPassword(form.payPassword));
const totalAvailable = computed(() => quote.value?.availableUsd ?? currencyBalance.value?.availableUsd
  ?? props.wallets.find(item => item.type === 'EARNINGS' && item.currency === 'USDT')?.available);
let selectionInitialized = false;
const balance = computed(() => quote.value?.availableAmount ?? currencyBalance.value?.availableAmount);
const exchangeRate = computed(() => quote.value?.accountingRate ?? currencyBalance.value?.accountingRate);
const active = (token: string, language: string, signal: AbortSignal) =>
  !disposed && !signal.aborted && token === session.token && language === locale.value;
function clearSelection() {
  selectedAddress.value = "";
  form.currency = "";
  form.network = "";
  form.address = "";
  form.memo = "";
  form.fields = {};
}
function selectAddress(requested: Address) {
  if (inputLocked.value) return;
  const address = availableAddresses.value.find((item) => item.id === requested.id);
  if (!address) return;
  applyAddress(address);
}
function applyAddress(address: Address) {
  selectionInitialized = true;
  selectedAddress.value = String(address.id);
  form.currency = address.currency;
  form.network = address.network;
  form.address = address.address;
  form.memo = address.memo || "";
  form.fields = { ...address.fields };
}
// 先选择钱包，再从钱包带入通道；异步加载完成或刷新时不覆盖用户已选项。
watch([availableAddresses, addressesLoaded], () => {
  if (pendingAttempt.value || !addressesLoaded.value) return;
  if (selectedAddress.value && !availableAddresses.value.some((address) => String(address.id) === selectedAddress.value)) clearSelection();
  if (!selectionInitialized && availableAddresses.value[0]) applyAddress(availableAddresses.value[0]);
});
watch(
  () => [form.amount, form.currency, form.network, selectedChannel.value, receivingValid.value, session.token, locale.value],
  () => {
    scheduleQuote();
  },
  { flush: 'sync' },
);
watch(() => [form.currency, form.network, selectedChannel.value, receivingValid.value, session.token, locale.value, props.wallets], scheduleBalance, { flush: 'sync' });
watch([form, selectedAddress], () => {
  if (!pendingAttempt.value) idempotency.value = requestId();
}, { flush: 'sync', deep: true });
function scheduleBalance() {
  if (pendingAttempt.value) return;
  const version = ++balanceVersion;
  clearTimeout(balanceTimer); balanceController?.abort();
  currencyBalance.value = undefined; balanceError.value = ''; balanceLoading.value = false;
  if (disposed || !session.token || !selectedChannel.value || !receivingValid.value) return;
  const token = session.token, language = locale.value;
  const query = { currency: form.currency, network: form.network };
  balanceLoading.value = true;
  balanceTimer = setTimeout(async () => {
    if (disposed || version !== balanceVersion || token !== session.token || language !== locale.value) return;
    const controller = new AbortController(); balanceController = controller;
    const current = () => version === balanceVersion && active(token, language, controller.signal);
    try {
      const result = await api.withdrawalBalance(query, controller.signal);
      if (!current()) return;
      if (result.accountingCurrency !== 'USD' || !(Number(result.accountingRate) > 0)
        || result.availableAmount == null || !Number.isFinite(Number(result.availableAmount)) || Number(result.availableAmount) < 0
        || result.availableUsd == null || !Number.isFinite(Number(result.availableUsd)) || Number(result.availableUsd) < 0)
        throw new Error(t('user.v22Accounting.quoteIncomplete'));
      currencyBalance.value = result;
    } catch (cause) {
      if (current()) balanceError.value = cause instanceof Error ? cause.message : t('user.error.request');
    } finally { if (current()) balanceLoading.value = false; }
  }, 120);
}
function scheduleQuote() {
  if (pendingAttempt.value) return;
  const version = ++quoteVersion;
  clearTimeout(quoteTimer);
  quoteController?.abort();
  quote.value = undefined;
  quoteError.value = "";
  quoteLoading.value = false;
  if (disposed || !session.token || !positiveAmount(form.amount) || !selectedChannel.value || !receivingValid.value) return;
  quoteLoading.value = true;
  const body = { currency: form.currency, network: form.network, amount: form.amount };
  const token = session.token, language = locale.value;
  quoteTimer = setTimeout(async () => {
    if (disposed || token !== session.token || language !== locale.value || version !== quoteVersion) return;
    const controller = new AbortController();
    quoteController = controller;
    const current = () => version === quoteVersion && active(token, language, controller.signal);
    try {
      const result = await api.quote(body, controller.signal);
      if (!current()) return;
      if (result.accountingCurrency !== 'USD' || !(Number(result.accountingRate) > 0) || !(Number(result.accountingAmount) > 0))
        throw new Error(t('user.v22Accounting.quoteIncomplete'));
      quote.value = result;
    } catch (cause) {
      if (current()) {
        quoteError.value = cause instanceof Error ? cause.message : t("user.error.request");
      }
    } finally {
      if (current()) quoteLoading.value = false;
    }
  }, 350);
}
async function submit() {
  if (disposed || busy.value || !session.token) return;
  if (pendingAttempt.value) {
    await sendAttempt(pendingAttempt.value, false);
    return;
  }
  if (disposed || !quote.value || !hasAccountingQuote.value || quoteLoading.value || busy.value || !receivingValid.value || !payPasswordReady.value) return;
  const token = session.token;
  const confirmedQuote = quote.value;
  confirming.value = true;
  // 将确认框对应的报价与表单一起快照，后续服务端报价变化必须重新确认。
  const submission = {
    requestId: idempotency.value,
    ...form,
    fields: { ...form.fields },
    addressId: Number(boundAddress.value!.id),
    expectedFee: String(quote.value.fee),
    expectedNetAmount: String(quote.value.netAmount),
    expectedAccountingRate: String(quote.value.accountingRate),
    expectedAccountingAmount: String(quote.value.accountingAmount),
  };
  try {
    // 先展示后台行为限制，再进入金额确认；用户知悉不会解除限制。
    if (!await showTaskPrompt("WITHDRAW", addressController.signal)) return;
    if (disposed || token !== session.token || submission.requestId !== idempotency.value) return;
    await showConfirmDialog({
      title: t("user.wallet.submitWithdraw"),
      message: `${t("user.v22Accounting.originalAmount")}: ${submission.amount} ${currencyLabel(submission.currency)}\n${t("user.v22Accounting.rateValue", { currency: currencyLabel(submission.currency), rate: submission.expectedAccountingRate })}\n${t("user.v22Accounting.debitAmount")}: ${submission.expectedAccountingAmount} ${unitName.value}\n${t("user.wallet.fee")}: ${submission.expectedFee} ${currencyLabel(submission.currency)}\n${t("user.wallet.netAmount")}: ${submission.expectedNetAmount} ${currencyLabel(submission.currency)}\n${t("user.wallet.withdrawAddress")}: ${
        Object.keys(submission.fields).length
          ? Object.entries(submission.fields)
              .map(
                ([key, value]) =>
                  `${t(`user.currency.addressField.${key}`)}: ${value}`,
              )
              .join("\n")
          : submission.address
      }${submission.memo ? `\n${t("user.currency.memo")}: ${submission.memo}` : ""}`,
      confirmButtonText: t("user.common.confirm"),
      cancelButtonText: t("user.common.cancel"),
    });
  } catch (cause) {
    if (cause instanceof Error && !disposed && token === session.token) error.value = cause.message;
    return;
  } finally {
    confirming.value = false;
  }
  if (disposed || token !== session.token || submission.requestId !== idempotency.value) return;
  // 发送前固定所有请求字段，包含原报价和支付密码；后续只在内存中重放原请求。
  saveAttempt({ payload: submission, quote: confirmedQuote, uncertain: false });
  ++quoteVersion; clearTimeout(quoteTimer); quoteController?.abort(); quoteLoading.value = false;
  ++balanceVersion; clearTimeout(balanceTimer); balanceController?.abort(); balanceLoading.value = false;
  await sendAttempt(pendingAttempt.value!, true);
}
async function sendAttempt(attempt: PendingWithdrawal, firstSubmission: boolean) {
  const token = session.token;
  const belongs = () => !disposed && token === session.token && pendingAttempt.value?.payload.requestId === attempt.payload.requestId;
  await run(async () => {
    try {
      if (!belongs()) return;
      await api.withdraw(attempt.payload);
    } catch (cause) {
      if (!belongs()) return;
      // 只接受首次发送的明确业务拒绝。未知之后的冲突/错误都不能证明原请求未落单。
      const rejected = firstSubmission && !attempt.uncertain && cause instanceof ApiError
        && cause.responseReceived && ![401, 403].includes(cause.code) && cause.code < 500;
      if (rejected) {
        clearAttempt(attempt.payload.requestId);
        idempotency.value = requestId();
        scheduleQuote();
      } else saveAttempt({ ...attempt, uncertain: true });
      throw cause;
    }
    if (!belongs()) return;
    clearAttempt(attempt.payload.requestId);
    idempotency.value = requestId();
    quote.value = undefined;
    form.amount = "";
    form.payPassword = "";
    showSuccessToast(t("user.v10.withdrawSubmitted"));
    emit("changed");
  });
}
function restoreAttempt() {
  const attempt = pendingAttempt.value;
  if (!attempt) return;
  const payload = attempt.payload;
  Object.assign(form, { currency: payload.currency, network: payload.network, amount: payload.amount,
    address: payload.address, memo: payload.memo, fields: { ...payload.fields }, payPassword: payload.payPassword });
  selectedAddress.value = payload.addressId == null ? '' : String(payload.addressId);
  selectionInitialized = true;
  idempotency.value = payload.requestId;
  quote.value = { ...attempt.quote };
}
restoreAttempt();
function loadAddresses() {
  if (!session.token || disposed) return;
  const token = session.token;
  const controller = addressController;
  addressesLoaded.value = false;
  addressLoadFailed.value = false;
  void run(async () => {
    try {
      if (disposed || token !== session.token || controller.signal.aborted) return;
      const items = await api.addresses(controller.signal);
      if (!disposed && token === session.token && !controller.signal.aborted) {
        addresses.value = items;
        addressesLoaded.value = true;
      }
    } catch (cause) {
      if (!disposed && token === session.token && !controller.signal.aborted) {
        addressLoadFailed.value = true;
        throw cause;
      }
    }
  }, false);
}
watch(() => session.token, () => {
  addressController.abort(); addressController = new AbortController();
  addresses.value = []; addressesLoaded.value = false; addressLoadFailed.value = false; selectionInitialized = false;
  form.amount = ''; form.payPassword = ''; error.value = '';
  clearSelection();
  loadAddresses();
}, { flush: 'sync' });
onMounted(loadAddresses);
onUnmounted(() => {
  disposed = true;
  ++quoteVersion;
  clearTimeout(quoteTimer);
  quoteController?.abort();
  ++balanceVersion;
  clearTimeout(balanceTimer);
  balanceController?.abort();
  addressController.abort();
});
</script>
<template>
  <div class="withdraw-layout">
    <section class="withdraw-balance card">
      <div><span>{{ $t("user.v13.wallet.withdrawAvailable") }}</span><strong>{{ money(totalAvailable, 2) }} <small>{{ unitName }}</small></strong></div>
      <van-icon name="balance-o" />
    </section>
    <div class="panel-grid">
      <section class="card">
        <div class="section-heading">
          <h2>{{ $t("user.wallet.withdrawAddress") }}</h2>
          <button class="text-button" :disabled="inputLocked" @click="$emit('addresses')">+ {{ $t("user.wallet.addAddress") }}</button>
        </div>
        <van-form @submit="submit">
          <AddressPicker :addresses="addresses" :channels="channels" :selected-id="selectedAddress" :disabled="inputLocked || channelTask.loading.value || !addressesLoaded" @select="selectAddress" />
          <div v-if="selectedAddress || pendingAttempt" class="withdraw-address-summary">
            <span class="field-label">{{ $t("user.wallet.withdrawAddress") }}</span>
            <p v-if="form.address" class="address-value">{{ form.address }}</p>
            <p v-if="form.memo"><span>{{ $t("user.currency.memo") }}:</span> {{ form.memo }}</p>
            <dl v-if="Object.keys(form.fields).length" class="details-list compact">
              <div v-for="(value, key) in form.fields" :key="key"><dt>{{ $t(`user.currency.addressField.${key}`) }}</dt><dd>{{ value }}</dd></div>
            </dl>
          </div>
          <p v-if="addressesLoaded && !channelTask.loading.value && !channelTask.error.value && !availableAddresses.length && !pendingAttempt" class="notice-box withdraw-bind-notice">
            <span>{{ $t('user.v46.withdrawal.boundRequired') }}</span>
            <button type="button" class="text-button" :disabled="inputLocked" @click="$emit('addresses')">{{ $t('user.v46.withdrawal.bindWallet') }}</button>
          </p>
          <van-field v-model="form.amount" :disabled="inputLocked || !boundAddress" :label="$t('user.v13.wallet.withdrawAmount')" :placeholder="$t('user.v13.wallet.amountPlaceholder')" type="number" :rules="[{ validator: positiveAmount, message: $t('user.common.amountInvalid') }]">
            <template #right-icon><span>{{ currencyLabel(form.currency) || '—' }}</span></template>
          </van-field>
          <template v-if="selectedChannel">
            <p v-if="balanceLoading && balance == null" class="channel-balance">{{ $t('user.v22Accounting.balanceLoading') }}</p>
            <p v-else class="channel-balance">{{ $t("user.v13.wallet.channelAvailable", { amount: money(balance), currency: currencyLabel(form.currency) }) }}</p>
            <p v-if="balanceError" class="inline-error">{{ balanceError }} <button type="button" class="text-button" @click="scheduleBalance">{{ $t('user.common.refresh') }}</button></p>
          </template>
          <PaymentPasswordField v-model="form.payPassword" :disabled="inputLocked || !boundAddress" />
          <p v-if="channelTask.error.value" class="inline-error">{{ channelTask.error.value }} <button type="button" class="text-button" :disabled="busy" @click="refresh">{{ $t("user.common.refresh") }}</button></p>
          <p v-if="selectedAddress && !selectedChannel && !channelTask.loading.value" class="inline-error">{{ $t("user.ui2.selectors.channelUnavailable") }}</p>
          <p v-if="error" class="inline-error" role="alert">{{ error }} <button v-if="addressLoadFailed" type="button" class="text-button" :disabled="busy" @click="loadAddresses">{{ $t('user.common.refresh') }}</button></p>
        </van-form>
      </section>
      <section class="card withdraw-quote">
        <h2>{{ $t("user.wallet.netAmount") }}</h2>
        <p v-if="quoteLoading" class="hint"><van-loading size="16" /> {{ $t("user.ui.wallet.calculating") }}</p>
        <p v-else-if="quoteError" class="inline-error" role="alert">{{ quoteError }} <button class="text-button" @click="scheduleQuote">{{ $t("user.common.refresh") }}</button></p>
        <dl class="details-list">
          <div><dt>{{ $t('user.v22Accounting.rate') }}</dt><dd>{{ exchangeRate != null ? $t('user.v22Accounting.rateValue', { currency: currencyLabel(form.currency), rate: String(exchangeRate) }) : '—' }}</dd></div>
          <div><dt>{{ $t('user.v22Accounting.debitAmount') }}</dt><dd>{{ quote ? money(quote.accountingAmount) : '—' }} {{ unitName }}</dd></div>
          <div><dt>{{ $t("user.wallet.fee") }}</dt><dd>{{ quote ? money(quote.fee) : "—" }} {{ currencyLabel(form.currency) }}</dd></div>
          <div v-if="quote?.accountingFee != null"><dt>{{ $t('user.v22Accounting.feeAmount') }}</dt><dd>{{ money(quote.accountingFee) }} {{ unitName }}</dd></div>
          <div><dt>{{ $t("user.wallet.netAmount") }}</dt><dd class="positive">{{ quote ? money(quote.netAmount) : "—" }} {{ currencyLabel(form.currency) }}</dd></div>
          <div v-if="quote?.accountingNetAmount != null"><dt>{{ $t('user.v22Accounting.netAmount') }}</dt><dd>{{ money(quote.accountingNetAmount) }} {{ unitName }}</dd></div>
        </dl>
        <p v-if="pendingAttempt && !loading" class="notice-box">{{ $t('user.v22Accounting.pendingWithdrawal') }} <RouterLink to="/records?tab=withdrawals" class="text-button">{{ $t('user.wallet.withdrawRecords') }}</RouterLink></p>
        <van-button block round type="primary" :disabled="confirming || (!pendingAttempt && (!hasAccountingQuote || quoteLoading || !receivingValid || !payPasswordReady))" :loading="loading" @click="submit">{{ $t(pendingAttempt ? 'user.v22Accounting.checkWithdrawal' : 'user.wallet.submitWithdraw') }}</van-button>
        <p class="notice-box">{{ $t("user.vora.wallet.withdrawGuidance") }}</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.withdraw-layout { display: grid; gap: 20px; max-width: 1060px; margin-inline: auto; color: var(--vora-primary); }
.withdraw-layout .card { min-width: 0; padding: 24px; border: 0; border-radius: 14px; background: var(--vora-surface); box-shadow: none; }
.withdraw-layout .withdraw-balance { display: flex; align-items: center; justify-content: space-between; gap: 18px; padding: 22px 24px; background: linear-gradient(110deg,#f5d7cd,#fbe8dd); }
.withdraw-balance > div { display: grid; gap: 8px; min-width: 0; }
.withdraw-balance span { font-size: 13px; }
.withdraw-balance strong { font-size: clamp(26px,3vw,34px); line-height: 1.4; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.withdraw-balance small { font-size: 13px; font-weight: 500; }
.withdraw-balance > .van-icon { font-size: 34px; flex-shrink: 0; color: #af706c; }
.panel-grid { display: grid; grid-template-columns: minmax(0,1.3fr) minmax(0,1fr); gap: 20px; }
.section-heading { align-items: center; gap: 12px; flex-wrap: wrap; margin: 0 0 10px; }
.section-heading h2,.withdraw-quote h2 { margin: 0; font-size: 15px; }
.section-heading .text-button { min-height: 44px; font-size: 12px; }
.withdraw-address-summary { margin: 12px 0 20px; }
.withdraw-address-summary .field-label { color: var(--vora-muted); font-size: 12px; margin: 0; }
.withdraw-address-summary .address-value { margin: 6px 0; padding: 0; color: var(--vora-primary); font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.channel-balance { margin: 6px 0 18px; color: var(--vora-muted); font-size: 12px; line-height: 1.7; }
.withdraw-quote { align-self: start; }
.withdraw-quote .details-list { margin: 14px 0 18px; padding: 10px 14px; border-radius: 10px; background: #f9ece6; }
.withdraw-quote .details-list > div { padding: 8px 0; gap: 16px; border: 0; font-size: 12px; }
.withdraw-quote .details-list dd { min-width: 0; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
.withdraw-quote .positive { color: var(--vora-primary); font-size: 16px; font-weight: 650; }
.withdraw-layout :deep(.van-field) { margin: 14px 0; padding: 14px; border: 1px solid var(--vora-border); border-radius: 8px; background: var(--vora-surface); }
.withdraw-layout :deep(.van-button) { min-height: 48px; border-radius: 999px; border: 0; background: var(--vora-gradient); }
.withdraw-quote .notice-box { margin: 16px 0 0; padding: 0; border: 0; border-radius: 0; background: transparent; color: var(--vora-muted); font-size: 12px; line-height: 1.8; }
.withdraw-bind-notice { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; padding: 12px; border: 0; background: #f9ece6; }
.withdraw-bind-notice > span { flex: 1 1 180px; min-width: 0; white-space: normal; word-break: normal; overflow-wrap: break-word; }
.withdraw-bind-notice > button { flex-shrink: 0; max-width: 100%; min-height: 44px; white-space: normal; word-break: normal; }
@media(max-width:760px) { .withdraw-layout { padding: 16px; gap: 18px; border-radius: 14px; background: var(--vora-surface); } .withdraw-layout .card { padding: 0; border-radius: 0; } .withdraw-layout .withdraw-balance { padding: 18px; border-radius: 12px; } .panel-grid { grid-template-columns: minmax(0,1fr); gap: 22px; } .withdraw-quote h2 { display: none; } .withdraw-quote .details-list { margin-top: 0; } }
@media(min-width:1000px) {
  .withdraw-layout { max-width: 1180px; grid-template-columns: minmax(0,1.45fr) minmax(300px,1fr); column-gap: 26px; row-gap: 0; }
  .withdraw-layout .panel-grid { display: contents; }
  .withdraw-layout .withdraw-balance { grid-column: 2; grid-row: 1; min-height: 154px; padding: 28px; border-radius: 14px 14px 0 0; }
  .withdraw-layout .panel-grid > .card:first-child { grid-column: 1; grid-row: 1 / span 2; padding: 28px; }
  .withdraw-layout .withdraw-quote { grid-column: 2; grid-row: 2; padding: 28px; border-radius: 0 0 14px 14px; }
  .withdraw-quote .details-list { margin-top: 18px; }
  .withdraw-balance strong { font-size: 30px; }
}
</style>
