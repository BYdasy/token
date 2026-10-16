import { request } from './index';
import type { Amount, BusinessRecord, PageResult } from './types';

export interface AutoExecution extends BusinessRecord {
  executionFinishedAt?: string;
  animationEndsAt?: string;
  detailsStatus?: 'PENDING' | 'READY';
  executionResult?: {
    source?: 'CONFIGURED_ALLOCATION';
    models: { name: string; provider: string; otherModels?: boolean; modelCount?: number; allocationPercent: Amount; tokenUsage?: Amount; inputTokens?: Amount; outputTokens?: Amount; modelCalls?: number }[];
    tokenUsage?: Amount;
    inputTokens?: Amount;
    outputTokens?: Amount;
    modelCalls?: number;
    stages?: string[];
    executionState: 'SUCCEEDED';
    settledIncome: Amount;
    expectedIncome: Amount;
    currency: string;
    detailsStatus?: 'PENDING' | 'READY';
    tokenBasis?: Amount;
    generatedAt?: string;
  };
}
export interface AutoStatus {
  serverTime: string;
  systemTimeZone: string;
  allowedAmount: Amount;
  currency: string;
  remainingCount: number;
  dailyLimit: number;
  nextAllowedAt?: string;
  canExecute: boolean;
  reasonKey?: string;
  vipExpiresAt?: string;
  pendingDetails?: AutoExecution | null;
}

export const fetchAutoContracts = (page = 1, signal?: AbortSignal) =>
  request<PageResult<AutoExecution>>(`/user/auto-compute?page=${page}&size=20`, 'GET', undefined, signal);
export const fetchAutoStatus = (signal?: AbortSignal, background = false) =>
  request<AutoStatus>('/user/auto-compute/status', 'GET', undefined, signal, background);
export const fetchAutoExecution = (orderNo: string, signal?: AbortSignal, background = false) =>
  request<AutoExecution>(`/user/auto-compute/${encodeURIComponent(orderNo)}`, 'GET', undefined, signal, background);
export const activateAuto = (requestId: string) =>
  request<AutoExecution>('/user/auto-compute', 'POST', { requestId });
