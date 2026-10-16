<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router';
import { mobileNavigation as items, navigationActive } from '@/router/navigation';
const route = useRoute();
const router = useRouter();
</script>
<template>
  <van-tabbar class="mobile-tabs" :aria-label="$t('user.ui2.nav.primary')" :model-value="items.findIndex(item => navigationActive(item.to, route.path))" placeholder safe-area-inset-bottom
    ><van-tabbar-item
      v-for="item in items"
      :key="item.to"
      @click="router.push(item.to)"
      :icon="item.icon"
      :aria-current="navigationActive(item.to, route.path) ? 'page' : undefined"
      >{{ $t(`user.vora.nav.${item.key}`) }}</van-tabbar-item
    ></van-tabbar
  >
</template>
