import { request } from './index';
import type { WalletType } from './types';

export interface TransferOptions {
  currency: string;
  assetRate: string | null;
  directions: { fromWallet: WalletType; toWallet: WalletType }[];
}
export const fetchTransferOptions = (signal?: AbortSignal, background = false) => request<TransferOptions>('/user/transfers/options', 'GET', undefined, signal, background);

export interface TransferQuote {
  fromWallet: string;
  toWallet: string;
  currency: string;
  amount: string;
  fee: string;
  netAmount: string;
}
export const fetchTransferQuote = (input: { fromWallet: string; toWallet: string; amount: string; amountUnit: string; expectedAssetRate: string }, signal?: AbortSignal) =>
  request<TransferQuote>('/user/transfers/quote', 'POST', input, signal);
