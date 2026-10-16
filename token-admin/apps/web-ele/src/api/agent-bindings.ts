import { requestClient } from './request';

export interface AgentMemberOption {
  id: number;
  username: string;
  inviteCode: string;
  depth?: number;
}

export interface AdminAgentBinding {
  adminUid: number;
  roots: AgentMemberOption[];
  scopeRestricted?: boolean;
}

const base = '/compute/admin';
export const fetchAgentBindingContext = () =>
  requestClient.get<{ global: boolean; canManageGlobal?: boolean }>(
    `${base}/agent-bindings/context`,
    { timeout: 15_000 }
  );

export const fetchAdminAgentBinding = (adminUid: number) =>
  requestClient.get<{ records: AdminAgentBinding[] }>(`${base}/agent-bindings`, {
    timeout: 15_000,
    params: { adminUid, page: 1, size: 1 },
  });

export const searchAgentMembers = (keyword: string) =>
  requestClient.get<{ records: AgentMemberOption[] }>(`${base}/agents`, {
    timeout: 15_000,
    params: { keyword, page: 1, size: 20 },
  });
