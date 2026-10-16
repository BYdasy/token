import { request } from "./index";
import type { Amount, PageResult } from "./types";
export interface ActivityPrize {
  id: number;
  name: string;
  amount: Amount;
  stock: number;
  usedStock: number;
  minDraw?: number;
  probability: Amount;
  imageUrl?: string;
  displayImageUrl?: string;
  isNoPrize?: boolean;
}
export interface Activity {
  id: number;
  name: string;
  type: "WHEEL" | "RED_PACKET" | "CHEST";
  description: string;
  currency: string;
  startsAt: string;
  endsAt: string;
  minLevel: number;
  minLevelName?: string;
  minDeposit: Amount;
  minActivePrincipal: Amount;
  totalStock: number;
  usedStock: number;
  remainingStock: number;
  totalBudget: Amount;
  usedBudget: Amount;
  remainingBudget: Amount;
  perUserTotalLimit: number;
  perUserDailyLimit: number;
  myTotalCount?: number;
  myTodayCount?: number;
  packetMode: "FIXED" | "RANDOM";
  minReward: Amount;
  maxReward: Amount;
  prizes: ActivityPrize[];
  status: "UPCOMING" | "OPEN" | "ENDED" | "DISABLED" | "EXHAUSTED" | "CONFIG_REQUIRED";
  wheelConfig?: {
    mode?: string;
    imageUrl?: string;
    topImageUrl?: string;
    descriptionHtml?: string;
    threshold?: Amount;
    earningType?: string;
    spinDurationSeconds?: number;
    descriptionTranslations?: Record<string, string>;
    tiers?: { threshold: Amount; selfCount: number; inviterCount: number }[];
    cumulativeScope?: 'ACTIVITY' | 'LIFETIME';
    translations?: Record<string, { title: string; description: string; content: string }>;

  };
  attemptsConfigurationRequired?: boolean;
  myWheel?: { availableCount: number; pendingCount: number; usedCount: number; earnedCount?: number; issuedCount?: number; selfEarnedCount?: number; inviterEarnedCount?: number };
}
export interface ActivityParticipation {
  id: number;
  activityId: number;
  activityName: string;
  activityType: "WHEEL" | "RED_PACKET" | "CHEST";
  prizeId?: number;
  prizeName: string;
  amount: Amount;
  currency: string;
  status: "PAID" | "NO_PRIZE" | "PENDING_REDEMPTION" | "MANUAL_REDEEMED";
  orderNo?: string;
  redemptionCode?: string;
  createdAt: string;
}
export const activityApi = {
  list: (page = 1, type = "", signal?: AbortSignal) =>
    request<PageResult<Activity>>(
      `/public/activities?${new URLSearchParams({ page: String(page), size: "12", ...(type ? { type } : {}) })}`, "GET", undefined, signal,
    ),
  detail: (id: number, signal?: AbortSignal) => request<Activity>(`/public/activities/${id}`, "GET", undefined, signal),
  participate: (id: number, requestId: string) =>
    request<ActivityParticipation>(
      `/user/activities/${id}/participations`,
      "POST",
      { requestId },
    ),
  records: (page = 1, activityId?: number, signal?: AbortSignal) =>
    request<PageResult<ActivityParticipation>>(
      `/user/activity-participations?${new URLSearchParams({ page: String(page), size: "20", ...(activityId ? { activityId: String(activityId) } : {}) })}`, "GET", undefined, signal,
    ),
};
