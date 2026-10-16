<script setup lang="ts">
import TeamOverviewPanel from '@/components/TeamOverviewPanel.vue';
</script>
<template>
  <div class="vora-team-overview"><TeamOverviewPanel /></div>
</template>
<style scoped>
.vora-team-overview { max-width: 1100px; margin: 0 auto; min-width: 0; }
@media (min-width: 1024px) { .vora-team-overview { max-width: none; } }
</style>
