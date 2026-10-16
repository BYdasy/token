<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { showSuccessToast } from "vant";
import { api, ApiError, requestId } from "@/api";
import { fetchTransferOptions, fetchTransferQuote, type TransferOptions, type TransferQuote } from "@/api/transfers";
import type { Wallet } from "@/api/types";
import { useTask } from "@/composables/useTask";
import { useSession } from "@/stores/session";
import { useToken } from '@/composables/useToken';
import { money, positiveAmount, validPassword } from "@/utils/format";
import { registerReloadBlocker } from '@/utils/resource-recovery';
import WalletSelector from "./WalletSelector.vue";
import PaymentPasswordField from "./PaymentPasswordField.vue";
const props = defineProps<{ wallets: Wallet[] }>();
const emit = defineEmits<{ changed: [] }>();
const { t, locale } = useI18n();
const session = useSession();
const { unitName } = useToken();
const { loading, error, run } = useTask();
const options = ref<TransferOptions>();
const optionsLoading = ref(false), optionsError = ref("");
const quote = ref<TransferQuote>(), quoteLoading = ref(false), quoteError = ref("");
const form = reactive({ fromWallet: "", toWallet: "", amount: "", payPassword: "" });
const payPasswordReady = computed(() => Boolean(session.member?.hasPayPassword) && validPassword(form.payPassword));
const idempotency = ref(requestId());
let disposed = false;
let controller: AbortController | undefined;
let pending: Promise<void> | undefined;
let quoteController: AbortController | undefined, quoteTimer: ReturnType<typeof setTimeout> | undefined;
let quoteVersion = 0;
const pendingAttempt = ref<Record<string, unknown>>();
const stopReloadBlocker = registerReloadBlocker(() => loading.value || Boolean(pendingAttempt.value));
const inputLocked = computed(() => loading.value || Boolean(pendingAttempt.value));
const directions = computed(() => options.value?.directions || []);
function walletBalance(type: string) {
  const native = props.wallets.find(wallet => wallet.type === type && wallet.currency === options.value?.currency)?.available || 0;
  return options.value?.assetRate ? money(Number(native) * Number(options.value.assetRate), 2) : "—";
}
const fromOptions = computed(() => [...new Set(directions.value.map(row => row.fromWallet))].map(type => ({ type, balance: walletBalance(type) })));
const toOptions = computed(() => [...new Set(directions.value.filter(row => row.fromWallet === form.fromWallet).map(row => row.toWallet))].map(type => ({ type, balance: walletBalance(type) })));
const validDirection = computed(() => directions.value.some(row => row.fromWallet === form.fromWallet && row.toWallet === form.toWallet));
function syncSelection() {
  if (pendingAttempt.value) return;
  if (!fromOptions.value.some(row => row.type === form.fromWallet)) form.fromWallet = fromOptions.value[0]?.type || "";
  if (!toOptions.value.some(row => row.type === form.toWallet)) form.toWallet = toOptions.value[0]?.type || "";
}
watch(() => form.fromWallet, syncSelection);
watch(form, () => { if (!pendingAttempt.value) idempotency.value = requestId(); });
function clearQuote() {
  ++quoteVersion; quoteController?.abort(); clearTimeout(quoteTimer); quoteTimer = undefined;
  quote.value = undefined; quoteError.value = ""; quoteLoading.value = false;
}
async function refreshQuote(version = ++quoteVersion) {
  if (disposed || pendingAttempt.value || !positiveAmount(form.amount) || !validDirection.value || !options.value?.assetRate) return;
  const current = new AbortController(), token = session.token;
  quoteController?.abort(); quoteController = current; quoteLoading.value = true; quoteError.value = "";
  try {
    const result = await fetchTransferQuote({ fromWallet: form.fromWallet, toWallet: form.toWallet, amount: form.amount,
      amountUnit: "TOKEN", expectedAssetRate: options.value.assetRate }, current.signal);
    if (disposed || current.signal.aborted || version !== quoteVersion || token !== session.token) return;
    quote.value = result;
  } catch (cause) {
    if (!disposed && !current.signal.aborted && version === quoteVersion && token === session.token) {
      quote.value = undefined; quoteError.value = cause instanceof Error ? cause.message : t("user.error.request");
    }
  } finally { if (version === quoteVersion) quoteLoading.value = false; }
}
function scheduleQuote() {
  if (pendingAttempt.value) return;
  clearQuote();
  if (!positiveAmount(form.amount) || !validDirection.value || !options.value?.assetRate) return;
  quoteLoading.value = true;
  const version = quoteVersion;
  quoteTimer = setTimeout(() => { void refreshQuote(version); }, 250);
}
watch(() => [form.amount, form.fromWallet, form.toWallet, options.value?.assetRate, validDirection.value, locale.value], scheduleQuote, { flush: 'sync' });
watch(() => session.token, () => {
  controller?.abort(); controller = undefined; pending = undefined;
  pendingAttempt.value = undefined; clearQuote(); options.value = undefined; optionsLoading.value = false;
  Object.assign(form, { fromWallet: "", toWallet: "", amount: "", payPassword: "" });
  error.value = "";
  if (session.token && !disposed) void loadOptions();
}, { flush: 'sync' });
function loadOptions(background = false) {
  if (pending || disposed) return pending;
  const current = new AbortController(), token = session.token;
  controller = current; optionsLoading.value = true; optionsError.value = "";
  pending = (async () => {
    try {
      const result = await fetchTransferOptions(current.signal, background);
      if (disposed || current.signal.aborted || token !== session.token) return;
      options.value = result; syncSelection();
    } catch (cause) {
      if (!current.signal.aborted && !disposed && token === session.token) {
        optionsError.value = cause instanceof Error ? cause.message : t("user.error.request");
        options.value = undefined; syncSelection();
      }
    } finally { if (controller === current) { optionsLoading.value = false; pending = undefined; } }
  })();
  return pending;
}
function refreshWalletChoices() { if (!inputLocked.value) { void loadOptions(); emit('changed'); } }
function visible() { if (!document.hidden && !inputLocked.value) { void loadOptions(true); emit('changed'); scheduleQuote(); } }
async function submit() {
  if (disposed || loading.value || (!pendingAttempt.value && (!payPasswordReady.value || !validDirection.value || !quote.value || quoteLoading.value))) return;
  await run(async () => {
    const token = session.token;
    if (!pendingAttempt.value) {
      if (!positiveAmount(form.amount) || !quote.value) throw new Error(t("user.common.amountInvalid"));
      const selection = { ...form }, expectedAssetRate = options.value?.assetRate, displayed = { ...quote.value };
      await loadOptions();
      if (disposed || token !== session.token) return;
      if (optionsError.value) throw new Error(optionsError.value);
      if (!directions.value.some(row => row.fromWallet === selection.fromWallet && row.toWallet === selection.toWallet))
        throw new Error(t("user.v10.wallet.directionUnavailable"));
      if (!expectedAssetRate || String(expectedAssetRate) !== String(options.value?.assetRate))
        throw new Error(t("user.v10.wallet.rateChanged"));
      pendingAttempt.value = { requestId: idempotency.value, ...selection, amountUnit: "TOKEN", expectedAssetRate,
        expectedFee: displayed.fee, expectedNetAmount: displayed.netAmount };
    }
    const attempt = pendingAttempt.value;
    try { await api.transfer(attempt); }
    catch (cause) {
      if (disposed || token !== session.token) return;
      // 响应不明时保留原报价和请求号，只能重试同一请求，避免再次扣款。
      if (cause instanceof ApiError && cause.responseReceived && ![401, 403].includes(cause.code) && cause.code < 500) {
        pendingAttempt.value = undefined; idempotency.value = requestId(); scheduleQuote();
      }
      throw cause;
    }
    if (disposed || token !== session.token) return;
    pendingAttempt.value = undefined; idempotency.value = requestId(); form.amount = ""; form.payPassword = "";
    showSuccessToast(t("user.wallet.transferSuccess")); emit("changed");
  });
}
onMounted(() => { void loadOptions(); document.addEventListener("visibilitychange", visible); });
onUnmounted(() => { stopReloadBlocker(); disposed = true; controller?.abort(); clearQuote(); pendingAttempt.value = undefined; document.removeEventListener("visibilitychange", visible); });
</script>
<template>
  <section class="card narrow-card vora-transfer-card">
    <div class="vora-transfer-heading"><span aria-hidden="true"><van-icon name="exchange" /></span><p>{{ $t('user.vora.wallet.transferIntro') }}</p></div>
    <div v-if="optionsError" class="inline-error">{{ optionsError }} <button type="button" class="text-button" @click="loadOptions()">{{ $t("user.common.refresh") }}</button></div>
    <div v-else-if="optionsLoading && !options" class="empty-state"><van-loading /></div>
    <van-empty v-else-if="!directions.length" :description="$t('user.v10.wallet.noDirections')" />
    <van-form v-else @submit="submit">
      <div class="vora-transfer-direction"><WalletSelector v-model="form.fromWallet" :label="$t('user.wallet.from')" :options="fromOptions" :disabled="inputLocked" @open="refreshWalletChoices" />
      <div class="transfer-arrow"><van-icon name="down" /></div>
      <WalletSelector v-model="form.toWallet" :label="$t('user.wallet.to')" :options="toOptions" :disabled="inputLocked" @open="refreshWalletChoices" /></div>
      <div class="balance-note">{{ $t("user.wallet.available") }} <strong>{{ walletBalance(form.fromWallet) }} {{ unitName }}</strong></div>
      <van-field v-model="form.amount" :label="$t('user.common.amount')" type="number" :disabled="inputLocked" :rules="[{ validator: positiveAmount, message: $t('user.common.amountInvalid') }]">
        <template #right-icon><span>{{ unitName }}</span></template>
      </van-field>
      <div v-if="positiveAmount(form.amount)" class="transfer-preview" aria-live="polite">
        <div v-if="quoteLoading"><van-loading size="16" /> {{ $t('user.v24Finance.calculating') }}</div>
        <p v-else-if="quoteError" class="inline-error">{{ quoteError }} <button type="button" class="text-button" @click="scheduleQuote">{{ $t('user.common.refresh') }}</button></p>
        <template v-else-if="quote">
          <div><span>{{ $t('user.v24Finance.serviceFee') }}</span><strong>{{ money(quote.fee, 8) }} {{ unitName }}</strong></div>
          <div><span>{{ $t('user.v24Finance.receivedAmount') }}</span><strong>{{ money(quote.netAmount, 8) }} {{ unitName }}</strong></div>
        </template>
      </div>
      <PaymentPasswordField v-model="form.payPassword" :disabled="inputLocked" />
      <p v-if="error" class="inline-error">{{ error }}</p>
      <p v-if="pendingAttempt && !loading" class="notice-box">{{ $t('user.v24Finance.pendingTransfer') }}</p>
      <van-button block round type="primary" native-type="submit" :loading="loading" class="space-top" :disabled="!pendingAttempt && (!validDirection || optionsLoading || !!optionsError || !payPasswordReady || !quote || quoteLoading || !!quoteError)">{{ $t(pendingAttempt ? 'user.v24Finance.retryTransfer' : 'user.common.confirm') }}</van-button>
    </van-form>
  </section>
</template>
<style scoped>
.vora-transfer-card { width: 100%; max-width: 700px; margin-inline: auto; padding: 0 24px 24px; border: 0; border-radius: 16px; background: var(--vora-surface); box-shadow: none; color: var(--vora-primary); overflow: visible; }
.vora-transfer-heading { position: relative; display: flex; flex-direction: row-reverse; align-items: center; justify-content: space-between; gap: 20px; margin: 0 -24px 26px; padding: 28px 24px; border-radius: 16px 16px 0 0; background: linear-gradient(110deg,#f9dfd2,#fdf4ed); }
.vora-transfer-heading > span { display: grid; place-items: center; flex-shrink: 0; width: 58px; height: 58px; border-radius: 50%; background: var(--vora-gradient); color: var(--vora-surface); font-size: 30px; transform: rotate(-18deg); }
.vora-transfer-heading h2 { margin: 0 0 8px; font-family: 'Vora Display',Georgia,'Noto Serif SC',serif; font-size: 24px; }
.vora-transfer-heading p { max-width: 36ch; margin: 0; color: var(--vora-muted); font-size: 12px; line-height: 1.7; }
.vora-transfer-direction { display: grid; grid-template-columns: minmax(0,1fr); gap: 10px; }
.transfer-arrow { display: grid; place-items: center; justify-self: center; width: 32px; height: 32px; margin: 0; border: 1px solid var(--vora-border); border-radius: 50%; background: var(--vora-surface); color: #8e6670; }
.balance-note { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 8px; margin: 18px 0; color: var(--vora-muted); font-size: 12px; line-height: 1.7; }
.balance-note strong { color: var(--vora-primary); font-variant-numeric: tabular-nums; }
.transfer-preview { margin: 18px 0; padding: 12px 16px; border-radius: 10px; background: #f9ece6; }
.transfer-preview > div { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 7px 0; font-size: 13px; }
.transfer-preview strong { text-align: end; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.vora-transfer-card :deep(.van-field) { margin: 14px 0; padding: 14px; border: 1px solid var(--vora-border); border-radius: 8px; background: var(--vora-surface); }
.vora-transfer-card :deep(.van-button) { min-height: 48px; border-radius: 999px; background: var(--vora-gradient); border: 0; }
@media(max-width:620px) { .vora-transfer-card { padding: 0 18px 22px; } .vora-transfer-heading { margin: 0 -18px 22px; padding: 24px 18px; gap: 15px; } .vora-transfer-heading > span { width: 48px; height: 48px; font-size: 25px; } .vora-transfer-heading h2 { font-size: 22px; } }
@media(min-width:1000px) {
  .vora-transfer-card { display: grid; grid-template-columns: minmax(240px,.7fr) minmax(0,1.5fr); gap: 0; max-width: 1160px; padding: 0; }
  .vora-transfer-heading { flex-direction: column; justify-content: flex-start; align-items: flex-start; gap: 32px; margin: 0; padding: 40px 32px; border-radius: 16px 0 0 16px; }
  .vora-transfer-heading > span { width: 76px; height: 76px; font-size: 36px; }
  .vora-transfer-heading p { font-size: 16px; line-height: 1.9; color: var(--vora-primary); }
  .vora-transfer-card > :deep(.van-form) { min-width: 0; padding: 32px; }
  .vora-transfer-card > .inline-error,.vora-transfer-card > .empty-state,.vora-transfer-card > :deep(.van-empty) { margin: 32px; }
  .vora-transfer-direction { grid-template-columns: minmax(0,1fr) 32px minmax(0,1fr); gap: 12px; align-items: center; }
  .transfer-arrow { transform: rotate(-90deg); }
  .vora-transfer-direction :deep(.wallet-selector) { min-height: 118px; }
  .vora-transfer-direction :deep(.wallet-selector-icon) { display: none; }
  .vora-transfer-direction :deep(.wallet-selector-content) { gap: 10px; }
  .vora-transfer-card .balance-note { margin: 24px 0; }
}
</style>
