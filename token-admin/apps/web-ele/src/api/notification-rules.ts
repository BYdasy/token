import type { NotificationSeverity } from './operations';
import { requestClient } from './request';

export type NotificationRuleKey = 'largeDeposit' | 'largeWithdrawal' | 'withdrawalOverdue' | 'energyLow';
export interface NotificationRule {
  enabled: boolean;
  threshold?: string;
  minutes?: number;
  currency?: 'TRX' | 'USDT';
  templateCode: string;
  telegramTemplateCode: string;
  channelId: number | null;
  targetMode: 'ALL' | 'SELECTED';
  bindingIds: number[];
  popup: boolean;
  severity: NotificationSeverity;
}
export interface NotificationRulesSettings {
  global?: boolean;
  version: number;
  rules: Record<NotificationRuleKey, NotificationRule>;
  templates: {code: string; shortCode: string; name: string; channelType: 'INTERNAL' | 'TELEGRAM'; enabled: boolean | number}[];
}
export const fetchNotificationRules = () => requestClient.get<NotificationRulesSettings>('/system/notification-config/automation');
export const saveNotificationRules = (data: Pick<NotificationRulesSettings, 'version' | 'rules'>) => requestClient.put<NotificationRulesSettings>('/system/notification-config/automation', data);
