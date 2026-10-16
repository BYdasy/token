<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, useId, watch } from 'vue';
import type { Currency } from '@/api/types';
import { useChannelLabels } from '@/composables/useChannelLabels';
import CurrencyIcon from './CurrencyIcon.vue';
import { currencyLabel } from '@/utils/currency';

const props = defineProps<{ channels: Currency[]; currency: string; network: string; disabled?: boolean; numbered?: boolean }>();
const emit = defineEmits<{ select: [channel: Currency] }>();
const labels = useChannelLabels();
const assets = computed(() => [...new Map(props.channels.map(channel => [labels.symbol(channel), channel])).values()]);
const networks = computed(() => props.channels.filter(channel => labels.symbol(channel) === currencyLabel(props.currency)));
const selected = computed(() => networks.value.find(channel => channel.currency === props.currency && channel.network === props.network));
type Dropdown = 'currency' | 'network';
const container = ref<HTMLElement>();
const open = ref<Dropdown>();
const id = useId();
const kinds: Dropdown[] = ['currency', 'network'];
const options = (kind: Dropdown) => kind === 'currency' ? assets.value : networks.value;
const optionValue = (kind: Dropdown, channel: Currency) => kind === 'currency' ? labels.symbol(channel) : `${channel.currency}:${channel.network}`;
const isSelected = (kind: Dropdown, channel: Currency) => kind === 'currency'
  ? labels.symbol(channel) === currencyLabel(props.currency)
  : channel.currency === props.currency && channel.network === props.network;
function close(restoreFocus = false) {
  const previous = open.value;
  open.value = undefined;
  if (restoreFocus && previous) container.value?.querySelector<HTMLButtonElement>(`[data-trigger="${previous}"]`)?.focus();
}
async function toggle(kind: Dropdown) {
  if (props.disabled || !options(kind).length) return;
  if (open.value === kind) { close(); return; }
  open.value = kind;
  await nextTick();
  focusOptions(kind);
}
function focusOptions(kind: Dropdown) {
  const menu = document.getElementById(`${id}-${kind}-options`);
  (menu?.querySelector<HTMLButtonElement>('[aria-selected="true"]') || menu?.querySelector<HTMLButtonElement>('[role="option"]'))?.focus();
}
function choose(kind: Dropdown, item: Currency) {
  if (props.disabled) return;
  const channel = kind === 'currency'
    ? props.channels.find(row => labels.symbol(row) === labels.symbol(item) && row.network === props.network) || props.channels.find(row => labels.symbol(row) === labels.symbol(item))
    : networks.value.find(row => row.currency === item.currency && row.network === item.network);
  if (channel) emit('select', channel);
  close(true);
}
function navigate(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); close(true); return; }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
  event.preventDefault();
  const items = Array.from((event.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('[role="option"]'));
  const current = items.indexOf(document.activeElement as HTMLButtonElement);
  const index = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 : (current + (event.key === 'ArrowUp' ? -1 : 1) + items.length) % items.length;
  items[index]?.focus();
}
watch(() => props.disabled, value => { if (value) close(); });
watch(() => [props.currency, props.network], () => close());
function closeOutside(event: Event) { if (event.target instanceof Node && !container.value?.contains(event.target)) close(); }
onMounted(() => { document.addEventListener('pointerdown', closeOutside); document.addEventListener('focusin', closeOutside); });
onUnmounted(() => { document.removeEventListener('pointerdown', closeOutside); document.removeEventListener('focusin', closeOutside); });
</script>

<template>
  <div ref="container" class="channel-dropdowns" :class="{ 'is-numbered': numbered }">
    <div v-for="kind in kinds" :key="kind" class="channel-dropdown">
      <span :id="`${id}-${kind}-label`" class="field-label"><span v-if="numbered" class="deposit-step-number" aria-hidden="true">{{ kind === 'currency' ? 1 : 2 }}</span>{{ $t(kind === 'currency' ? 'user.v13.wallet.currency' : 'user.common.network') }}</span>
      <button type="button" class="channel-dropdown-control" :class="{ expanded: open === kind }" :data-trigger="kind" :disabled="disabled || !options(kind).length" :aria-labelledby="`${id}-${kind}-label ${id}-${kind}-value`" aria-haspopup="listbox" :aria-expanded="open === kind" :aria-controls="open === kind ? `${id}-${kind}-options` : undefined" @click="toggle(kind)" @keydown.down.prevent="open !== kind && toggle(kind)" @keydown.up.prevent="open !== kind && toggle(kind)" @keydown.esc="close(true)">
        <CurrencyIcon :channel="selected || (kind === 'currency' ? assets.find(item => item.currency === currency) : undefined)" :network="kind === 'network'" />
        <span :id="`${id}-${kind}-value`" class="channel-selected-text"><strong>{{ kind === 'currency' ? currencyLabel(currency) || '—' : selected ? labels.network(selected) : '—' }}</strong></span>
        <van-icon name="arrow-down" />
      </button>
      <div v-if="open === kind" :id="`${id}-${kind}-options`" :data-menu="kind" role="listbox" :aria-labelledby="`${id}-${kind}-label`" class="channel-dropdown-menu" @keydown="navigate">
        <button v-for="channel in options(kind)" :key="optionValue(kind, channel)" type="button" role="option" :aria-selected="isSelected(kind, channel)" class="channel-dropdown-option" @click="choose(kind, channel)">
          <CurrencyIcon :channel="channel" :network="kind === 'network'" />
          <span><strong>{{ kind === 'currency' ? labels.symbol(channel) : labels.network(channel) }}</strong></span>
          <van-icon v-if="isSelected(kind, channel)" name="success" />
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.channel-dropdowns { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 12px; margin: 16px 0; }
.channel-dropdown { position: relative; min-width: 0; }
.channel-dropdown:has(.channel-dropdown-menu) { z-index: 30; }
.channel-dropdown .field-label { display: block; margin: 0 0 8px; color: var(--vora-primary); font-size: 13px; }
.deposit-step-number { position: relative; z-index: 1; display: inline-grid; place-items: center; vertical-align: middle; width: 22px; height: 22px; margin-right: 12px; border-radius: 50%; background: var(--vora-gradient); color: var(--vora-surface); font-size: 12px; line-height: 1; text-align: center; font-variant-numeric: tabular-nums; }
.is-numbered .channel-dropdown::before { content: ''; position: absolute; top: 27px; bottom: -20px; left: 10px; width: 1px; background: var(--vora-border); pointer-events: none; }
.is-numbered .channel-dropdown-control,.is-numbered .channel-dropdown-menu { width: calc(100% - 34px); margin-left: 34px; }
.channel-dropdown-control { display: flex; align-items: center; gap: 10px; width: 100%; min-height: 48px; padding: 10px 12px; border: 1px solid var(--vora-border); border-radius: 8px; background: var(--vora-surface); color: var(--vora-primary); text-align: start; font: inherit; cursor: pointer; }
.channel-selected-text { display: grid; flex: 1; min-width: 0; gap: 3px; }
.channel-selected-text strong { font-size: 14px; font-weight: 500; overflow-wrap: anywhere; }
.channel-dropdown-control.expanded { border-color: #c2817c; }
.channel-dropdown-control:focus-visible,.channel-dropdown-option:focus-visible { outline: 2px solid var(--vora-primary); outline-offset: 2px; }
.channel-dropdown-control > .van-icon { flex-shrink: 0; color: var(--vora-muted); font-size: 13px; }
.channel-dropdown-control.expanded > .van-icon { transform: rotate(180deg); }
.channel-dropdown-control:disabled { opacity: .6; cursor: default; }
.channel-dropdown-menu { position: absolute; z-index: 30; inset-inline: 0; top: calc(100% + 6px); display: grid; gap: 4px; padding: 6px; max-height: min(45dvh,350px); overflow: auto; overscroll-behavior: contain; border: 0; border-radius: 12px; background: var(--vora-surface); box-shadow: 0 10px 30px rgb(59 20 43 / 16%); }
.channel-dropdown-option { display: flex; align-items: center; gap: 12px; width: 100%; min-height: 50px; padding: 12px; border: 0; border-radius: 10px; background: var(--vora-bg); color: var(--vora-primary); text-align: start; font: inherit; cursor: pointer; }
.channel-dropdown-option[aria-selected=true],.channel-dropdown-option:hover { background: #f9e6dd; }
.channel-dropdown-option > span:not(.wallet-currency-icon) { display: grid; flex: 1; min-width: 0; gap: 4px; }
.channel-dropdown-option strong { font-size: 13px; overflow-wrap: anywhere; }
.channel-dropdown-option > .van-icon { flex-shrink: 0; font-size: 16px; }
.channel-dropdown :deep(.wallet-currency-icon) { width: 26px; height: 26px; flex-basis: 26px; }
@media(max-width:420px) { .channel-dropdowns { gap: 8px; } .channel-dropdown-control { gap: 7px; padding: 10px; } .channel-selected-text strong { font-size: 13px; } }
</style>
