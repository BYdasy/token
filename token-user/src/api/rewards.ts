import { request } from './index';
export interface DepositRewardRule { currency: string; participation: 'FIRST' | 'ALL'; firstDepositCompleted: boolean; received: string; tiers: { threshold: string; selfMode: string; selfValue: string; directMode: string; directValue: string }[] }
export interface EffectiveRewardTier { count: number; amount: string; received: boolean }
export interface EffectiveRewardRule { currency: string; basis: string; threshold: string; effectiveCount: number; received: string; nextTier: EffectiveRewardTier | null; tiers: EffectiveRewardTier[] }
export interface VipRewardRule {
  depth: number;
  firstDepositEnabled?: boolean;
  firstDepositRate?: string;
  depositEnabled: boolean;
  depositRate: string;
  vipEnabled: boolean;
  vipRate: string;
  productEnabled: boolean;
  productRate: string;
  depositCapByCompute?: boolean;
  vipRequireComputeBalance?: boolean;
  requireEffectiveMember?: boolean;
  effectiveDepositThreshold?: string;
}
export interface RewardRecord { orderNo: string; sourceKind: string; currency: string; amount: string; depth: number; createdAt: string; sourceUsername: string; isVirtual: boolean | number }
export interface RewardRecords { records: RewardRecord[]; total: number }
export interface RewardCenter { depositRules: DepositRewardRule[]; effectiveRules: EffectiveRewardRule[]; vipRules: VipRewardRule[]; level: number; levelName?: string; teamDepth: number; isTest: boolean; totals: { currency: string; amount: string }[]; records: RewardRecords }
export const fetchRewardCenter = (signal?: AbortSignal) => request<RewardCenter>('/user/team/rewards', 'GET', undefined, signal);
export const fetchRewardRecords = (page: number, signal?: AbortSignal) => request<RewardRecords>(`/user/team/reward-records?page=${page}&size=10`, 'GET', undefined, signal);
