import { request } from "@/api";
import type { Amount } from "./types";

export interface UserFeatures {
  invitationEnabled: boolean;
  supportEnabled: boolean;
  supportSalesmartlyScriptUrl?: string;
  supportFloatingImage?: string;
  supportFloatingScope?: "HOME" | "ALL";
  supportFloatingBottom?: number;
  supportContacts: Array<{ name: string; url: string; image: string }>;
  checkinEnabled: boolean;
  dailyReward: Amount;
  cycleDays: number;
  cycleBonus: Amount;
  phoneCountry: string;
  floatingEntries?: Array<{
    key: string;
    path: string;
    image: string;
    bottom?: number;
    endsAt?: string | null;
  }>;
  activityBanners?: Array<{
    key: string;
    path: string;
    image: string;
    title: string;
    endsAt?: string | null;
  }>;
}
export interface UserPreferences {
  userLanguage?: string;
  avatarUrl?: string | null;
}
export interface CheckinRecord {
  orderNo: string;
  checkinDate: string;
  streakDays: number;
  rewardToken: Amount;
  createdAt: string;
}
export interface CheckinStatus {
  checked: boolean;
  today: string;
  streak: number;
  dailyReward: Amount;
  cycleDays: number;
  cycleBonus: Amount;
  records: CheckinRecord[];
}
export const featuresApi = {
  preferences: (background = false) => request<UserPreferences>("/user/preferences", "GET", undefined, undefined, background),
  language: (language: string, background = false) =>
    request<void>("/user/language", "PUT", { language }, undefined, background),
  checkinStatus: (signal?: AbortSignal) =>
    request<CheckinStatus>("/user/checkin", "GET", undefined, signal),
  checkin: () => request<CheckinStatus>("/user/checkin", "POST"),
};
