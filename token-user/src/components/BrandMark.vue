<script setup lang="ts">
import { useSession } from "@/stores/session";
import { computed } from 'vue';
import { imageSource } from '@/utils/image-source';
import { brandAsset } from '@/utils/brand-assets';
const session = useSession();
const customLogo = computed(() => imageSource(session.config?.siteLogo));
const useWordmark = computed(() => !customLogo.value && session.siteName === 'Tensor AI');
const logo = computed(() => customLogo.value || brandAsset(useWordmark.value ? 'tensor-ai-logo.png' : 'icon-512.png'));
</script>
<template>
  <RouterLink class="brand" :class="{ 'brand-wordmark': useWordmark }" to="/" :aria-label="session.siteName"
    ><img
      :src="logo"
      :width="useWordmark ? 180 : 42"
      :height="useWordmark ? 60 : 42"
      class="brand-mark brand-image"
      alt=""
    /><span v-if="!useWordmark" class="brand-copy"
      ><strong>{{ session.siteName }}</strong
      ></span
    ></RouterLink
  >
</template>
<style scoped>
.brand-image {
  width: 42px;
  height: 42px;
  flex: 0 0 42px;
  object-fit: contain;
  transform: none;
  background: none;
}
.brand-copy {
  min-width: 0;
}
.brand { gap: 9px; color: var(--vora-primary); }
.brand-wordmark .brand-image { width: 180px; height: 60px; flex: 0 1 180px; }
.brand-copy strong { font-family: 'Vora Display', Georgia, 'Noto Serif SC', serif; font-size: 27px; font-weight: 600; letter-spacing: -.02em; }
.brand-copy strong,
.brand-copy small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
@media (max-width: 640px) {
  .brand-image {
    width: 34px;
    height: 34px;
    flex-basis: 34px;
  }
  .brand-wordmark .brand-image { width: 144px; height: 48px; flex-basis: 144px; }
}
@media (max-width: 700px) {
  .brand-copy small { display: none; }
}
</style>
