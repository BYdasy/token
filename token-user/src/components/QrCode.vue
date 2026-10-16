<script setup lang="ts">
import { ref, watch } from 'vue';
import QRCode from 'qrcode';
const props = withDefaults(defineProps<{ value: string; size?: number }>(), { size: 180 });
const image = ref('');
let version = 0;
watch(() => [props.value, props.size] as const, async ([value, size]) => {
  const current = ++version;
  image.value = '';
  if (!value) return;
  try {
    const data = await QRCode.toDataURL(value, { width: size, margin: 2, errorCorrectionLevel: 'M' });
    if (current === version) image.value = data;
  } catch { /* 二维码失败仍保留可复制的原文，不伪造地址。 */ }
}, { immediate: true });
</script>
<template><img v-if="image" :src="image" :width="size" :height="size" class="ui-qr" :alt="$t('user.ui.common.qrCode')" /></template>
