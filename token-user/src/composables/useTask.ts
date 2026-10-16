import { ref } from "vue";
import { showDialog } from "vant";
import { i18n } from "@/locales";

export function useTask() {
  const loading = ref(false);
  const error = ref("");
  let pendingRead: (() => Promise<unknown>) | undefined;
  async function run<T>(action: () => Promise<T>, toast = true): Promise<T | undefined> {
    if (loading.value) {
      // 切换分页或筛选时保留最后一次读取；资金等写操作仍阻止重复提交。
      if (!toast) pendingRead = () => run(action, false);
      return;
    }
    loading.value = true;
    error.value = "";
    try {
      return await action();
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : i18n.global.t("user.error.request");
      if (toast) void showDialog({
        title: i18n.global.t("user.ui.common.operationFailed"),
        message: error.value,
        confirmButtonText: i18n.global.t("user.common.confirm"),
      });
    } finally {
      loading.value = false;
      if (pendingRead) {
        const next = pendingRead;
        pendingRead = undefined;
        void next();
      }
    }
  }
  return { loading, error, run };
}
