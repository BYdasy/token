<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue';
import { $t } from '@vben/locales';
import { useVbenModal } from '@vben-core/popup-ui';
import { createUpdateMonitor, fetchBuildId, refreshedPageUrl } from './update-check';

interface Props {
  // 前台轮询间隔，单位分钟；非正数停用。
  checkUpdatesInterval?: number;
  // 与当前应用代码一起部署的版本清单。
  checkUpdateUrl?: string;
}
defineOptions({ name: 'CheckUpdates' });
const props = withDefaults(defineProps<Props>(), {
  checkUpdatesInterval: 1,
  checkUpdateUrl: `${import.meta.env.BASE_URL || '/'}version.json`,
});
const latestBuildId = ref('');
let mounted = false;
let monitor: ReturnType<typeof createUpdateMonitor> | undefined;
const [UpdateNoticeModal, modalApi] = useVbenModal({
  closable: false,
  closeOnPressEscape: false,
  closeOnClickModal: false,
  onConfirm() {
    if (latestBuildId.value) window.location.replace(refreshedPageUrl(window.location.href, latestBuildId.value));
  },
});
function startMonitor() {
  monitor?.dispose();
  // 本地 production 预览同样检查；开发热更新由 Vite 负责。
  if (!mounted || !import.meta.env.PROD || !import.meta.env.VITE_APP_BUILD_ID) return;
  monitor = createUpdateMonitor({
    currentBuildId: import.meta.env.VITE_APP_BUILD_ID,
    intervalMs: props.checkUpdatesInterval * 60_000,
    visible: () => !document.hidden,
    read: (signal) => fetchBuildId(props.checkUpdateUrl, signal),
    onUpdate(buildId) { latestBuildId.value = buildId; modalApi.open(); },
  });
  if (!document.hidden) monitor.start();
}
function visibilityChanged() {
  if (document.hidden) monitor?.pause();
  else monitor?.start();
}
watch(() => [props.checkUpdatesInterval, props.checkUpdateUrl], startMonitor);
onMounted(() => {
  mounted = true;
  startMonitor();
  document.addEventListener('visibilitychange', visibilityChanged);
});
onUnmounted(() => {
  mounted = false;
  monitor?.dispose();
  modalApi.close();
  document.removeEventListener('visibilitychange', visibilityChanged);
});
</script>
<template>
  <UpdateNoticeModal
    :cancel-text="$t('common.cancel')"
    :confirm-text="$t('common.refresh')"
    :fullscreen-button="false"
    :title="$t('ui.widgets.checkUpdatesTitle')"
    centered
    content-class="px-8 min-h-10"
    footer-class="border-none mb-3 mr-3"
    header-class="border-none"
  >
    {{ $t('ui.widgets.checkUpdatesDescription') }}
  </UpdateNoticeModal>
</template>
