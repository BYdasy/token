import { computed } from "vue";

/** 两端统一居中对话框；只控制展示，不重建表单或资金请求状态。 */
export function useResponsivePopup() {
  return { popupPosition: computed(() => "center" as const) };
}
