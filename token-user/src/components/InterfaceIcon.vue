<script setup lang="ts">
defineProps<{ name: 'bell' | 'globe' }>();
</script>
<template>
  <svg class="interface-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <template v-if="name === 'bell'"><path d="M18 8a6 6 0 0 0-12 0c0 7-2.5 7-2.5 8.5h17C20.5 15 18 15 18 8Z"/><path d="M9.5 20a2.8 2.8 0 0 0 5 0M12 2V1"/></template>
    <template v-else><circle cx="12" cy="12" r="9"/><path d="M3.5 9.2h4.1L10 6.5 9.1 3.6M20.7 14h-4.2l-2.3 2.5.2 4.2M7.8 9.2l1.1 4 3.3 1.1 2.4-3.1-1.1-3 2.2-3.7"/></template>
  </svg>
</template>
<style scoped>
.interface-icon { display: block; width: 22px; height: 22px; flex: 0 0 22px; }
</style>
