<script setup lang="ts">
import { ElAlert, ElButton } from 'element-plus';
import { useCompute } from '#/components/business/shared';
import MerchantDetails from './MerchantDetails.vue';
import { useMerchantBatchDetails } from './useMerchantBatchDetails';

const props = defineProps<{ batchId: string }>();
const { c } = useCompute();
const { batch, loading, failed, page, next, previous, reload } = useMerchantBatchDetails(() => props.batchId);
</script>

<template>
  <div v-loading="loading" class="min-h-24">
    <ElAlert v-if="failed" class="mb-4" type="error" :closable="false" :title="c('merchant.receiptLoadFailed')" />
    <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
      <span v-if="batch">{{ c('merchant.receiptPage') }} {{ page }} · {{ c('merchant.receiptTotal') }} {{ batch.receipt_count }}</span>
      <div class="flex flex-wrap gap-2">
        <ElButton :disabled="loading" @click="reload">{{ c('merchant.reloadReceipts') }}</ElButton>
        <ElButton :disabled="loading || failed || page <= 1" @click="previous">{{ c('merchant.previous') }}</ElButton>
        <ElButton :disabled="loading || failed || !batch?.receipt_has_more" @click="next">{{ c('merchant.next') }}</ElButton>
      </div>
    </div>
    <MerchantDetails v-if="batch" :value="batch" resource="batches" />
  </div>
</template>
