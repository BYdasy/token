<script setup lang="ts">
import { useSession } from '@/stores/session';
const session = useSession();
</script>
<template>
  <div v-if="session.config?.features?.checkinEnabled" class="feature-entries">
    <RouterLink v-if="session.config?.features?.checkinEnabled" to="/checkin"><span class="feature-entry-icon"><van-icon name="calendar-o" /></span><div><strong>{{ $t('user.v11.features.checkin') }}</strong><small>{{ $t('user.v11.features.checkinEntry') }}</small></div><van-icon name="arrow" /></RouterLink>
  </div>
</template>
<style scoped>
.feature-entries { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 14px; margin: 0 0 20px; }
.feature-entries a { display: flex; align-items: center; gap: 13px; padding: 18px; border: 1px solid var(--vora-soft-surface); border-radius: 15px; background: #fff; color: var(--vora-primary); }
.feature-entry-icon { display: grid; place-items: center; width: 44px; height: 44px; border-radius: 12px; color: var(--vora-primary); background: var(--vora-surface); font-size: 24px; }
.feature-entries strong, .feature-entries small { display: block; }.feature-entries small { color: var(--vora-muted); font-size: 12px; margin-top: 4px; }.feature-entries a > .van-icon { margin-left: auto; color: var(--vora-muted); }
@media (max-width: 600px) { .feature-entries { grid-template-columns: 1fr; gap: 10px; }.feature-entries a { padding: 13px 16px; } }
</style>
