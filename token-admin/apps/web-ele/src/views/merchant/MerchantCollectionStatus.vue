<script setup lang="ts">
import type { MerchantRow } from '#/api/merchant';
import { useCompute } from '#/components/business/shared';
import { collectionStatusFields, collectionStatusLabel } from './order-display';

defineProps<{ value: MerchantRow }>();
const { c } = useCompute();
</script>

<template>
  <div v-for="key in collectionStatusFields" :key="key" class="collection-status">
    <small>{{ c(`merchant.field.${key}`) }}：</small><span>{{ collectionStatusLabel(key, value[key], c) }}</span>
  </div>
</template>

<style scoped>
.collection-status { overflow-wrap: anywhere; line-height: 1.65; }
.collection-status small { color: var(--el-text-color-secondary); }
</style>
