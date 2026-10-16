import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { api } from '@/api';
import type { Catalog } from '@/api/types';
import { useTask } from './useTask';
import { hasPendingTranslation, useTranslationPolling } from './useTranslationPolling';

/** 产品与等级共享目录读取方式，目标语言补译时不重载账户和资金数据。 */
export function useProductCatalog() {
  const { locale } = useI18n();
  const catalog = ref<Catalog>();
  const task = useTask();
  let version = 0;
  let disposed = false;
  const polling = useTranslationPolling(() => load(false));
  function load(restart = true) {
    const current = ++version;
    if (restart) polling.restart();
    void task.run(async () => {
      const result = await api.catalog(!restart);
      if (disposed || current !== version) return;
      catalog.value = result;
      polling.schedule(
        [...result.products, ...result.levels].some(hasPendingTranslation)
      );
    }, false);
  }
  watch(locale, () => load());
  onMounted(() => load());
  onUnmounted(() => {
    disposed = true;
    polling.stop();
  });
  // 目标语言轮询失败不撤下已经取得的英语内容。
  const visibleError = computed(() => (catalog.value ? '' : task.error.value));
  return { catalog, task, load, visibleError, translationPaused: polling.paused };
}
