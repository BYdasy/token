<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useResponsivePopup } from '@/composables/useResponsivePopup';

const props = defineProps<{
  modelValue: string;
  label: string;
  options: { value: string; label: string }[];
  disabled?: boolean;
}>();
const emit = defineEmits<{ 'update:modelValue': [string] }>();
const show = ref(false);
const { popupPosition } = useResponsivePopup();
const selected = computed(() => props.options.find(option => option.value === props.modelValue)?.label || props.label);
function choose(value: string) {
  if (props.disabled || !props.options.some(option => option.value === value)) return;
  emit('update:modelValue', value);
  show.value = false;
}
watch(() => props.disabled, disabled => { if (disabled) show.value = false; });
</script>

<template>
  <div class="filter-picker">
    <button type="button" class="filter-picker-trigger" :disabled="disabled || !options.length" :aria-label="label" :aria-expanded="show" @click="show = true">
      <span><small>{{ label }}</small><strong>{{ selected }}</strong></span><van-icon name="arrow-down" />
    </button>
    <van-popup v-model:show="show" :position="popupPosition" round closeable teleport="body" class="form-popup ui-responsive-popup">
      <h2>{{ label }}</h2>
      <div class="filter-picker-options" role="listbox" :aria-label="label">
        <button v-for="option in options" :key="option.value" type="button" role="option" :aria-selected="option.value === modelValue" :class="{ selected: option.value === modelValue }" @click="choose(option.value)">
          <span>{{ option.label }}</span><van-icon v-if="option.value === modelValue" name="success" />
        </button>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.filter-picker { min-width: 180px; }
.filter-picker-trigger { display: flex; align-items: center; justify-content: space-between; gap: 16px; width: 100%; padding: 11px 14px; border: 1px solid var(--border, var(--vora-soft-surface)); border-radius: 12px; background: var(--card, #fff); color: inherit; text-align: start; cursor: pointer; }
.filter-picker-trigger > span { display: grid; gap: 4px; }
.filter-picker-trigger small { color: var(--muted, var(--vora-muted)); }
.filter-picker-trigger strong { font-size: 14px; }
.filter-picker-trigger:disabled { opacity: .6; cursor: default; }
.filter-picker-options { display: grid; gap: 8px; max-height: min(60dvh, 480px); overflow-y: auto; }
.filter-picker-options button { display: flex; justify-content: space-between; gap: 12px; padding: 15px; text-align: start; color: inherit; background: transparent; border: 1px solid var(--border, var(--vora-soft-surface)); border-radius: 10px; cursor: pointer; }
.filter-picker-options button.selected { border-color: var(--primary, var(--vora-primary)); color: var(--primary, var(--vora-primary)); background: rgba(119, 62, 81, 0.039); }
</style>
