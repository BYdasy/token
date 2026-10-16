import { onUnmounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { fetchPresentation, type UserPresentation } from "@/api/presentation";
import { useSession } from "@/stores/session";

/** 等级页面共用账户读取，账号或语言变化时丢弃旧进度并取消旧请求。 */
export function useVipAccount() {
  const session = useSession();
  const { locale, t } = useI18n();
  const presentation = ref<UserPresentation>();
  const loading = ref(false);
  const error = ref("");
  let generation = 0;
  let disposed = false;
  let controller: AbortController | undefined;

  async function loadAccount(force = false, background = false) {
    const current = ++generation;
    const token = session.token;
    controller?.abort();
    controller = new AbortController();
    error.value = "";
    if (!token || disposed) {
      presentation.value = undefined;
      loading.value = false;
      return;
    }
    loading.value = true;
    try {
      const [, summary] = await Promise.all([
        session.loadMember(force, background),
        fetchPresentation(controller.signal, background),
      ]);
      if (disposed || current !== generation || token !== session.token) return;
      presentation.value = summary;
    } catch (cause) {
      if (disposed || current !== generation || token !== session.token) return;
      error.value = cause instanceof Error ? cause.message : t("user.error.request");
    } finally {
      if (current === generation) loading.value = false;
    }
  }

  watch([() => session.token, locale], () => {
    presentation.value = undefined;
    void loadAccount();
  }, { immediate: true });
  onUnmounted(() => {
    disposed = true;
    generation++;
    controller?.abort();
  });
  return { presentation, loading, error, loadAccount };
}
