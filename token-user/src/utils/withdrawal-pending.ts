import { effectScope, shallowRef, watch } from 'vue';
import type { Quote } from '@/api/types';
import { registerReloadBlocker } from './resource-recovery';

export interface PendingWithdrawal {
  payload: {
    requestId: string;
    currency: string;
    network: string;
    amount: string;
    address: string;
    memo: string;
    fields: Record<string, string>;
    payPassword: string;
    addressId?: number;
    expectedFee: string;
    expectedNetAmount: string;
    expectedAccountingRate: string;
    expectedAccountingAmount: string;
  };
  quote: Quote;
  uncertain: boolean;
}

// 未决提款只驻留本页进程内存，绝不把支付密码写入localStorage/sessionStorage。
// 独立作用域在组件离开后继续监听凭据，注销或换账号立即清除旧请求和密码。
const pending = shallowRef<PendingWithdrawal>();
registerReloadBlocker(() => Boolean(pending.value));
let trackedSession: { token: string } | undefined;
let sessionScope: ReturnType<typeof effectScope> | undefined;
export function usePendingWithdrawal(session: { token: string }) {
  if (trackedSession !== session) {
    sessionScope?.stop(); pending.value = undefined; trackedSession = session;
    sessionScope = effectScope(true);
    sessionScope.run(() => watch(() => session.token, () => { pending.value = undefined; }, { flush: 'sync' }));
  }
  return {
    pending,
    save(value: PendingWithdrawal) {
      // 拷贝实际JSON请求，脱离可编辑表单和响应式报价，重放保持字段逐字一致。
      pending.value = JSON.parse(JSON.stringify(value)) as PendingWithdrawal;
    },
    clear(requestId?: string) {
      if (!requestId || pending.value?.payload.requestId === requestId) pending.value = undefined;
    },
  };
}
