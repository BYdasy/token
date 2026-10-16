<script setup lang="ts">
import { useSession } from "@/stores/session";
import { computed } from 'vue';
import { imageSource } from '@/utils/image-source';
import { brandAsset } from '@/utils/brand-assets';
const session = useSession();
const logo = computed(() => imageSource(session.config?.siteLogo) || brandAsset('icon-512.png'));
</script>
<template>
  <RouterLink class="brand" to="/"
    ><img
      :src="logo"
      width="42"
      height="42"
      class="brand-mark brand-image"
      alt=""
    /><span class="brand-copy"
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
}
@media (max-width: 700px) {
  .brand-copy small { display: none; }
}
</style>
