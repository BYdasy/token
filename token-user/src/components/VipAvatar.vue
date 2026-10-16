<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useSession } from '@/stores/session';
import { imageSource } from '@/utils/image-source';
const props = defineProps<{ src?: string | null; username?: string }>();
const session = useSession();
const failed = ref(false);
const image = computed(() => imageSource(props.username !== undefined ? props.src : session.member?.avatarUrl));
const letter = computed(() => (props.username || session.member?.username || session.member?.email || 'U').slice(0, 1).toUpperCase());
watch(image, () => { failed.value = false; });
</script>
<template><span class="vip-avatar" aria-hidden="true"><img v-if="image && !failed" :src="image" alt="" @error="failed = true" /><template v-else>{{ letter }}</template></span></template>
<style scoped>.vip-avatar { display: inline-grid; flex: none; place-items: center; overflow: hidden; border-radius: 50%; background: var(--vora-soft-surface); color: var(--vora-primary); }.vip-avatar img { display: block; width: 100%; height: 100%; object-fit: cover; }</style>
