<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import { createIconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';
import { ElButton, ElPopover } from 'element-plus';
import { merchantAddressQr } from '#/api/merchant';
import { useCompute } from '#/components/business/shared';

const props = defineProps<{ address: string }>();
const QrIcon = createIconifyIcon('lucide:qr-code');
const { c } = useCompute();
const access = useAccessStore();
const visible = ref(false), image = ref(''), loading = ref(false), failed = ref(false);
let generation = 0;
async function load() {
  if (image.value || loading.value || !props.address) return;
  const own = ++generation;
  loading.value = true;
  failed.value = false;
  try {
    const result = await merchantAddressQr(props.address);
    if (own === generation) image.value = result.image;
  } catch {
    if (own === generation) failed.value = true;
  } finally {
    if (own === generation) loading.value = false;
  }
}
watch(() => [props.address, access.accessToken], () => {
  generation++;
  visible.value = false;
  image.value = '';
  loading.value = false;
});
onBeforeUnmount(() => generation++);
</script>
<template>
  <ElPopover v-model:visible="visible" trigger="click" :width="244" placement="bottom" @show="load">
    <template #reference>
      <ElButton link type="primary" :aria-label="c('v12merchant.qr')" :title="c('v12merchant.qr')">
        <QrIcon class="size-4" />
      </ElButton>
    </template>
    <div v-loading="loading" class="address-qr">
      <img v-if="image" :src="image" :alt="c('v12merchant.qr')" />
      <ElButton v-else-if="failed" link type="primary" @click="load">{{ c('v12merchant.refresh') }}</ElButton>
      <p>{{ address }}</p>
    </div>
  </ElPopover>
</template>
<style scoped>
.address-qr { min-height: 180px; text-align: center; }
.address-qr img { display: block; width: 212px; height: 212px; margin: 0 auto 8px; }
.address-qr p { font: 12px/1.5 monospace; overflow-wrap: anywhere; }
</style>
