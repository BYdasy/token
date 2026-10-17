<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router';
import { mobileNavigation as items, navigationActive } from '@/router/navigation';
import InterfaceIcon from '@/components/InterfaceIcon.vue';
const route = useRoute();
const router = useRouter();
</script>
<template>
  <van-tabbar class="mobile-tabs" :aria-label="$t('user.ui2.nav.primary')" :model-value="items.findIndex(item => navigationActive(item.to, route.path))" placeholder safe-area-inset-bottom
    ><van-tabbar-item
      v-for="item in items"
      :key="item.to"
      @click="router.push(item.to)"
      :aria-current="navigationActive(item.to, route.path) ? 'page' : undefined"
      ><template #icon><InterfaceIcon v-if="item.icon === 'income-levels'" name="income-levels" class="navigation-icon" /><van-icon v-else :name="item.icon" aria-hidden="true" /></template>{{ $t(`user.vora.nav.${item.key}`) }}</van-tabbar-item
    ></van-tabbar
  >
</template>
<style scoped>
.navigation-icon { width: 23px; height: 23px; flex-basis: 23px; }
</style>
