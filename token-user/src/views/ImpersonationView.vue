<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { request } from '@/api';
import type { Member } from '@/api/types';
import { useSession } from '@/stores/session';
import { takeImpersonationTicket } from '@/router';
const router = useRouter(), session = useSession();
const failed = ref(false), controller = new AbortController();
onMounted(async () => {
  const ticket = takeImpersonationTicket();
  session.clear();
  if (!ticket) { failed.value = true; return; }
  try {
    const value = await request<{ token: string; member: Member }>('/auth/impersonation', 'POST', { ticket }, controller.signal);
    if (controller.signal.aborted) return;
    session.setSession(value, false);
    await router.replace('/');
  } catch {
    if (!controller.signal.aborted) failed.value = true;
  }
});
onBeforeUnmount(() => controller.abort());
</script>
<template>
  <section class="page">
    <van-empty v-if="failed" :description="$t('user.v8.impersonationFailed')">
      <van-button type="primary" @click="router.replace('/login')">{{ $t('user.auth.login') }}</van-button>
    </van-empty>
    <van-loading v-else vertical>{{ $t('user.v8.impersonating') }}</van-loading>
  </section>
</template>
