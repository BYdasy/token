import { ref } from 'vue';

// 业务筛选和表单时间固定按系统时区，独立于后台阅读时区。
export const businessTimeZone = ref('UTC');
export const getBusinessTimeZone = () => businessTimeZone.value;
export function setBusinessTimeZone(value?: string) {
  if (value) businessTimeZone.value = value;
}
