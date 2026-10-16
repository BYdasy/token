<script setup lang="ts">
import type { Currency } from "@/api/types";
defineProps<{
  channel?: Currency;
  readonly?: boolean;
  model: { address: string; memo: string; fields: Record<string, string> };
}>();
</script>
<template>
  <template v-if="channel?.assetType === 'FIAT'">
    <van-field
      v-for="key in channel.requiredFields"
      :key="key"
      v-model="model.fields[key]"
      :label="$t(`user.currency.addressField.${key}`)"
      :readonly="readonly"
      maxlength="250"
      :rules="[{ required: true, message: $t('user.common.required') }]"
    />
  </template>
  <van-field
    v-else
    v-model="model.address"
    :label="$t('user.wallet.withdrawAddress')"
    :readonly="readonly"
    maxlength="200"
    :rules="[{ required: true, message: $t('user.common.required') }]"
  />
  <van-field
    v-if="channel?.needMemo || model.memo"
    v-model="model.memo"
    :label="$t('user.currency.memo')"
    :readonly="readonly"
    maxlength="120"
    :rules="[{ required: Boolean(channel?.needMemo), message: $t('user.common.required') }]"
  />
</template>
