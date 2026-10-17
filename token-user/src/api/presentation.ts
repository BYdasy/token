import { request } from './index';
import { useSession } from '@/stores/session';
import type { Amount, WalletType } from './types';

/** 服务端生成的展示快照，仅用于平台运行概览。 */
export interface RuntimeSample {
  at: string;
  exaflops: Amount;
  onlineGpus: Amount;
}

export interface UiPresentationConfig {
  /** 仅展示名称，不改变资金单位或真实币种编码。 */
  unitName?: string;
  legal?: Partial<Record<'TERMS' | 'PRIVACY', { title: string; bodyHtml: string }>>;
  defaultLanguage: string;
  autoDetectLanguage: boolean;
  slides: { imageUrl: string; targetPath?: string }[];
  runtime: {
    enabled: boolean;
    simulated: boolean;
    totalExaflops: Amount;
    exaflops: Amount;
    onlineGpus: Amount;
    mode?: 'AUTO' | 'MANUAL';
    refreshSeconds?: number;
    changeSeconds?: number;
    updatedAt?: string;
    nextUpdateAt?: string;
    sampleSeconds?: number;
    serverTime?: string;
    samples?: RuntimeSample[];
  };
  valuation: {
    unit: 'token';
    usdPerToken: '1';
    assetUsdRates: Record<string, string>;
    fiatPerUsd: Record<string, string>;
  };
}

export interface UnreadCount {
  notifications: number;
  messages: number;
  tasks: number;
  total: number;
}

export interface UserPresentation {
  valuation?: UiPresentationConfig['valuation'];
  /** 仅用于资产总额及其展示构成，钱包与业务判断仍读取原 assets。 */
  displayAssets?: { totalToken: string; giftToken: string };
  invitation: { memberCount: number; totalRewardsToken: string; rewardsByCurrency: { currency: string; amount: string }[]; complete: boolean };
  autoSummary?: { activeCount: number; totalCount: number; activePrincipal: string; todayIncome?: string; totalIncome: string; currency: string };
  assets: {
    totalToken: string;
    availableToken: string;
    participatingToken: string;
    complete: boolean;
    unpricedCurrencies: string[];
    wallets: { type: WalletType; currency: string; available: Amount; frozen: Amount; token: string | null }[];
  };
  securityScore: number;
  vipProgress: { currentLevel: number; nextLevel: number | null; availableBalance: string; invested: string; threshold: string; remaining: string; percent: Amount; currency: string; requiredDirectMembers?: number; directMemberDepositThreshold?: Amount; qualifiedDirectMembers?: number; remainingDirectMembers?: number; directMemberRequirementMet?: boolean; levels?: VipMemberProgress[] };
  unread: UnreadCount;
}

export interface VipMemberProgress {
  levelId: number;
  requiredDirectMembers: number;
  directMemberDepositThreshold: Amount;
  qualifiedDirectMembers: number;
  remainingDirectMembers: number;
  directMemberRequirementMet: boolean;
}

export const fetchPresentation = async (signal?: AbortSignal, background = false) => {
  const session = useSession(), token = session.token;
  const value = await request<UserPresentation>('/user/presentation', 'GET', undefined, signal, background);
  if (session.token === token && session.config?.presentation && value.valuation) session.config.presentation.valuation = value.valuation;
  return value;
};
export const fetchUnreadCount = (signal?: AbortSignal) => request<UnreadCount>('/user/unread-count', 'GET', undefined, signal, true);
