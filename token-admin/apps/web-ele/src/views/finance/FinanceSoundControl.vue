<script setup lang="ts">
import { ElAlert, ElButton, ElPopover } from 'element-plus';
import { useCompute } from '#/components/business/shared';
import { useFinanceSound } from './useFinanceSound';
const { c } = useCompute();
const { visible, enabled, blocked, failed, storageFailed, unlocked, unlock } = useFinanceSound();
</script>
<template>
  <ElPopover v-if="visible" placement="bottom-end" :width="320" trigger="click">
    <template #reference><ElButton text :type="blocked || failed || storageFailed ? 'warning' : enabled && unlocked ? 'primary' : 'info'">{{ c('v12sound.control') }}<span v-if="enabled" class="sound-dot" /></ElButton></template>
    <p class="sound-help">{{ c('v51Notifications.sound.browserHelp') }}</p>
    <ElAlert v-if="!enabled" class="mb-3" :closable="false" :title="c('v12sound.disabled')" type="info" />
    <ElAlert v-if="blocked" class="mb-3" :closable="false" :title="c('v11.sound.blocked')" type="warning" />
    <ElAlert v-if="failed" class="mb-3" :closable="false" :title="c('v11.sound.loadFailed')" type="warning" />
    <ElAlert v-if="storageFailed" class="mb-3" :closable="false" :title="c('v11.sound.storageFailed')" type="warning" />
    <ElButton type="primary" :disabled="!enabled && !failed" @click="unlock">{{ c(unlocked && !blocked ? 'v11.sound.preview' : 'v12sound.unlock') }}</ElButton>
  </ElPopover>
</template>
<style scoped>.sound-dot { width:6px;height:6px;border-radius:50%;background:currentColor;margin-left:6px; }.sound-help { font-size:12px;line-height:1.7;margin-bottom:12px;color:var(--el-text-color-secondary); }</style>
