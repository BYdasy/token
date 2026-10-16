<script setup lang="ts">
import { useI18n } from 'vue-i18n';
import { showFailToast, showSuccessToast } from 'vant';
import { copyText } from '@/utils/format';

const props = defineProps<{ value: string }>();
const { t } = useI18n();
async function copy() {
  if (!props.value) return;
  try { await copyText(props.value); showSuccessToast(t('user.common.copied')); }
  catch { showFailToast(t('user.payment.copyFailed')); }
}
</script>
<template>
  <button type="button" class="inline-copy-button" :aria-label="t('user.common.copy')" :title="t('user.common.copy')" @click.stop.prevent="copy">
    <van-icon name="description-o" aria-hidden="true" />
  </button>
</template>
<style scoped>
.inline-copy-button { display:inline-flex; flex:none; align-items:center; justify-content:center; min-width:32px; min-height:32px; padding:4px; border:0; border-radius:8px; color:var(--vora-primary); background:transparent; font-size:15px; line-height:1; vertical-align:middle; cursor:pointer; }
.inline-copy-button:hover { background:var(--vora-soft-surface); }
.inline-copy-button:active { background:var(--vora-soft); }
@media (pointer: coarse) { .inline-copy-button { min-width:44px; min-height:44px; } }
.inline-copy-button:focus-visible { outline:2px solid currentColor; outline-offset:2px; }
</style>
