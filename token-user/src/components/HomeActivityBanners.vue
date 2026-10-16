<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useHomeActivities } from "@/composables/useHomeActivities";
const { banners } = useHomeActivities();
const failed = ref<string[]>([]);
const visible = computed(() => banners.value.filter(item => !failed.value.includes(`${item.key}:${item.image}`)));
watch(() => banners.value.map(item => `${item.key}:${item.image}`).join("|"), () => { failed.value = []; });
</script>
<template>
  <section v-if="visible.length" class="home-activity-banners">
    <RouterLink v-for="item in visible" :key="item.key" :to="item.path" class="home-activity-banner">
      <img :src="item.image" alt="" loading="lazy" @error="failed.push(`${item.key}:${item.image}`)" />
      <strong>{{ item.title }}</strong>
      <span class="home-activity-banner-arrow" aria-hidden="true"><van-icon name="arrow" /></span>
    </RouterLink>
  </section>
</template>
<style scoped>
.home-activity-banners { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 380px), 1fr)); min-width: 0; gap: 14px; }
.home-activity-banner { position: relative; isolation: isolate; display: flex; align-items: center; justify-content: space-between; gap: 16px; overflow: hidden; width: 100%; min-width: 0; min-height: 128px; aspect-ratio: 6; padding: 24px 28px; border: 1px solid var(--vora-border); border-radius: 16px; background: var(--vora-plum); color: #fff; box-shadow: var(--vora-shadow); }
.home-activity-banner img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; z-index: -2; }
.home-activity-banner::before { content: ""; position: absolute; inset: 0; background: linear-gradient(90deg, rgb(59 20 43 / 78%), rgb(59 20 43 / 14%) 85%); z-index: -1; }
.home-activity-banner strong { max-width: 72%; font-size: clamp(18px, 2.4vw, 30px); font-weight: 600; line-height: 1.4; overflow-wrap: anywhere; text-shadow: 0 2px 8px rgb(59 20 43 / 30%); }
.home-activity-banner-arrow { display: grid; place-items: center; flex: 0 0 38px; height: 38px; border: 1px solid rgb(255 255 255 / 50%); border-radius: 50%; background: rgb(59 20 43 / 24%); font-size: 18px; }
.home-activity-banner:focus-visible { outline: 2px solid var(--vora-primary); outline-offset: 4px; }
@media (max-width: 700px) {
  .home-activity-banners { gap: 10px; }
  .home-activity-banner { min-height: 100px; aspect-ratio: auto; padding: 16px 20px; border-radius: 14px; }
  .home-activity-banner strong { max-width: 78%; font-size: 20px; }
  .home-activity-banner-arrow { flex-basis: 32px; height: 32px; font-size: 16px; }
}
</style>
