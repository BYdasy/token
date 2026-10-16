<script setup lang="ts">
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import type { PlatformContent } from '@/api/content';
import { contentJump } from '@/utils/content-jump';
const props = defineProps<{ content: PlatformContent }>();
const router = useRouter();
const jump = computed(() => contentJump(props.content, router));
function follow(event: MouseEvent) {
  if (!jump.value?.path || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || event.button !== 0) return;
  event.preventDefault();
  void router.push(jump.value.path);
}
</script>
<template>
  <a v-if="jump" :href="jump.href" :target="jump.target" rel="noopener noreferrer" class="hero-button space-top" @click="follow">{{ $t('user.content.openLink') }}<van-icon name="arrow" /></a>
</template>
