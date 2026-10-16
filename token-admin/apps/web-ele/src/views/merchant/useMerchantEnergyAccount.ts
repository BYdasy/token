import { onScopeDispose, ref, watch } from 'vue';
import { useAccessStore } from '@vben/stores';
import { useI18n } from '@vben/locales';
import { merchantQuery, type MerchantRow } from '#/api/merchant';

/** 独立读取供应商已保存快照，身份变化或关闭入口后旧响应不得回填。 */
export function useMerchantEnergyAccount(allowed: () => boolean) {
  const access = useAccessStore();
  const { locale } = useI18n();
  const account = ref<MerchantRow>(), loading = ref(false), failed = ref(false);
  let generation = 0, disposed = false;
  async function load() {
    const own = ++generation;
    if (disposed || !allowed() || !access.accessToken) return;
    loading.value = true; failed.value = false;
    try {
      const response = await merchantQuery('energy-account', {}, true);
      if (!disposed && own === generation && allowed()) account.value = response;
    } catch {
      if (!disposed && own === generation) { failed.value = true; account.value = undefined; }
    } finally { if (own === generation) loading.value = false; }
  }
  watch(() => [access.accessToken, locale.value, allowed()], () => {
    generation++; account.value = undefined; loading.value = false; failed.value = false;
    if (allowed() && access.accessToken) void load();
  }, { immediate: true, flush: 'sync' });
  onScopeDispose(() => { disposed = true; generation++; });
  return { account, loading, failed, load };
}
