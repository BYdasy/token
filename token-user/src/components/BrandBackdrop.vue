<script setup lang="ts">
import { computed } from 'vue';
import { useSession } from '@/stores/session';
import { imageSource } from '@/utils/image-source';
import { brandAsset } from '@/utils/brand-assets';
const session = useSession();
const logo = computed(() => imageSource(session.config?.siteLogo) || brandAsset('icon-512.png'));
</script>
<template>
  <div class="vora-brand-backdrop" aria-hidden="true">
    <svg viewBox="0 0 1000 1300" fill="none" preserveAspectRatio="xMidYMin slice"><path d="M1090 120C690 -140 110 280 580 490S1170 900 460 1090"/><path d="M1120 145C720 -115 142 290 610 505S1200 920 490 1115"/><path d="M1145 170C752 -90 174 300 640 520S1230 940 520 1140"/></svg>
    <img :src="logo" alt="" width="180" height="180" />
  </div>
</template>
<style scoped>
.vora-brand-backdrop { position: absolute; inset: 70px 0 0; overflow: hidden; pointer-events: none; z-index: -1; }
svg { position: absolute; top: 20px; right: -15%; width: min(100%,1000px); height: 1300px; color: var(--vora-peach); opacity: .14; }
path { stroke: currentColor; stroke-width: 1.3; }
img { position: absolute; top: 480px; left: -42px; width: 180px; height: 180px; object-fit: contain; opacity: .045; transform: rotate(-18deg); }
@media(min-width:900px) { img { top: 410px; left: 2%; width: 240px; height: 240px; } }
</style>
