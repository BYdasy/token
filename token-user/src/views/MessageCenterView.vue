<script setup lang="ts">
import ContentView from './ContentView.vue';
</script>
<template>
  <div class="vora-messages-page"><ContentView embedded content-kind="MESSAGE" /></div>
</template>
<style scoped>
.vora-messages-page { max-width: 900px; margin: 0 auto; color: var(--vora-ink); }
</style>
