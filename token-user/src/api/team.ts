import { request } from "./index";
import type { Amount } from "./types";

export interface TeamOverview {
  supportedDepth: number;
  rewardValuation: { unit: 'token'; complete: boolean; unpricedCount: number; unpricedCurrencies: string[] };
  timeZone: string;
  fromDate: string;
  toDate: string;
  summary: {
    memberCount: number;
    directCount: number;
    totalInvites: number;
    todayNewMembers: number;
    periodNewMembers: number;
    totalRewardToken: Amount;
    todayRewardToken: Amount;
    periodRewardToken: Amount;
  };
  trend: {
    day: string;
    newMembers: number;
    directInvites: number;
    rewardToken: Amount;
  }[];
  structure: { depth: number; memberCount: number; newMembers: number }[];
  members: {
    depth: number | null;
    records: {
      username: string;
      avatarUrl?: string;
      inviterUsername?: string;
      level: number;
      levelName?: string;
      createdAt: string;
      depth: number;
    }[];
    total: number;
    page: number;
    pageSize: number;
  };
}

export interface TeamQuery {
  fromDate?: string;
  toDate?: string;
  page?: number;
  pageSize?: number;
  depth?: number;
}

export function fetchTeamOverview(query: TeamQuery = {}, signal?: AbortSignal) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== "") params.set(key, String(value));
  }
  return request<TeamOverview>(
    `/user/team/overview?${params}`,
    "GET",
    undefined,
    signal
  );
}
