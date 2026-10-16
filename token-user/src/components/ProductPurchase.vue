<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRouter } from 'vue-router';
import { showSuccessToast } from 'vant';
import { api, ApiError, requestId } from '@/api';
import type { Product, Wallet } from '@/api/types';
import { useSession } from '@/stores/session';
import { money, positiveAmount } from '@/utils/format';
import { canStepProductInput, productInputUnits, stepProductInput } from '@/utils/product-input';
import { clearPendingProductPurchase, readPendingProductPurchase, savePendingProductPurchase } from '@/utils/product-pending';
import { useToken } from '@/composables/useToken';
import { quoteProduct } from '@/api/product';
import type { ProductQuote } from '@/api/product';
const { tokenAmount, tokenValue, unitName, withUnit } = useToken();

const props = defineProps<{ product?: Product; purchasable?: boolean }>();
const { t, locale } = useI18n();
const router = useRouter();
const session = useSession();
const walletLoading = ref(false), walletError = ref('');
const submitting = ref(false), submitError = ref(''), completed = ref(false);
const wallets = ref<Wallet[]>([]);
const amount = ref('');
const nonce = ref<string>(requestId());
const quote = ref<ProductQuote>();
const quoteLoading = ref(false);
const quoteError = ref('');
const uncertainAttempt = ref(false);
const quoteNow = ref(Date.now());
const quoteClockOffset = ref(0);
let quoteVersion = 0;
let quoteController: AbortController | undefined;
let quoteTimer: ReturnType<typeof setTimeout> | undefined;
let clock: ReturnType<typeof setInterval> | undefined;
let contextVersion = 0, readVersion = 0;
let walletController: AbortController | undefined;
let disposed = false;
const wallet = computed(() =>
  wallets.value.find(
    (row) => row.type === 'PROJECT' && row.currency === props.product?.currency
  )
);
const allowed = computed(() =>
  Boolean(session.token && session.member?.id && props.purchasable !== false && props.product?.enabled && props.product.type === 'GPU')
);
const byQuantity = computed(() => props.product?.purchaseMode === 'QUANTITY');
const dailyRateMin = computed(() => quote.value?.dailyRateMin ?? props.product?.dailyRateMin);
const dailyRateMax = computed(() => quote.value?.dailyRateMax ?? props.product?.dailyRateMax);
const minToken = computed(() => tokenValue(props.product?.minAmount, props.product?.currency));
const maxToken = computed(() => tokenValue(props.product?.maxAmount, props.product?.currency));
function boundText(value: unknown, converted: number | null) {
  if (['USD', 'USDT', 'USDC', 'USDT0', 'TOKEN'].includes(props.product?.currency || ''))
    return typeof value === 'number' && Number.isFinite(value) ? value.toFixed(8).replace(/\.?0+$/, '') : String(value ?? '');
  return converted === null ? '' : converted.toFixed(8);
}
const minimumInput = computed(() => byQuantity.value ? '1' : boundText(props.product?.minAmount, minToken.value));
const maximumInput = computed(() => byQuantity.value ? String(Math.min(1_000_000_000, Number(props.product?.maxPurchaseQuantity) || 1_000_000_000)) : boundText(props.product?.maxAmount, maxToken.value));
const quoteExpired = computed(() => !quote.value || (!uncertainAttempt.value && quoteNow.value - quoteClockOffset.value >= new Date(quote.value.expiresAt).getTime()));
const inputLocked = computed(() => submitting.value || walletLoading.value || uncertainAttempt.value || completed.value || !allowed.value);
const canDecrease = computed(() => !inputLocked.value && canStepProductInput(amount.value, -1, minimumInput.value, maximumInput.value));
const canIncrease = computed(() => !inputLocked.value && canStepProductInput(amount.value, 1, minimumInput.value, maximumInput.value));
const rateRange = computed(() => money(dailyRateMin.value, 4) !== money(dailyRateMax.value, 4));
function validAmount(value: string) {
  if (byQuantity.value) return /^[1-9]\d*$/.test(value) && Number(value) <= Number(maximumInput.value);
  const current = productInputUnits(value), minimum = productInputUnits(minimumInput.value), maximum = productInputUnits(maximumInput.value);
  return positiveAmount(value) && current !== null && minimum !== null && maximum !== null && current >= minimum && current <= maximum;
}
function validInput(value: string) {
  return uncertainAttempt.value || validAmount(value);
}
function step(direction: -1 | 1) {
  if (inputLocked.value) return;
  amount.value = stepProductInput(amount.value, direction, minimumInput.value, maximumInput.value);
}
function cancelQuote() {
  quoteVersion++;
  quoteController?.abort();
  quoteController = undefined;
  if (quoteTimer) clearTimeout(quoteTimer);
  quoteTimer = undefined;
  quoteLoading.value = false;
}
function scheduleQuote() {
  cancelQuote();
  quote.value = undefined;
  quoteError.value = '';
  if (!disposed && allowed.value && !completed.value && props.product && validAmount(amount.value)) quoteTimer = setTimeout(() => void refreshQuote(), 300);
}
async function refreshQuote() {
  if (uncertainAttempt.value) return;
  cancelQuote();
  if (disposed || !allowed.value || completed.value || !props.product || !validAmount(amount.value)) return;
  const current = ++quoteVersion;
  const token = session.token;
  const controller = new AbortController();
  quoteController = controller;
  quoteLoading.value = true;
  quoteError.value = '';
  quote.value = undefined;
  try {
    const result = await quoteProduct(props.product.id, byQuantity.value ? { quantity: amount.value } : { amount: amount.value }, controller.signal);
    if (!disposed && current === quoteVersion && !controller.signal.aborted && token === session.token) {
      quote.value = result;
      submitError.value = '';
      quoteNow.value = Date.now();
      quoteClockOffset.value = quoteNow.value - new Date(result.quotedAt).getTime();
      nonce.value = requestId();
    }
  } catch (cause) {
    if (!disposed && current === quoteVersion && !controller.signal.aborted && token === session.token) quoteError.value = cause instanceof Error ? cause.message : t('user.error.request');
  } finally { if (current === quoteVersion) { quoteLoading.value = false; quoteController = undefined; } }
}
async function loadWallets() {
  const current = ++readVersion;
  const currentToken = session.token, language = locale.value;
  walletController?.abort();
  wallets.value = [];
  walletError.value = '';
  if (!currentToken || disposed) { walletLoading.value = false; return; }
  const controller = new AbortController();
  walletController = controller;
  walletLoading.value = true;
  try {
    const balances = await api.wallets(controller.signal);
    if (disposed || current !== readVersion || controller.signal.aborted || currentToken !== session.token) return;
    wallets.value = balances;
  } catch (cause) {
    if (!disposed && current === readVersion && !controller.signal.aborted && currentToken === session.token && language === locale.value)
      walletError.value = cause instanceof Error ? cause.message : t('user.error.request');
  } finally { if (current === readVersion) { walletLoading.value = false; walletController = undefined; } }
}
function resetContext() {
  contextVersion++;
  ++readVersion;
  walletController?.abort();
  cancelQuote();
  if (clock) clearInterval(clock);
  quote.value = undefined;
  wallets.value = [];
  walletError.value = '';
  uncertainAttempt.value = false;
  completed.value = false;
  submitting.value = false;
  submitError.value = '';
  quoteError.value = '';
  nonce.value = requestId();
  amount.value = minimumInput.value;
  if (!props.product || !session.token || !session.member?.id || disposed) { walletLoading.value = false; return; }
  const pending = readPendingProductPurchase(session.member.id, props.product.id);
  if (pending) {
    amount.value = pending.amount;
    quote.value = pending.quote;
    nonce.value = pending.requestId;
    uncertainAttempt.value = true;
  }
  clock = setInterval(() => { quoteNow.value = Date.now(); }, 1000);
  if (!pending) scheduleQuote();
  void loadWallets();
}
watch([() => props.product?.id, () => props.product?.currency, byQuantity, () => session.token, () => session.member?.id], resetContext, { immediate: true, flush: 'sync' });
watch([amount, allowed], () => {
  if (uncertainAttempt.value || submitting.value || completed.value) return;
  nonce.value = requestId();
  scheduleQuote();
}, { flush: 'sync' });
watch(locale, () => {
  if (uncertainAttempt.value || submitting.value || completed.value) return;
  submitError.value = '';
  if (walletLoading.value || walletError.value) void loadWallets();
  scheduleQuote();
});
onUnmounted(() => { disposed = true; contextVersion++; readVersion++; walletController?.abort(); cancelQuote(); if (clock) clearInterval(clock); });
async function submit() {
  if (submitting.value || completed.value || (!uncertainAttempt.value && (walletLoading.value || walletError.value || quoteLoading.value))) return;
  const token = session.token, current = contextVersion;
  const belongs = () => !disposed && current === contextVersion && token === session.token;
  submitting.value = true;
  submitError.value = '';
  try {
    const product = props.product;
    const memberId = session.member?.id;
    if (!product || !session.token || !memberId || (!uncertainAttempt.value && !allowed.value)) throw new Error(t('user.product.notFound'));
    if (!uncertainAttempt.value && !validAmount(amount.value))
      throw new Error(
        byQuantity.value ? t('user.v18Product.quantityInvalid') : t('user.product.amountRange', {
          min: minToken.value,
          max: maxToken.value,
          currency: unitName.value,
        })
      );
    if (quoteExpired.value || !quote.value) throw new Error(t('user.product.quoteExpired'));
    if (!uncertainAttempt.value && Number(quote.value.debitAmount) > Number(wallet.value?.available || 0))
      throw new Error(t('user.ui.products.insufficientBalance'));
    if (!belongs()) return;
    const attemptId = nonce.value;
    try {
      savePendingProductPurchase(memberId, product.id, { requestId: attemptId, amount: amount.value, quote: quote.value });
      uncertainAttempt.value = true;
      await api.order({
        requestId: attemptId,
        productId: product.id,
        quoteId: quote.value.quoteId,
      });
      clearPendingProductPurchase(memberId, product.id, attemptId);
    } catch (cause) {
      // 认证拒绝只说明本次无法查证；原未决购买仍须在重新登录后用原号复核。
      const rejected = cause instanceof ApiError && cause.responseReceived && ![401, 403].includes(cause.code);
      if (rejected) clearPendingProductPurchase(memberId, product.id, attemptId);
      if (!belongs()) return;
      if (rejected) {
        uncertainAttempt.value = false;
        quoteError.value = cause.message;
        quote.value = undefined;
      }
      throw cause;
    }
    if (!belongs()) return;
    uncertainAttempt.value = false;
    completed.value = true;
    showSuccessToast(t('user.product.buySuccess'));
    // 购买结果已经明确，无需再次确认；导航失败时仍保留成功状态，避免再次购买。
    void router.push('/orders').catch(() => {});
  } catch (cause) {
    if (belongs()) {
      const message = cause instanceof Error ? cause.message : t('user.error.request');
      submitError.value = message === quoteError.value ? '' : message;
    }
  } finally { if (belongs()) submitting.value = false; }
}
</script>

<template>
  <section v-if="product" class="card ui-inline-purchase">
    <template v-if="product">
      <div class="ui-inline-purchase-heading">
        <h2>
          {{ $t('user.v20Product.purchaseTitle') }}
        </h2>
      </div>
      <div class="ui-purchase-wallet">
        <span>{{ $t('user.wallet.PROJECT') }}</span
        ><strong>{{ tokenAmount(wallet?.available, wallet?.currency) }} {{ unitName }}</strong
        ><small>{{ $t('user.product.walletBalance') }}</small>
      </div>
      <p v-if="walletError" class="inline-error">
        {{ walletError
        }}<button type="button" class="text-button" @click="loadWallets">
          {{ $t('user.common.refresh') }}
        </button>
      </p>
      <van-form @submit="submit">
        <div class="ui-purchase-input-row">
        <button type="button" class="ui-input-step" :disabled="!canDecrease" :aria-label="$t('user.v20Product.decrease')" @click="step(-1)"><van-icon name="minus" /></button>
        <van-field
          v-model="amount"
          type="number"
          :label="$t(byQuantity ? 'user.v18Product.quantity' : 'user.common.amount')"
          :disabled="inputLocked"
          :placeholder="
            byQuantity ? $t('user.v18Product.quantityHint') : $t('user.product.amountRange', {
              min: minToken,
              max: maxToken,
              currency: unitName,
            })
          "
          :rules="[{ validator: validInput, message: $t(byQuantity ? 'user.v18Product.quantityInvalid' : 'user.common.amountInvalid') }]"
          ><template #extra>{{ byQuantity ? $t('user.v18Product.units') : unitName }}</template></van-field
        >
        <button type="button" class="ui-input-step" :disabled="!canIncrease" :aria-label="$t('user.v20Product.increase')" @click="step(1)"><van-icon name="plus" /></button>
        </div>
        <dl class="details-list ui-purchase-rules">
          <template v-if="byQuantity">
            <div><dt>{{ $t('user.v18Product.unitPrice') }}</dt><dd>{{ money(quote?.unitPriceToken ?? product.unitPrice) }} {{ unitName }}</dd></div>
            <div v-if="quote"><dt>{{ $t('user.v18Product.quantity') }}</dt><dd>{{ quote.quantity }} {{ $t('user.v18Product.units') }}</dd></div>
          </template>
          <div>
            <dt>{{ $t('user.ui.products.totalDebit') }}</dt>
            <dd>
              {{ quote ? money(quote.debitToken) : '—' }} {{ unitName }}
            </dd>
          </div>
          <div>
            <dt>{{ $t('user.product.duration') }}</dt>
            <dd>{{ quote?.durationDays ?? product.durationDays }} {{ $t('user.common.days') }}</dd>
          </div>
          <div>
            <dt>{{ $t('user.v6.dailyRate') }}</dt>
            <dd>
              {{ money(dailyRateMin, 4) }}<template v-if="rateRange">–{{ money(dailyRateMax, 4) }}</template>%
            </dd>
          </div>
          <div>
            <dt>{{ $t('user.product.rules') }}</dt>
            <dd>{{ $t(`user.product.mode.${quote?.settlementMode ?? product.settlementMode}`) }}</dd>
          </div>
        </dl>
        <div v-if="Number(product.maxPurchaseAmount) > 0 || Number(product.maxPurchaseQuantity) > 0" class="ui-purchase-quota">
          <p>{{ $t('user.v18Product.lifetimeLimit') }}</p>
          <p v-if="Number(product.maxPurchaseAmount) > 0">{{ withUnit($t('user.v18Product.amountLimit', { limit: money(product.maxPurchaseAmount) })) }}<template v-if="quote?.quota?.amountComplete"> · {{ withUnit($t('user.v18Product.amountRemaining', { remaining: money(quote.quota.remainingAmountToken) })) }}</template></p>
          <p v-if="Number(product.maxPurchaseQuantity) > 0">{{ $t('user.v18Product.quantityLimit', { limit: product.maxPurchaseQuantity }) }}<template v-if="quote?.quota"> · {{ $t('user.v18Product.quantityRemaining', { remaining: quote.quota.remainingQuantity }) }}</template></p>
        </div>
        <section v-if="quote && !quoteExpired" class="ui-purchase-estimate" aria-live="polite">
          <span>{{ $t('user.v16Content.expectedIncome') }}</span>
          <strong>{{ money(quote.expectedIncomeMinToken) }}<template v-if="Number(quote.expectedIncomeMinToken) !== Number(quote.expectedIncomeMaxToken)">–{{ money(quote.expectedIncomeMaxToken) }}</template> <small>{{ unitName }}</small></strong>
          <p>{{ withUnit($t('user.v16Content.expectedIncomeBasis', { amount: money(quote.priceToken), days: quote.durationDays })) }}</p>
          <dl>
            <div><dt>{{ $t('user.v16Content.principalReturn') }}</dt><dd>{{ money(quote.principalReturnToken) }} {{ unitName }}</dd></div>
          </dl>
          <p>{{ $t('user.v16Content.expectedIncomeHint') }}</p>
        </section>
        <p v-if="quoteLoading" class="muted" role="status">{{ $t('user.product.quoting') }}</p>
        <p v-if="quoteError" class="inline-error" role="alert">{{ quoteError }}</p>
        <p v-if="!quoteLoading && (quoteError || (quote && quoteExpired))" class="ui-purchase-note">
          <span v-if="quote && quoteExpired">{{ $t('user.product.quoteExpired') }}</span>
          <button type="button" class="text-button" @click="refreshQuote">{{ $t('user.product.refreshQuote') }}</button>
        </p>
        <p v-if="uncertainAttempt && !submitting" class="ui-purchase-note">{{ $t('user.v20Product.retryHint') }}</p>
        <p v-if="submitError" class="inline-error" role="alert">
          {{ submitError }}
        </p>
        <p v-if="completed" class="ui-purchase-success">{{ $t('user.product.buySuccess') }} <RouterLink to="/orders">{{ $t('user.ui.products.viewOrders') }}</RouterLink></p>
        <van-button
          block
          round
          type="primary"
          native-type="submit"
          :loading="submitting"
          :disabled="completed || (!uncertainAttempt && (!allowed || walletLoading || !!walletError || quoteLoading || quoteExpired || !!quoteError))"
          >{{ $t(uncertainAttempt ? 'user.v20Product.retryPurchase' : 'user.product.buy') }}</van-button
        >
      </van-form>
    </template>
  </section>
</template>
<style scoped>
.ui-inline-purchase { min-width: 0; margin: 0; padding: 24px; background: var(--vora-surface); border: none; border-radius: 14px; color: var(--vora-ink); box-shadow: none; }
.ui-inline-purchase-heading h2 { margin: 0 0 18px; font-size: 20px; color: inherit; }
.ui-purchase-wallet { display: grid; grid-template-columns: 1fr auto; align-items: baseline; gap: 6px 12px; padding: 0 0 18px; border-bottom: 1px solid var(--vora-border); }
.ui-purchase-wallet > span { font-size: 13px; color: var(--vora-muted); }
.ui-purchase-wallet > strong { font-size: 23px; line-height: 1.4; color: var(--vora-ink); text-align: right; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
.ui-purchase-wallet > small { grid-column: 1 / -1; text-align: right; font-size: 12px; color: var(--vora-muted); }
.ui-purchase-input-row { display: grid; grid-template-columns: 44px minmax(0, 1fr) 44px; align-items: center; gap: 8px; margin: 18px 0 14px; }
.ui-input-step { width: 44px; height: 48px; border: none; border-radius: 10px; background: var(--vora-soft-surface); color: var(--vora-primary); font-size: 20px; cursor: pointer; }
.ui-input-step:disabled { opacity: .4; cursor: not-allowed; }
.ui-purchase-input-row :deep(.van-field) { min-width: 0; min-height: 48px; padding: 12px 10px; background: var(--vora-bg); border: 1px solid var(--vora-border); border-radius: 10px; }
.ui-purchase-input-row :deep(.van-field__label) { width: auto; max-width: 34%; margin-right: 8px; color: var(--vora-muted); }
.ui-purchase-input-row :deep(.van-field__control) { min-width: 0; font-size: 16px; font-weight: 600; text-align: center; color: var(--vora-ink); }
.ui-purchase-input-row :deep(.van-field:focus-within) { outline: 2px solid var(--vora-primary); outline-offset: 2px; }
.ui-purchase-input-row :deep(.van-field__right-icon), .ui-purchase-input-row :deep(.van-field__extra) { color: var(--vora-muted); font-size: 12px; }
.ui-purchase-rules { margin: 0 0 14px; }
.ui-purchase-rules > div { padding: 11px 0; border-color: var(--vora-border); gap: 14px; }
.ui-purchase-rules dt { color: var(--vora-muted); font-size: 13px; }
.ui-purchase-rules dd { color: var(--vora-ink); font-size: 13px; }
.ui-purchase-success { color: var(--vora-success); font-size: 14px; line-height: 1.7; }
.ui-purchase-estimate { margin: 14px 0 20px; padding: 16px 0; border-top: 1px solid var(--vora-border); border-bottom: 1px solid var(--vora-border); }
.ui-purchase-estimate > span { display: block; color: var(--vora-muted); font-size: 13px; }
.ui-purchase-estimate > strong { display: block; color: var(--vora-success); font-size: 28px; line-height: 1.6; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
.ui-purchase-estimate strong small { font-size: 13px; font-weight: 500; }
.ui-purchase-estimate p { margin: 6px 0 0; font-size: 12px; line-height: 1.7; color: var(--vora-muted); }
.ui-purchase-estimate dl { margin: 14px 0 0; }
.ui-purchase-estimate dl > div { display: flex; justify-content: space-between; gap: 12px; font-size: 13px; }
.ui-purchase-estimate dt { color: var(--vora-muted); }
.ui-purchase-estimate dd { margin: 0; text-align: right; color: var(--vora-ink); }
.ui-purchase-quota { margin: 12px 0; color: var(--vora-muted); font-size: 12px; line-height: 1.7; }
.ui-purchase-quota p { margin: 2px 0; }
.ui-purchase-note { font-size: 12px; line-height: 1.7; color: var(--vora-muted); }
.ui-inline-purchase .text-button { min-height: 44px; color: var(--vora-primary); }
.ui-inline-purchase :deep(.van-button--primary) { height: 48px; border: none; border-radius: 999px; background: var(--vora-gradient); color: white; font-size: 16px; font-weight: 600; }
@media (max-width: 640px) { .ui-inline-purchase { padding: 19px 16px; } .ui-inline-purchase-heading h2 { font-size: 18px; } .ui-purchase-wallet > strong { font-size: 20px; } }
</style>
