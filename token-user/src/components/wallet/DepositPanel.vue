<script setup lang="ts">
const depositScene = `${import.meta.env.BASE_URL}images/vora/home.png`;
import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute } from "vue-router";
import { showConfirmDialog, showSuccessToast } from "vant";
import { api, requestId } from "@/api";
import { useCurrencies } from "@/composables/useCurrencies";
import { useChannelLabels } from "@/composables/useChannelLabels";
import { useTask } from "@/composables/useTask";
import { useSession } from "@/stores/session";
import { copyText, positiveAmount } from "@/utils/format";
import { currencyLabel } from "@/utils/currency";
import QrCode from "@/components/QrCode.vue";
import ChannelDropdowns from "./ChannelDropdowns.vue";

const route = useRoute();
const emit = defineEmits<{ changed: [] }>();
const { t } = useI18n();
const labels = useChannelLabels();
const { loading, error, run } = useTask();
const session = useSession();
let disposed = false;
// 保留已有入口的合法目标钱包，普通充值入口继续使用当前算力钱包默认值。
const requestedWallet = String(route.query.walletType || "");
const form = reactive({
  walletType: ["COMPUTE", "PROJECT"].includes(requestedWallet)
    ? requestedWallet
    : "COMPUTE",
  currency: String(route.query.currency || ""),
  network: "",
  amount: "",
});
const {
  availableChannels,
  task: channelTask,
  refresh,
} = useCurrencies("DEPOSIT", () => form.walletType);
const depositWallets = ["COMPUTE", "PROJECT"];
function supportsWallet(
  channel: { supportAccountTypes: string },
  wallet: string
) {
  return channel.supportAccountTypes
    .split(",")
    .map((value) => value.trim())
    .includes(wallet);
}
const channels = computed(() =>
  availableChannels.value.filter((channel) =>
    depositWallets.some((wallet) => supportsWallet(channel, wallet))
  )
);
const selectedChannel = computed(() =>
  channels.value.find(
    (item) => item.currency === form.currency && item.network === form.network
  )
);
const walletOptions = computed(() =>
  depositWallets.filter(
    (wallet) =>
      selectedChannel.value && supportsWallet(selectedChannel.value, wallet)
  )
);
const hasSelectedWallet = computed(() =>
  walletOptions.value.includes(form.walletType)
);
const automatic = computed(() => selectedChannel.value?.depositMode === "MCAG");
const result = ref<Record<string, unknown>>();
const addressLoading = ref(false),
  addressError = ref("");
const addressPreparing = ref(false);
const confirming = ref(false);
const busy = computed(() => loading.value || confirming.value);
const idempotency = ref(requestId());
let version = 0;
let addressAttempts = 0;
// 网络监听暂不可用时有界刷新，自动检查不延长用户会话。
const addressRetryLimit = 30;
let timer: ReturnType<typeof setTimeout> | undefined;
let addressController: AbortController | undefined;
const currencies = computed(() => [
  ...new Set(channels.value.map((item) => item.currency)),
]);
const networks = computed(() =>
  channels.value
    .filter((item) => item.currency === form.currency)
    .map((item) => item.network)
);
watch(
  currencies,
  (values) => {
    if (!values.includes(form.currency)) {
      const requested = String(route.query.currency || "");
      form.currency = values.includes(requested) ? requested : values[0] || "";
    }
  },
  { immediate: true }
);
watch(
  networks,
  (values) => {
    if (!values.includes(form.network)) form.network = values[0] || "";
  },
  { immediate: true }
);
watch(
  walletOptions,
  (values) => {
    if (!values.includes(form.walletType))
      form.walletType = values.includes(requestedWallet)
        ? requestedWallet
        : values[0] || "";
  },
  { immediate: true }
);
function invalidateResult() {
  idempotency.value = requestId();
  result.value = undefined;
  version++;
  clearTimeout(timer);
  addressController?.abort();
  addressLoading.value = false;
  addressPreparing.value = false;
  addressAttempts = 0;
  addressError.value = "";
  error.value = "";
}
function scheduleAddress() {
  invalidateResult();
  if (
    automatic.value &&
    selectedChannel.value &&
    hasSelectedWallet.value &&
    session.token
  ) {
    addressLoading.value = true;
    // 同一轮币种和网络联动只获取最终所选通道，不生成中间选择的地址。
    timer = setTimeout(() => void obtainAddress(), 120);
  }
}
watch(
  () => [
    form.currency,
    form.network,
    form.walletType,
    automatic.value,
    hasSelectedWallet.value,
  ],
  scheduleAddress,
  { flush: "sync" }
);
watch(
  () => form.amount,
  () => {
    idempotency.value = requestId();
    if (!automatic.value) result.value = undefined;
  }
);
watch(() => session.token, scheduleAddress, { flush: "sync" });
async function obtainAddress(background = false) {
  if (
    disposed ||
    document.hidden ||
    !automatic.value ||
    !selectedChannel.value ||
    !hasSelectedWallet.value ||
    !session.token
  )
    return;
  clearTimeout(timer);
  if (!background) addressAttempts = 0;
  const current = ++version,
    token = session.token;
  const context = {
    currency: form.currency,
    network: form.network,
    walletType: form.walletType,
  };
  addressController?.abort();
  const controller = new AbortController();
  addressController = controller;
  addressLoading.value = true;
  addressError.value = "";
  result.value = undefined;
  try {
    const address = await api.depositAddress(
      context,
      controller.signal,
      background
    );
    if (disposed || current !== version || token !== session.token) return;
    if (
      address.currency !== context.currency ||
      address.network !== context.network ||
      address.walletType !== context.walletType
    )
      throw new Error(t("user.payment.addressUnavailable"));
    addressPreparing.value = !address.monitoringReady && address.status === "ACTIVE";
    if (addressPreparing.value) {
      if (++addressAttempts < addressRetryLimit && !document.hidden)
        timer = setTimeout(() => void obtainAddress(true), Math.min(15000, 3000 + addressAttempts * 1000));
      return;
    }
    if (address.status !== "ACTIVE" || !address.monitoringReady || !address.address)
      throw new Error(t("user.payment.addressUnavailable"));
    result.value = { ...address };
  } catch (cause) {
    if (
      !disposed &&
      current === version &&
      !controller.signal.aborted &&
      token === session.token
    )
      addressError.value =
        cause instanceof Error ? cause.message : t("user.error.request");
  } finally {
    if (current === version) addressLoading.value = false;
  }
}
async function submit() {
  if (
    disposed ||
    busy.value ||
    automatic.value ||
    !selectedChannel.value ||
    !hasSelectedWallet.value ||
    channelTask.loading.value ||
    !positiveAmount(form.amount)
  )
    return;
  const token = session.token;
  const submission = { requestId: idempotency.value, ...form };
  confirming.value = true;
  try {
    await showConfirmDialog({
      title: t("user.wallet.createDeposit"),
      message: `${t("user.common.amount")}: ${submission.amount} ${
        labels.symbol(selectedChannel.value)
      }\n${t("user.common.network")}: ${labels.network(selectedChannel.value)}\n${t(
        "user.v14.depositWallet"
      )}: ${t("user.wallet." + submission.walletType)}`,
      confirmButtonText: t("user.common.confirm"),
      cancelButtonText: t("user.common.cancel"),
    });
  } catch {
    return;
  } finally {
    confirming.value = false;
  }
  if (
    disposed ||
    token !== session.token ||
    submission.requestId !== idempotency.value
  )
    return;
  await run(async () => {
    try {
      const response = await api.deposit(submission);
      if (
        disposed ||
        token !== session.token ||
        submission.requestId !== idempotency.value
      )
        return;
      result.value = response;
      idempotency.value = requestId();
      showSuccessToast(t("user.wallet.depositCreated"));
      emit("changed");
    } catch (cause) {
      if (!disposed && token === session.token) throw cause;
    }
  });
}
function copy(value: unknown) {
  void run(async () => {
    await copyText(String(value));
    showSuccessToast(t("user.common.copied"));
  });
}
const paymentUrl = computed(() => {
  try {
    const url = new URL(String(result.value?.paymentUrl || ""));
    return url.protocol === "https:" ? url.href : undefined;
  } catch {
    return undefined;
  }
});
const address = computed(() =>
  String(result.value?.address || result.value?.paymentAddress || "")
);
function visibilityChanged() {
  if (document.hidden) {
    clearTimeout(timer);
    addressController?.abort();
    version++;
    addressLoading.value = false;
  } else if (
    automatic.value &&
    !result.value &&
    !addressError.value &&
    addressAttempts < addressRetryLimit
  )
    void obtainAddress(true);
}
onMounted(() =>
  document.addEventListener("visibilitychange", visibilityChanged)
);
onUnmounted(() => {
  disposed = true;
  invalidateResult();
  document.removeEventListener("visibilitychange", visibilityChanged);
});
</script>

<template>
  <div class="deposit-layout">
    <section class="card deposit-main">
      <ChannelDropdowns
        numbered
        :channels="channels"
        :currency="form.currency"
        :network="form.network"
        :disabled="busy"
        @select="
          (channel) => {
            form.currency = channel.currency;
            form.network = channel.network;
          }
        "
      />
      <fieldset
        v-if="walletOptions.length"
        class="deposit-wallets"
        :disabled="busy"
      >
        <legend class="field-label"><span class="deposit-step-number" aria-hidden="true">3</span>{{ $t("user.v14.depositWallet") }}</legend>
        <label
          v-for="wallet in walletOptions"
          :key="wallet"
          class="deposit-wallet-option"
          :class="{ selected: form.walletType === wallet }"
        >
          <input
            v-model="form.walletType"
            type="radio"
            name="deposit-wallet"
            :value="wallet"
          />
          <van-icon :name="wallet === 'COMPUTE' ? 'cluster-o' : 'balance-o'" />
          <span>{{ $t("user.wallet." + wallet) }}</span
          ><van-icon v-if="form.walletType === wallet" name="checked" />
        </label>
      </fieldset>
      <p v-if="channelTask.loading.value" class="hint">
        {{ $t("user.common.loading") }}
      </p>
      <p v-if="channelTask.error.value" class="inline-error">
        {{ channelTask.error.value }}
        <button
          type="button"
          class="text-button"
          :disabled="busy"
          @click="refresh"
        >
          {{ $t("user.common.refresh") }}
        </button>
      </p>
      <p
        v-if="!networks.length && !channelTask.loading.value"
        class="inline-error"
      >
        {{ $t("user.wallet.noNetwork") }}
      </p>
      <section v-if="selectedChannel && hasSelectedWallet" class="deposit-credit-step">
      <h2 class="deposit-step-heading"><span class="deposit-step-number" aria-hidden="true">4</span>{{ $t('user.refine.deposit.credit') }}</h2>
      <van-form v-if="selectedChannel && !automatic" @submit="submit">
        <van-field
          v-model="form.amount"
          :disabled="busy"
          :label="`${$t('user.common.amount')} (${currencyLabel(form.currency)})`"
          :placeholder="$t('user.v13.wallet.amountPlaceholder')"
          type="number"
          :rules="[
            {
              validator: positiveAmount,
              message: $t('user.common.amountInvalid'),
            },
          ]"
        />
        <p class="hint">{{ $t("user.wallet.depositPending") }}</p>
        <van-button
          type="primary"
          block
          round
          native-type="submit"
          class="space-top"
          :loading="loading"
          :disabled="channelTask.loading.value || confirming"
          >{{ $t("user.wallet.createDeposit") }}</van-button
        >
      </van-form>
      <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      <div v-if="addressLoading" class="deposit-address-state" role="status">
        <van-loading size="32" />
        <p>
          {{
            $t(
              addressPreparing
                ? "user.payment.addressUnavailable"
                : "user.v13.wallet.addressLoading"
            )
          }}
        </p>
      </div>

      <div v-else-if="addressError" class="deposit-address-state">
        <p class="inline-error" role="alert">{{ addressError }}</p>
        <van-button plain round type="primary" @click="obtainAddress()">{{
          $t("user.common.refresh")
        }}</van-button>
      </div>
      <div
        v-else-if="addressPreparing"
        class="deposit-address-state"
        role="status"
      >
        <van-icon name="clock-o" size="40" />
        <p>{{ $t("user.payment.addressUnavailable") }}</p>
        <p v-if="addressAttempts >= addressRetryLimit" class="hint">
          {{ $t("user.v15.addressRetryPaused") }}
        </p>
        <van-button plain round type="primary" @click="obtainAddress()">{{
          $t("user.common.refresh")
        }}</van-button>
      </div>
      <div v-else-if="result" class="deposit-address-result">
        <dl v-if="!automatic" class="details-list">
          <div v-if="result.orderNo">
            <dt>{{ $t("user.wallet.orderNo") }}</dt>
            <dd>{{ result.orderNo }}</dd>
          </div>
          <div>
            <dt>{{ $t("user.common.status") }}</dt>
            <dd>{{ $t(`user.status.${result.status || "PENDING"}`) }}</dd>
          </div>
        </dl>
        <template v-if="address">
          <h3>{{ $t("user.wallet.payAddress") }}</h3>
          <div class="deposit-address-content">
            <div class="deposit-qr">
              <QrCode :value="address" :size="200" />
            </div>
            <div class="deposit-address-copy">
              <p class="address-value">{{ address }}</p>
              <van-button
                plain
                round
                type="primary"
                icon="description-o"
                @click="copy(address)"
                >{{ $t("user.common.copy") }}</van-button
              >
              <template v-if="result.memo">
                <h3>{{ $t("user.currency.memo") }}</h3>
                <p class="address-value">{{ result.memo }}</p>
                <van-button
                  plain
                  round
                  type="primary"
                  @click="copy(result.memo)"
                  >{{ $t("user.common.copy") }}</van-button
                >
                <p class="notice-box">{{ $t("user.payment.memoRequired") }}</p>
              </template>
            </div>
          </div>
        </template>
        <a
          v-if="paymentUrl"
          class="hero-button space-top"
          :href="paymentUrl"
          target="_blank"
          rel="noopener noreferrer"
          >{{ $t("user.wallet.payUrl") }}</a
        >
      </div>
      <p
        v-else-if="!selectedChannel && !channelTask.loading.value"
        class="muted"
      >
        {{ $t("user.v13.wallet.selectDepositChannel") }}
      </p>
      </section>
    </section>
    <section class="card deposit-instructions">
      <div class="vora-panel-heading"><span aria-hidden="true"><van-icon name="info-o" /></span><h2>{{ $t("user.v13.wallet.depositInstructions") }}</h2></div>
      <ol>
        <li v-if="selectedChannel">
          {{
            $t("user.v13.wallet.depositChannelNotice", {
              currency: currencyLabel(form.currency),
              network: labels.network(selectedChannel),
            })
          }}
        </li>
        <li v-if="selectedChannel?.needMemo || result?.memo">
          {{ $t("user.v13.wallet.depositMemoNotice") }}
        </li>
        <li v-if="automatic">
          {{ $t("user.v13.wallet.depositConfirmationNotice") }}
        </li>
        <li v-else>{{ $t("user.wallet.depositPending") }}</li>
        <li v-if="selectedChannel && labels.depositHint(selectedChannel)">
          {{ labels.depositHint(selectedChannel) }}
        </li>
      </ol>
    </section>
    <div class="deposit-scene" aria-hidden="true"><img :src="depositScene" alt="" /></div>
  </div>
</template>

<style scoped>
.deposit-layout { display: grid; grid-template-columns: minmax(0,1.5fr) minmax(0,1fr); gap: 20px; max-width: 1060px; margin-inline: auto; align-items: start; }
.deposit-layout .card { min-width: 0; border: 0; border-radius: 14px; padding: 24px; background: var(--vora-surface); box-shadow: none; }
.deposit-main { grid-row: span 2; }
.deposit-step-number { position: relative; z-index: 1; display: inline-grid; place-items: center; vertical-align: middle; flex-shrink: 0; width: 22px; height: 22px; margin-right: 12px; border-radius: 50%; background: var(--vora-gradient); color: var(--vora-surface); font-size: 12px; line-height: 1; text-align: center; font-weight: 500; font-variant-numeric: tabular-nums; }
.deposit-step-heading { display: flex; align-items: center; margin: 22px 0 12px -34px; font-size: 13px; font-weight: 500; }
.deposit-credit-step { padding-left: 34px; }
.deposit-main :deep(.channel-dropdowns) { grid-template-columns: 1fr; gap: 18px; }
.deposit-wallets { position: relative; display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 8px; margin: 20px 0; padding: 0 0 0 34px; border: 0; }
.deposit-wallets::before { content: ''; position: absolute; top: 4px; bottom: -22px; left: 10px; width: 1px; background: var(--vora-border); pointer-events: none; }
.deposit-wallets legend { margin: 0 0 10px -34px; padding: 0; font-size: 13px; color: var(--vora-primary); }
.deposit-wallet-option { position: relative; display: flex; align-items: center; gap: 8px; min-width: 0; min-height: 46px; padding: 10px; border: 1px solid var(--vora-border); border-radius: 8px; background: var(--vora-surface); color: var(--vora-muted); cursor: pointer; }
.deposit-wallet-option.selected { border-color: #ce9389; background: #fcece3; color: var(--vora-primary); }
.deposit-wallet-option input { position: absolute; width: 1px; height: 1px; opacity: 0; }
.deposit-wallet-option:has(input:focus-visible) { outline: 2px solid var(--vora-primary); outline-offset: 3px; }
.deposit-wallet-option > span { flex: 1; font-size: 13px; overflow-wrap: anywhere; }
.deposit-wallet-option > .van-icon { flex-shrink: 0; font-size: 18px; }
.deposit-wallets:disabled { opacity: .6; }
.deposit-wallets:disabled label { cursor: default; }
.deposit-address-state { min-height: 200px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 14px; color: var(--vora-muted); text-align: center; }
.deposit-address-result { margin-top: 12px; }
.deposit-address-result h3 { font-size: 13px; font-weight: 600; color: var(--vora-primary); margin: 0 0 10px; }
.deposit-address-content { display: flex; flex-direction: column; gap: 18px; align-items: center; }
.deposit-address-copy { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; order: -1; width: 100%; }
.deposit-address-copy .address-value { flex: 1; min-width: 0; margin: 0; padding: 11px 12px; border: 1px solid var(--vora-border); border-radius: 8px; background: var(--vora-surface); color: var(--vora-primary); font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; user-select: all; }
.deposit-address-copy .van-button { min-width: 44px; flex-shrink: 0; padding: 8px 12px; font-size: 12px; }
.deposit-address-copy .notice-box { flex-basis: 100%; }
.deposit-qr { padding: 12px; background: #fff; }
.vora-panel-heading { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; color: var(--vora-primary); }
.vora-panel-heading > span { color: #b46a65; font-size: 18px; }
.vora-panel-heading h2 { margin: 0; font-size: 15px; }
.deposit-layout .deposit-instructions { background: #f9e8e1; }
.deposit-instructions ol { margin: 0; padding-left: 18px; color: var(--vora-muted); font-size: 12px; line-height: 1.9; }
.deposit-instructions li + li { margin-top: 7px; }
.deposit-scene { height: 190px; overflow: hidden; border-radius: 12px; }
.deposit-scene img { width: 100%; height: 100%; object-fit: cover; }
.deposit-layout :deep(.van-field) { margin: 12px 0; padding: 14px; border: 1px solid var(--vora-border); border-radius: 8px; background: var(--vora-surface); }
.deposit-layout :deep(.van-button) { min-height: 44px; border-radius: 999px; }
.deposit-layout :deep(.van-button--primary:not(.van-button--plain)) { border: 0; background: var(--vora-gradient); }
@media(max-width:760px) { .deposit-layout { grid-template-columns: minmax(0,1fr); gap: 12px; } .deposit-layout .card { padding: 18px 16px; } .deposit-main { grid-row: auto; } .deposit-scene { height: 155px; } .deposit-address-result { margin-top: 20px; } }
@media(min-width:1100px) {
  .deposit-layout { max-width: 1280px; grid-template-columns: minmax(0,2.1fr) minmax(260px,.9fr); gap: 24px; }
  .deposit-layout .deposit-main { display: grid; grid-template-columns: minmax(0,1fr) minmax(0,1.15fr); gap: 0 28px; padding: 28px; align-items: start; }
  .deposit-main > :deep(.channel-dropdowns) { grid-column: 1; margin-top: 0; }
  .deposit-main > .deposit-wallets { grid-column: 1; margin-top: 4px; grid-template-columns: minmax(0,1fr); }
  .deposit-wallets::before { display: none; }
  .deposit-main > .hint,.deposit-main > .inline-error { grid-column: 1; }
  .deposit-credit-step { grid-column: 2; grid-row: 1 / span 8; min-width: 0; padding-left: 0; padding-inline-start: 24px; border-inline-start: 1px solid var(--vora-border); }
  .deposit-step-heading { margin: 0 0 18px; }
  .deposit-address-result { margin-top: 0; }
  .deposit-address-copy { order: 0; }
  .deposit-qr { margin: 0 auto; }
  .deposit-layout .deposit-instructions { padding: 26px; }
  .deposit-instructions ol { font-size: 13px; line-height: 1.9; }
  .deposit-scene { height: 220px; }
}
</style>
