<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { showConfirmDialog, showSuccessToast } from "vant";
import { api } from "@/api";
import type { Address } from "@/api/types";
import { useCurrencies } from "@/composables/useCurrencies";
import { useChannelLabels } from "@/composables/useChannelLabels";
import ReceivingFields from "./ReceivingFields.vue";
import ChannelDropdowns from "./ChannelDropdowns.vue";
import CurrencyIcon from "./CurrencyIcon.vue";
import PaymentPasswordField from "./PaymentPasswordField.vue";
import { useResponsivePopup } from "@/composables/useResponsivePopup";
import { useTask } from "@/composables/useTask";
import { copyText, validPassword } from "@/utils/format";
import { currencyLabel } from "@/utils/currency";
import { useSession } from "@/stores/session";
import PageState from "@/components/PageState.vue";
const { t } = useI18n();
const labels = useChannelLabels();
const task = useTask();
const action = useTask();
const session = useSession();
let disposed = false;
let readVersion = 0;
let controller: AbortController | undefined;
const addresses = ref<Address[]>([]);
const deletionEnabled = ref(true);
const showAdd = ref(false);
const { popupPosition } = useResponsivePopup();
const form = reactive({
  label: "",
  currency: "",
  network: "",
  address: "",
  memo: "",
  fields: {} as Record<string, string>,
  payPassword: "",
});
const payPasswordReady = computed(() => Boolean(session.member?.hasPayPassword) && validPassword(form.payPassword));
const {
  channels,
  task: channelTask,
  refresh,
} = useCurrencies("ADDRESS", "EARNINGS");
const selectedChannel = computed(() =>
  channels.value.find(
    (item) => item.currency === form.currency && item.network === form.network,
  ),
);
const currencies = computed(() => [
  ...new Set(channels.value.map((item) => item.currency)),
]);
const networks = computed(() =>
  channels.value
    .filter((item) => item.currency === form.currency)
    .map((item) => item.network),
);
watch(
  currencies,
  (values) => {
    if (!values.includes(form.currency)) form.currency = values[0] || "";
  },
  { immediate: true },
);
watch(
  networks,
  (values) => {
    if (!values.includes(form.network)) form.network = values[0] || "";
  },
  { immediate: true },
);
function load() {
  const current = ++readVersion, token = session.token;
  controller?.abort();
  if (!token || disposed) { addresses.value = []; return; }
  void task.run(async () => {
    if (disposed || current !== readVersion || token !== session.token) return;
    const requestController = new AbortController();
    controller = requestController;
    try {
      const policy = api.addressPolicy(requestController.signal).then(value => {
        if (!disposed && current === readVersion && token === session.token) deletionEnabled.value = value.withdrawalAddressDeleteEnabled !== false;
      }).catch(() => { /* 读取失败保留本页已知设置，提交仍由服务端实时校验。 */ });
      const [items] = await Promise.all([api.addresses(requestController.signal), policy]);
      if (!disposed && current === readVersion && token === session.token) addresses.value = items;
    } catch (cause) {
      if (!disposed && token === session.token && !requestController.signal.aborted) throw cause;
    }
  }, false);
}
watch(showAdd, value => {
  if (value) void refresh();
  else form.payPassword = "";
});
watch(
  () => [form.currency, form.network],
  () => {
    form.address = "";
    form.memo = "";
    form.fields = {};
  },
);
async function save() {
  if (disposed || !payPasswordReady.value || !selectedChannel.value || channelTask.loading.value || action.loading.value) return;
  const token = session.token;
  const submission = { ...form, fields: { ...form.fields }, label: form.label.trim(), address: form.address.trim() };
  await action.run(async () => {
    try { await api.addAddress(submission); }
    catch (cause) { if (!disposed && token === session.token) throw cause; else return; }
    if (disposed || token !== session.token) return;
    showAdd.value = false;
    form.address = "";
    form.label = "";
    form.memo = "";
    form.fields = {};
    form.payPassword = "";
    showSuccessToast(t("user.common.success"));
    load();
  });
}
async function remove(id: number) {
  if (disposed || !deletionEnabled.value || action.loading.value) return;
  const token = session.token;
  try {
    await showConfirmDialog({
      message: t("user.wallet.addressDelete"),
      confirmButtonText: t("user.common.confirm"),
      cancelButtonText: t("user.common.cancel"),
    });
  } catch {
    return;
  }
  if (disposed || token !== session.token || !deletionEnabled.value) return;
  await action.run(async () => {
    try { await api.deleteAddress(id); }
    catch (cause) { if (!disposed && token === session.token) throw cause; else return; }
    if (disposed || token !== session.token) return;
    load();
  });
}
function copy(value: string) {
  void action.run(async () => {
    await copyText(value);
    showSuccessToast(t("user.common.copied"));
  });
}
onMounted(load);
watch(() => session.token, () => { addresses.value = []; deletionEnabled.value = true; showAdd.value = false; load(); }, { flush: 'sync' });
onUnmounted(() => { disposed = true; readVersion++; controller?.abort(); form.payPassword = ""; });
</script>
<template>
  <div class="vora-address-panel">
  <div class="address-toolbar">
  <div class="address-safety"><van-icon name="shield-o" aria-hidden="true" /><div><strong>{{ $t('user.mobile.wallet.addressSafety') }}</strong><p>{{ $t("user.wallet.addressHint") }}</p></div></div>
    <van-button class="address-add-button" type="primary" block round icon="plus" @click="showAdd = true">{{
      $t("user.wallet.addAddress")
    }}</van-button>
  </div>
  <div class="section-heading"><h2>{{ $t('user.mobile.wallet.boundAddresses') }} <span>({{ addresses.length }})</span></h2></div>
  <PageState
    :loading="task.loading.value"
    :error="task.error.value"
    :empty="!addresses.length"
    @retry="load"
    ><div class="address-grid">
      <article
        v-for="address in addresses"
        :key="address.id"
        class="card address-card"
      >
        <div class="row">
          <CurrencyIcon :channel="channels.find(item => item.currency === address.currency && item.network === address.network) || address" />
          <div>
            <h3>{{ currencyLabel(address.currency) }}</h3>
            <span class="pill"
              >{{ labels.network(channels.find(item => item.currency === address.currency && item.network === address.network) || address) }}</span
            >
          </div>
        </div>
        <dl class="address-details">
          <div><dt>{{ $t('user.refine.address.label') }}</dt><dd>{{ address.label || '—' }}</dd></div>
          <div><dt>{{ $t('user.refine.address.value') }}</dt><dd class="address-inline"><span class="address-value">{{ address.address }}</span><button type="button" class="address-copy" :aria-label="$t('user.common.copy')" @click="copy(address.address)"><van-icon name="description-o" aria-hidden="true" /></button></dd></div>
        </dl>
        <small v-if="address.memo"
          >{{ $t("user.currency.memo") }}: {{ address.memo }}</small
        >
        <dl
          v-if="address.fields && Object.keys(address.fields).length"
          class="details-list compact"
        >
          <div v-for="(value, key) in address.fields" :key="key">
            <dt>{{ $t(`user.currency.addressField.${key}`) }}</dt>
            <dd>{{ value }}</dd>
          </div>
        </dl>
        <div class="row between">
          <button
            v-if="deletionEnabled"
            class="text-button danger"
            :disabled="action.loading.value"
            @click="remove(address.id)"
          >
            {{ $t("user.common.delete") }}
          </button>
        </div>
      </article>
    </div></PageState
  ><van-popup
    v-model:show="showAdd"
    :position="popupPosition"
    round
    closeable
    :close-on-click-overlay="!action.loading.value"
    teleport="body"
    class="form-popup ui-responsive-popup vora-address-popup"
    ><h2>{{ $t("user.wallet.addAddress") }}</h2>
    <van-form @submit="save"
      ><van-field
        v-model="form.label"
        :label="$t('user.wallet.addressLabel')"
        :rules="[{ required: true, message: $t('user.common.required') }]"
        :disabled="action.loading.value"
        maxlength="80"
      />
      <ChannelDropdowns :channels="channels" :currency="form.currency" :network="form.network" :disabled="action.loading.value" @select="(channel) => { form.currency = channel.currency; form.network = channel.network; }" />
      <p class="hint">{{ $t("user.ui2.selectors.addressBound") }}</p>
      <ReceivingFields :model="form" :channel="selectedChannel" :readonly="action.loading.value" />
      <PaymentPasswordField v-model="form.payPassword" :disabled="action.loading.value" />
      <p v-if="selectedChannel?.assetType === 'FIAT'" class="hint">
        {{ $t("user.currency.fiatReview") }}
      </p>
      <p v-if="channelTask.error.value" class="inline-error">
        {{ channelTask.error.value }}
        <button type="button" class="text-button" @click="refresh">
          {{ $t("user.common.refresh") }}
        </button>
      </p>
      <p v-if="action.error.value" class="inline-error">
        {{ action.error.value }}
      </p>
      <van-button
        block
        round
        type="primary"
        native-type="submit"
        :loading="action.loading.value"
        :disabled="!selectedChannel || channelTask.loading.value || !payPasswordReady"
        class="space-top"
        >{{ $t("user.common.save") }}</van-button
      ></van-form
    ></van-popup
  >
  </div>
</template>
<style scoped>
.vora-address-panel { max-width: 980px; margin-inline: auto; color: var(--vora-primary); }
.address-safety { display: flex; align-items: center; gap: 16px; margin-bottom: 18px; padding: 18px 20px; border-radius: 14px; background: #fae3dc; }
.address-safety > .van-icon { display: grid; place-items: center; width: 44px; height: 44px; flex-shrink: 0; border-radius: 50%; background: #f3cfca; font-size: 25px; color: #915363; }
.address-safety strong { display: block; font-size: 15px; }
.address-safety p { margin: 6px 0 0; color: var(--vora-muted); line-height: 1.7; font-size: 13px; }
.address-add-button { width: 100%; min-height: 48px; margin-bottom: 24px; border: 0; border-radius: 999px; background: var(--vora-gradient); }
.section-heading { margin: 0 0 12px; }
.section-heading h2 { margin: 0; font-size: 15px; font-weight: 500; }
.section-heading h2 span { margin-left: 5px; color: var(--vora-muted); font-variant-numeric: tabular-nums; }
.address-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 14px; }
.address-card { position: relative; display: grid; grid-template-columns: minmax(0,1fr) auto; gap: 8px 12px; min-width: 0; padding: 18px; border: 1px solid #eddfd8; border-radius: 12px; background: var(--vora-surface); box-shadow: none; }
.address-card > .row:first-child { grid-column: 1 / -1; gap: 12px; align-items: center; }
.address-card > .row:first-child > div { display: flex; flex-wrap: wrap; gap: 6px 12px; align-items: center; min-width: 0; }
.address-card h3 { margin: 0; font-size: 16px; overflow-wrap: anywhere; }
.address-card .pill { padding: 4px 8px; border-radius: 6px; background: #f7e7e1; color: #865663; font-size: 12px; }
.address-details { display: grid; gap: 8px; grid-column: 1 / -1; margin: 6px 0 0; }
.address-details > div { display: flex; align-items: flex-start; gap: 12px; min-width: 0; font-size: 12px; line-height: 1.7; }
.address-details dt { flex: 0 0 4em; color: var(--vora-muted); }
.address-details dd { margin: 0; min-width: 0; color: var(--vora-primary); overflow-wrap: anywhere; }
.address-details .address-inline { display: flex; align-items: flex-start; gap: 4px; flex: 1; }
.address-card .address-value { margin: 0; padding: 0; min-width: 0; border: 0; border-radius: 0; background: transparent; color: var(--vora-primary); font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; user-select: all; }
.address-copy { display: inline-grid; place-items: center; flex: 0 0 44px; width: 44px; height: 44px; margin: -10px -10px -10px 0; border: 0; border-radius: 50%; background: transparent; color: var(--vora-primary); font-size: 17px; cursor: pointer; }
.address-copy:hover { background: var(--vora-bg); }
.address-card > small,.address-card .details-list { grid-column: 1 / -1; margin: 0 0 0 50px; }
.address-card > .row:last-child { grid-column: 1 / -1; justify-content: flex-end; gap: 8px; margin: 2px -8px -10px; border: 0; }
.address-card .text-button { min-height: 44px; padding: 8px 12px; border-radius: 999px; color: var(--vora-primary); font-size: 12px; }
.address-card .text-button:hover { background: var(--vora-bg); }
.address-card .text-button.danger { color: #a5444b; }
.address-card :deep(.wallet-currency-icon) { width: 38px; height: 38px; flex-basis: 38px; }
.vora-address-panel :deep(.van-button),.vora-address-popup :deep(.van-button) { min-height: 46px; border-radius: 999px; }
.vora-address-popup :deep(.van-button--primary) { background: var(--vora-gradient); border: 0; }
.vora-address-popup { background: var(--vora-surface); color: var(--vora-primary); border-radius: 18px; }
.vora-address-popup > h2 { margin-bottom: 24px; font-size: 21px; }
.vora-address-popup :deep(.van-field) { padding: 14px; margin: 12px 0; border: 1px solid var(--vora-border); border-radius: 10px; background: var(--vora-surface); }
.vora-address-popup .hint { color: var(--vora-muted); line-height: 1.7; }
.vora-address-panel button:focus-visible { outline: 2px solid var(--vora-primary); outline-offset: 3px; }
@media(max-width:700px) { .address-grid { grid-template-columns: minmax(0,1fr); gap: 12px; } .address-safety { gap: 12px; padding: 15px; } .address-card { padding: 16px; } }
@media(min-width:1000px) {
  .vora-address-panel { max-width: 1280px; }
  .address-toolbar { display: grid; grid-template-columns: minmax(0,1fr) 210px; gap: 24px; align-items: center; margin-bottom: 24px; }
  .address-safety,.address-add-button { margin: 0; }
  .address-grid { display: block; padding: 0 24px; background: var(--vora-surface); border-radius: 14px; }
  .address-card { display: grid; grid-template-columns: 190px minmax(0,1fr) auto; gap: 12px 24px; padding: 24px 0; border: 0; border-bottom: 1px solid var(--vora-border); border-radius: 0; background: transparent; align-items: center; }
  .address-card:last-child { border-bottom: 0; }
  .address-card > .row:first-child { grid-column: 1; grid-row: 1; }
  .address-card > .row:first-child > div { display: grid; gap: 6px; justify-items: start; }
  .address-card h3 { font-size: 14px; }
  .address-details { grid-column: 2; grid-row: 1; grid-template-columns: minmax(120px,.65fr) minmax(0,1.7fr); gap: 24px; margin: 0; }
  .address-details > div { display: grid; gap: 6px; align-content: start; }
  .address-details dt { font-size: 11px; }
  .address-details .address-inline { align-items: center; }
  .address-copy { margin-block: -10px; }
  .address-card > .row:last-child { grid-column: 3; grid-row: 1; margin: 0; }
  .address-card > small,.address-card .details-list { grid-column: 2 / -1; margin: 0; }
  .address-card .details-list { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 8px 24px; }
}
</style>
