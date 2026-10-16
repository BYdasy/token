import { requestClient } from './request';

export type TelegramChatType = 'CHANNEL' | 'GROUP' | 'PRIVATE';
export interface TelegramBinding {
  id: number;
  channelId: number;
  chatId: string;
  name: string;
  chatType: TelegramChatType;
  enabled: 0 | 1;
  createdAt?: string;
  updatedAt?: string;
}
export interface TelegramSendOptions {
  channels: { id: number; name: string }[];
  bindings: TelegramBinding[];
  templates: { code: string; shortCode: string; name: string; variables: string[] }[];
}
export interface TelegramNotificationPayload {
  channelId: number;
  targetMode: 'ALL' | 'SELECTED';
  bindingIds: number[];
  templateCode?: string;
  title?: string;
  content?: string;
  variables?: Record<string, string>;
  language?: string;
}
export type TelegramBindingSave = Omit<TelegramBinding, 'createdAt' | 'id' | 'updatedAt'> & { id?: number };

export const fetchTelegramBindings = (channelId?: number) =>
  requestClient.post<TelegramBinding[]>('/system/notification-config/telegram/bindings', { channelId });
export const saveTelegramBinding = (data: TelegramBindingSave) =>
  requestClient.post<TelegramBinding>('/system/notification-config/telegram/binding/save', data);
export const fetchTelegramSendOptions = () =>
  requestClient.post<TelegramSendOptions>('/system/notification-config/telegram/send-options');
export const sendTelegramNotification = (data: TelegramNotificationPayload) =>
  requestClient.post<{ recipientCount: number }>('/system/notification-config/template/send', data);
