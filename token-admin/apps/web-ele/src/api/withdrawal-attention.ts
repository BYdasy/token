import { requestClient } from './request';

/** 提醒开关独立于真实付款及审核状态。 */
export function setWithdrawalAttention(id: number, ignored: boolean) {
  return requestClient.put(`/compute/admin/withdrawals/${id}/attention`, { ignored });
}
