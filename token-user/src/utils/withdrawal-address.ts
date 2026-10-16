import type { Address, Currency } from '@/api/types';

/** 仅用于界面选项；提交时仍由服务端实时复核归属、启用状态和币网快照。 */
export function usableWithdrawalAddress(address: Address, channels: Currency[]) {
  return Number.isSafeInteger(Number(address.id)) && Number(address.id) > 0
    && (address.enabled == null || address.enabled === true || address.enabled === 1)
    && channels.some((channel) => channel.currency === address.currency && channel.network === address.network
      && channel.withdrawEnabled !== false);
}
