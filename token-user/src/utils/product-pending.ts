import type { ProductQuote } from '@/api/product';
import { registerReloadBlocker } from './resource-recovery';

export interface PendingProductPurchase {
  requestId: string;
  amount: string;
  quote: ProductQuote;
}

const memory = new Map<string, PendingProductPurchase>();
registerReloadBlocker(() => memory.size > 0);
const storageFallback = new Set<string>();
function key(memberId: number, productId: number) {
  return `token-user-product-pending:${memberId}:${productId}`;
}

/** 只保存未决请求的原报价与请求号；是否成交始终由服务端幂等复核。 */
export function readPendingProductPurchase(memberId: number, productId: number): PendingProductPurchase | undefined {
  const storageKey = key(memberId, productId);
  if (storageFallback.has(storageKey)) return memory.get(storageKey);
  try {
    const stored = sessionStorage.getItem(storageKey);
    if (!stored) { memory.delete(storageKey); return; }
    const value = JSON.parse(stored) as PendingProductPurchase | null;
    if (!value || typeof value.requestId !== 'string' || !value.requestId || value.requestId.length > 200
      || typeof value.amount !== 'string' || !value.quote || value.quote.productId !== productId
      || typeof value.quote.quoteId !== 'string' || !value.quote.quoteId
      || !['AMOUNT', 'QUANTITY'].includes(value.quote.purchaseMode)
      || !['A', 'B', 'C', 'D'].includes(value.quote.settlementMode)
      || typeof value.quote.currency !== 'string'
      || !Number.isFinite(Date.parse(value.quote.expiresAt)) || !Number.isFinite(Date.parse(value.quote.quotedAt))) return;
    const values = [value.quote.quantity, value.quote.durationDays, value.quote.priceToken,
      value.quote.debitToken, value.quote.debitAmount, value.quote.dailyRateMin, value.quote.dailyRateMax,
      value.quote.expectedIncomeMinToken, value.quote.expectedIncomeMaxToken, value.quote.principalReturnToken];
    if (values.some((amount) => !['number', 'string'].includes(typeof amount) || !Number.isFinite(Number(amount)) || Number(amount) < 0)) return;
    memory.set(storageKey, value);
    return value;
  } catch { return memory.get(storageKey); }
}

export function savePendingProductPurchase(memberId: number, productId: number, value: PendingProductPurchase) {
  const storageKey = key(memberId, productId);
  const snapshot = JSON.parse(JSON.stringify(value)) as PendingProductPurchase;
  memory.set(storageKey, snapshot);
  try { sessionStorage.setItem(storageKey, JSON.stringify(snapshot)); storageFallback.delete(storageKey); }
  catch { storageFallback.add(storageKey); /* 存储被禁用或已满时，站内导航仍保留原请求。 */ }
}

export function clearPendingProductPurchase(memberId: number, productId: number, requestId: string) {
  const storageKey = key(memberId, productId);
  if (readPendingProductPurchase(memberId, productId)?.requestId !== requestId) return;
  memory.delete(storageKey);
  storageFallback.delete(storageKey);
  try { sessionStorage.removeItem(storageKey); } catch { storageFallback.add(storageKey); }
}
