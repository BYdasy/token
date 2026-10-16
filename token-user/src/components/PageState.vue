<script setup lang="ts">
defineProps<{ loading?: boolean; error?: string; empty?: boolean; emptyText?: string }>();
defineEmits<{ retry: [] }>();
</script>
<template>
  <div v-if="loading" class="page-state">
    <van-loading color="var(--vora-primary)" vertical>{{ $t("user.common.loading") }}</van-loading>
  </div>
  <div v-else-if="error" class="page-state">
    <van-empty image="network" :description="error" /><van-button
      plain
      type="primary"
      @click="$emit('retry')"
      >{{ $t("user.common.refresh") }}</van-button
    >
  </div>
  <van-empty v-else-if="empty" :description="emptyText || $t('user.common.empty')" /><slot v-else />
</template>
