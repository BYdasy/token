import { onUnmounted, ref } from 'vue';

export function hasPendingTranslation(value: unknown): boolean {
  if (!value || typeof value !== 'object') return false;
  const content = value as Record<string, unknown>;
  return ['PENDING', 'QUEUED', 'RUNNING'].includes(
    String(content.translationPendingStatus || content.translationStatus || '')
  );
}

/** 英语回退可立即展示，仅对已受理的目标语言翻译每两秒轮询，最多九十次。 */
export function useTranslationPolling(refresh: () => void) {
  const paused = ref(false);
  let attempts = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let disposed = false;
  function stop() {
    if (timer !== undefined) clearTimeout(timer);
    timer = undefined;
  }
  function restart() {
    stop();
    attempts = 0;
    paused.value = false;
  }
  function schedule(pending: boolean) {
    stop();
    if (disposed || !pending) return;
    if (attempts >= 90) {
      paused.value = true;
      return;
    }
    timer = setTimeout(() => {
      attempts++;
      refresh();
    }, 2000);
  }
  onUnmounted(() => {
    disposed = true;
    stop();
  });
  return { paused, stop, restart, schedule };
}
