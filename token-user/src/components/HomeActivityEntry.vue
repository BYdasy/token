<script setup lang="ts">
import { computed, ref } from "vue";
import { useHomeActivities } from "@/composables/useHomeActivities";
import FloatingActivityIcon from "./FloatingActivityIcon.vue";
const { floatingEntries } = useHomeActivities();
const dismissed = ref<string[]>([]);
const visible = computed(() => {
  const positions: number[] = [];
  return floatingEntries.value.filter(item => !dismissed.value.includes(item.key)).map(item => {
    const configured = Number(item.bottom ?? 160);
    let bottom = Number.isFinite(configured) ? Math.max(0, Math.min(2000, configured)) : 160;
    // 多个活动设置了相同高度时先错开，用户仍可逐个拖动。
    while (positions.some(value => Math.abs(value - bottom) < 96)) bottom += 96;
    positions.push(bottom);
    return { ...item, bottom };
  });
});
</script>
<template>
  <FloatingActivityIcon v-for="item in visible" :key="item.key" :path="item.path" :image="item.image" :bottom="item.bottom"
    @dismiss="dismissed.push(item.key)" />
</template>
