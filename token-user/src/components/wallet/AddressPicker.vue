<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, useId, watch } from "vue";
import type { Address, Currency } from "@/api/types";
import CurrencyIcon from "./CurrencyIcon.vue";
import { useChannelLabels } from "@/composables/useChannelLabels";
import { currencyLabel } from "@/utils/currency";
import { usableWithdrawalAddress } from '@/utils/withdrawal-address';
const props = defineProps<{ addresses: Address[]; channels: Currency[]; selectedId: string; disabled?: boolean }>();
const emit = defineEmits<{ select: [address: Address] }>();
const labels = useChannelLabels();
const show = ref(false);
const search = ref("");
const container = ref<HTMLElement>();
const trigger = ref<HTMLButtonElement>();
const menuId = useId();
const available = (address: Address) => usableWithdrawalAddress(address, props.channels);
const selected = computed(() => props.addresses.find((item) => String(item.id) === props.selectedId && available(item)));
const filtered = computed(() => {
  const keyword = search.value.trim().toLocaleLowerCase();
  return props.addresses.filter(available).filter((item) => [item.label, currencyLabel(item.currency), item.currency, item.network, networkLabel(item), item.address, ...Object.values(item.fields || {})]
    .some((value) => String(value ?? '').toLocaleLowerCase().includes(keyword)));
});
const channelFor = (address: Address) => props.channels.find(item => item.currency === address.currency && item.network === address.network);
const networkLabel = (address: Address) => labels.network(channelFor(address) || address);
function choose(address: Address) {
  if (props.disabled || !available(address)) return;
  emit("select", address);
  show.value = false;
  trigger.value?.focus();
}
async function toggle() { if (props.disabled) return; show.value = !show.value; search.value = ''; if (show.value) { await nextTick(); (container.value?.querySelector<HTMLButtonElement>('[aria-selected="true"]') || container.value?.querySelector<HTMLButtonElement>('[role="option"]'))?.focus(); } }
function closeOutside(event: Event) { if (event.target instanceof Node && !container.value?.contains(event.target)) show.value = false; }
function navigate(event: KeyboardEvent) {
  if (event.key === 'Escape') { show.value = false; trigger.value?.focus(); return; }
  if (!['ArrowDown','ArrowUp','Home','End'].includes(event.key)) return;
  event.preventDefault(); const items = Array.from((event.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('[role="option"]'));
  const current = items.indexOf(document.activeElement as HTMLButtonElement);
  items[event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 : (current + (event.key === 'ArrowUp' ? -1 : 1) + items.length) % items.length]?.focus();
}
watch(() => props.disabled, (value) => { if (value) show.value = false; });
onMounted(() => { document.addEventListener('pointerdown', closeOutside); document.addEventListener('focusin', closeOutside); });
onUnmounted(() => { document.removeEventListener('pointerdown', closeOutside); document.removeEventListener('focusin', closeOutside); });
</script>
<template>
  <div ref="container" class="address-selector-field">
  <label class="field-label">{{ $t("user.ui2.selectors.savedWallet") }}</label>
  <button ref="trigger" type="button" class="ui-channel-button" :disabled="disabled" aria-haspopup="listbox" :aria-controls="show ? menuId : undefined" :aria-expanded="show" @click="toggle" @keydown.down.prevent="!show && toggle()">
    <CurrencyIcon v-if="selected" :channel="channelFor(selected) || selected" />
    <span v-else class="ui-channel-icon"><van-icon name="balance-o" /></span>
    <div>
      <strong>{{ selected?.label || $t("user.v46.withdrawal.selectWallet") }}</strong>
      <small>{{ selected ? `${currencyLabel(selected.currency)} · ${networkLabel(selected)}` : $t("user.v46.withdrawal.boundRequired") }}</small>
    </div>
    <van-icon name="arrow-down" />
  </button>
  <div v-if="show" class="ui-address-picker">
    <van-search v-if="addresses.filter(available).length > 5" v-model="search" :placeholder="$t('user.ui2.selectors.walletSearch')" />
    <div :id="menuId" role="listbox" :aria-label="$t('user.ui2.selectors.savedWallet')" @keydown="navigate">
      <button v-for="address in filtered" :key="address.id" class="ui-channel-option" type="button" role="option" :aria-selected="selectedId === String(address.id)" :disabled="!available(address)" @click="choose(address)">
        <CurrencyIcon :channel="channelFor(address) || address" />
        <div>
          <strong>{{ address.label }}</strong>
          <small>{{ currencyLabel(address.currency) }} · {{ networkLabel(address) }}</small>
          <small>{{ address.address || Object.values(address.fields || {}).join(" · ") }}</small>
          <small v-if="!available(address)">{{ $t("user.ui2.selectors.channelUnavailable") }}</small>
        </div>
        <van-icon v-if="selectedId === String(address.id)" name="checked" />
      </button>
      <van-empty v-if="!filtered.length" :description="$t(search ? 'user.common.empty' : 'user.v46.withdrawal.boundRequired')" />
    </div>
  </div>
  </div>
</template>
<style scoped>
.field-label { color: var(--vora-ink, #392d32); font-size: 13px; }
.address-selector-field { position: relative; min-width: 0; }
.address-selector-field:has(.ui-address-picker) { z-index: 30; }
.ui-channel-button { min-height: 76px; border: 1px solid #e5d6d0; border-radius: 12px; background: var(--vora-bg, #faf6f2); color: var(--vora-ink, #392d32); padding: 16px; }
.ui-channel-button small { display: block; margin-top: 6px; color: var(--vora-muted, #806e75); line-height: 1.6; font-size: 12px; }
.ui-channel-icon { color: var(--vora-primary, #773e51); background: #f2dfd9; }
.ui-address-picker { position: absolute; z-index: 30; inset-inline: 0; top: calc(100% + 6px); padding: 8px; max-height: min(50dvh,420px); overflow: auto; overscroll-behavior: contain; border: 0; border-radius: 12px; color: var(--vora-primary); background: var(--vora-surface); box-shadow: 0 10px 30px rgb(59 20 43 / 16%); }
.ui-address-picker :deep(.van-search) { padding: 0 0 18px; background: transparent; }
.ui-address-picker :deep(.van-search__content) { min-height: 44px; border-radius: 12px; background: var(--vora-bg, #faf6f2); }
.ui-channel-option { min-height: 76px; gap: 14px; padding: 16px; margin-bottom: 10px; border: 1px solid #e5d6d0; border-radius: 12px; background: transparent; color: var(--vora-ink, #392d32); }
.ui-channel-option[aria-selected=true] { background: #f4e5e0; border-color: var(--vora-primary, #773e51); }
.ui-channel-option small { color: var(--vora-muted, #806e75); font-size: 12px; line-height: 1.6; }
.ui-channel-option > .van-icon { color: var(--vora-primary, #773e51); }
.ui-channel-option:focus-visible, .ui-channel-button:focus-visible { outline: 2px solid var(--vora-primary, #773e51); outline-offset: 3px; }
.ui-channel-button { min-height: 64px; padding: 12px; border-radius: 8px; background: var(--vora-surface); border-color: var(--vora-border); }
.ui-channel-option { min-height: 66px; border-radius: 10px; padding: 12px; }
.ui-channel-option[aria-selected=true] { border-color: #c58c88; background: #fae8df; }
</style>
