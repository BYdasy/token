<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, useId, watch } from 'vue';
import type { WalletType } from '@/api/types';
import { useToken } from '@/composables/useToken';
const { unitName } = useToken();
const props = defineProps<{ modelValue: string; label: string; options: { type: WalletType; balance?: string }[]; disabled?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [string]; open: [] }>();
const show = ref(false);
const container = ref<HTMLElement>();
const trigger = ref<HTMLButtonElement>();
const menuId = useId();
const selected = computed(() => props.options.find(option => option.type === props.modelValue));
async function open() { if (!props.disabled && props.options.length) { if (show.value) { show.value = false; return; } emit('open'); show.value = true; await nextTick(); (container.value?.querySelector<HTMLButtonElement>('[aria-selected="true"]') || container.value?.querySelector<HTMLButtonElement>('[role="option"]'))?.focus(); } }
function choose(type: WalletType) {
  if (props.disabled || !props.options.some(option => option.type === type)) return;
  emit('update:modelValue', type); show.value = false; trigger.value?.focus();
}
function closeOutside(event: Event) { if (event.target instanceof Node && !container.value?.contains(event.target)) show.value = false; }
function navigate(event: KeyboardEvent) {
  if (event.key === 'Escape') { show.value = false; trigger.value?.focus(); return; }
  if (!['ArrowDown','ArrowUp','Home','End'].includes(event.key)) return;
  event.preventDefault(); const items = Array.from((event.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('[role="option"]'));
  const current = items.indexOf(document.activeElement as HTMLButtonElement);
  items[event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 : (current + (event.key === 'ArrowUp' ? -1 : 1) + items.length) % items.length]?.focus();
}
watch(() => props.disabled, disabled => { if (disabled) show.value = false; });
onMounted(() => { document.addEventListener('pointerdown', closeOutside); document.addEventListener('focusin', closeOutside); });
onUnmounted(() => { document.removeEventListener('pointerdown', closeOutside); document.removeEventListener('focusin', closeOutside); });
</script>
<template>
  <div ref="container" class="wallet-selector-field">
  <button ref="trigger" type="button" class="wallet-selector" :disabled="disabled || !options.length" aria-haspopup="listbox" :aria-expanded="show" :aria-controls="show ? menuId : undefined" @click="open" @keydown.down.prevent="!show && open()">
    <span class="wallet-selector-icon"><van-icon name="balance-o" /></span>
    <span class="wallet-selector-content"><small>{{ label }}</small><strong>{{ modelValue ? $t(`user.wallet.${modelValue}`) : $t('user.v10.wallet.chooseWallet') }}</strong><small v-if="selected?.balance !== undefined" class="selected-wallet-balance">{{ $t('user.wallet.available') }} {{ selected.balance }} {{ unitName }}</small></span>
    <van-icon name="arrow-down" />
  </button>
    <div v-if="show" :id="menuId" class="wallet-option-list" role="listbox" :aria-label="label" @keydown="navigate">
      <button v-for="option in options" :key="option.type" type="button" role="option" :aria-selected="option.type === modelValue" class="wallet-option" :class="{ selected: option.type === modelValue }" @click="choose(option.type)">
        <van-icon name="balance-o" /><span><strong>{{ $t(`user.wallet.${option.type}`) }}</strong><small v-if="option.balance !== undefined">{{ option.balance }} {{ unitName }}</small></span><van-icon v-if="option.type === modelValue" name="success" />
      </button>
    </div>
  </div>
</template>
<style scoped>
.wallet-selector { display: flex; align-items: center; gap: 12px; width: 100%; min-height: 84px; padding: 16px; border: 1px solid #e5d6d0; border-radius: 12px; text-align: start; background: var(--vora-bg, #faf6f2); color: var(--vora-ink, #392d32); cursor: pointer; }
.wallet-selector:disabled { opacity: .55; cursor: default; }
.wallet-selector-content { flex: 1; min-width: 0; display: grid; gap: 7px; }
.wallet-selector-content strong { font-size: 14px; overflow-wrap: anywhere; }
.wallet-selector-content small { color: var(--vora-muted, #806e75); font-size: 12px; }
.wallet-selector-icon { color: var(--vora-primary, #773e51); font-size: 24px; flex-shrink: 0; }
.wallet-selector > .van-icon { color: var(--vora-muted, #806e75); font-size: 12px; }
.wallet-selector-field { position: relative; min-width: 0; }
.wallet-selector-field:has(.wallet-option-list) { z-index: 30; }
.wallet-option-list { position: absolute; z-index: 30; inset-inline: 0; top: calc(100% + 6px); display: grid; gap: 4px; padding: 6px; max-height: min(45dvh,350px); overflow: auto; overscroll-behavior: contain; border: 0; border-radius: 12px; background: var(--vora-surface); box-shadow: 0 10px 30px rgb(59 20 43 / 16%); }
.wallet-option { display: flex; align-items: center; gap: 14px; min-height: 68px; padding: 16px; border-radius: 12px; border: 1px solid #e5d6d0; background: transparent; text-align: start; color: inherit; font: inherit; cursor: pointer; }
.wallet-option.selected { border-color: var(--vora-primary, #773e51); background: #f4e5e0; }
.wallet-option > span { display: grid; flex: 1; min-width: 0; gap: 6px; overflow-wrap: anywhere; }
.wallet-option small { color: var(--vora-muted, #806e75); font-variant-numeric: tabular-nums; }
.wallet-option > .van-icon { font-size: 22px; color: var(--vora-primary, #773e51); }
.wallet-option:focus-visible, .wallet-selector:focus-visible { outline: 2px solid var(--vora-primary, #773e51); outline-offset: 3px; }
.wallet-selector { min-height: 64px; padding: 12px; border-color: var(--vora-border); border-radius: 8px; background: var(--vora-surface); }
.wallet-selector-content { gap: 5px; }
.wallet-selector-icon { display: grid; place-items: center; width: 32px; height: 32px; border-radius: 50%; background: var(--vora-gradient); color: var(--vora-surface); font-size: 20px; }
.wallet-option { min-height: 60px; padding: 12px; border-radius: 10px; }
</style>
