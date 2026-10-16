import { request } from '@/api';
import type { Amount } from './types';

export interface ProductQuote {
  purchaseMode: 'AMOUNT' | 'QUANTITY';
  quantity: number;
  unitPriceToken: Amount;
  quota?: {
    maxPurchaseAmount: Amount;
    maxPurchaseQuantity: number;
    purchasedAmountToken: Amount | null;
    purchasedQuantity: number;
    remainingAmountToken: Amount | null;
    remainingQuantity: number | null;
    amountComplete: boolean;
  } | null;
  quoteId: string;
  expiresAt: string;
  quotedAt: string;
  productId: number;
  amount: Amount;
  priceToken: Amount;
  debitToken: Amount;
  debitAmount: Amount;
  currency: string;
  durationDays: number;
  dailyRateMin: Amount;
  dailyRateMax: Amount;
  settlementMode: 'A' | 'B' | 'C' | 'D';
  expectedIncomeMinToken: Amount;
  expectedIncomeMaxToken: Amount;
  principalReturnToken: Amount;
}

export const quoteProduct = (productId: number, input: { amount?: string; quantity?: string }, signal?: AbortSignal) => request<ProductQuote>('/user/orders/quote', 'POST', { productId, ...input }, signal);
