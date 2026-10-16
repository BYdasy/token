import type { SystemPageQuery, SystemPageResult } from './system';

import { requestClient } from './request';
import { setCurrentTimezone } from '@vben/utils';
import { useTimezoneStore } from '@vben/stores';
import { setBusinessTimeZone } from '#/utils/business-time-zone';

export type NotificationDisplayType = 'MESSAGE' | 'POPUP';
export type NotificationSeverity = 'INFO' | 'SUCCESS' | 'WARNING' | 'ERROR';
export type NotificationTargetType = 'ALL' | 'ROLE' | 'USER';
export type NotificationChannelType = 'DINGTALK' | 'EMAIL' | 'TELEGRAM';
export type NotificationTemplateChannelType =
  | 'DINGTALK'
  | 'EMAIL'
  | 'INTERNAL'
  | 'TELEGRAM';

export interface NotificationRecord {
  severity?: NotificationSeverity;
  content?: string;
  createdAt?: string;
  createTime?: string;
  id: number;
  isRead?: boolean;
  link?: string;
  readTime?: string;
  recipientUid?: number;
  recipientUsername?: string;
  readStatus?: number;
  senderUid?: number;
  senderUsername?: string;
  title?: string;
}

export interface NotificationChannel {
  channelType: NotificationChannelType;
  config: Record<string, any>;
  defaultRecipients?: string;
  enabled: boolean | number;
  id?: number;
  name: string;
}

export interface NotificationTemplate {
  shortCode?: string;
  channelType?: NotificationTemplateChannelType;
  code?: string;
  contentTemplate: string;
  enabled: boolean | number;
  enabledLanguages?: NotificationTemplateLanguage[];
  id?: number;
  name: string;
  titleTemplate?: string;
  translations?: NotificationTemplateTranslation[];
  translationType?: number;
}

export interface NotificationTemplateLanguage {
  lang: string;
  name?: string;
  nativeName?: string;
}

export interface NotificationTemplateTranslation {
  content: string;
  lang: string;
  title?: string;
}

export interface NotificationTemplateTranslateResult {
  translations: NotificationTemplateTranslation[];
}

export interface NotificationPushEvent {
  severity?: NotificationSeverity;
  content: string;
  createdAt?: string;
  displayType: NotificationDisplayType;
  eventId: string;
  notificationId: null | number;
  readStatus: null | number;
  title?: null | string;
}

export interface SiteSettings {
  cache?: {
    configuredMode: 'AUTO' | 'MEMORY' | 'REDIS';
    effectiveMode: 'DISABLED' | 'MEMORY' | 'REDIS';
    enabled: boolean;
    features: Record<string, boolean>;
    localEntryCount: number;
    redisAvailable: boolean;
  };
  cacheEnabled?: boolean;
  cacheIpWhitelistEnabled?: boolean;
  cacheLanguagesEnabled?: boolean;
  cacheMenusEnabled?: boolean;
  cacheMode?: 'AUTO' | 'MEMORY' | 'REDIS';
  cacheRolesEnabled?: boolean;
  cacheSettingsEnabled?: boolean;
  dateTimeFormat?: string;
  serverTime?: string;
  siteIcon?: string;
  siteName?: string;
  siteLogo?: string;
  userSiteName?: string;
  userSiteLogo?: string;
  userSiteIcon?: string;
  userHeroImage?: string;
  userShareEntryUrl?: string;
  userShareTitle?: string;
  userShareDescription?: string;
  userShareImage?: string;
  timeZone?: string;
  serverDisplayTimeZone?: string;
  userTimeZoneMode?: 'USER' | 'SYSTEM';
  [key: string]: any;
}

export interface ServerClock {
  systemTimeZone?: string;
  dateTimeFormat?: string;
  epochMillis: number;
  serverTime: string;
  timeZone: string;
}

export interface TimeZoneOption {
  id?: string;
  label?: string;
  offset?: string;
  timeZone?: string;
  value?: string;
}

export interface IpWhitelistRecord {
  createdAt?: string;
  createdBy?: number;
  enabled?: boolean;
  id?: number;
  description?: string;
  ipPattern?: string;
  updatedAt?: string;
}

export interface IpWhitelistStatus {
  activeRuleCount?: number;
  currentClientAllowed?: boolean;
  clientIp?: string;
  enabled?: boolean;
}

export interface TotpStatus {
  bound?: boolean;
  enabled?: boolean;
  required?: boolean;
}

export interface TotpSetup {
  otpauthUri: string;
  qrCodeDataUrl: string;
  secret: string;
}

function buildPageQuery(query: SystemPageQuery = {}) {
  return {
    filterParams: query.filterParams ?? {},
    page: { current: query.current ?? 1, size: query.size ?? 20 },
    sortColumn: query.sortColumn ?? { order: '', prop: '' },
  };
}

function normalizePage<T>(data: any): SystemPageResult<T> {
  const items = data?.records ?? data?.items ?? data?.list ?? [];
  return {
    current: Number(data?.current ?? data?.page ?? 1),
    items,
    size: Number(data?.size ?? data?.pageSize ?? items.length ?? 20),
    total: Number(data?.total ?? data?.count ?? items.length ?? 0),
  };
}

export async function fetchMyNotifications(
  query?: SystemPageQuery,
  silent = false,
  includeHidden = false,
) {
  const data = await requestClient.post<any>(
    '/system/notifications/my/list',
    buildPageQuery(query),
    {
      ...(silent ? { headers: { 'X-Client-Silent-Error': '1', 'X-Background-Request': 'true' } } : {}),
      params: { includeHidden },
    },
  );
  return normalizePage<NotificationRecord>(data);
}

/** 使用当前 JWT 建立通知主动推送长连接。 */
export function connectNotificationStream(options: {
  onConnected?: () => void;
  onHeartbeat?: () => void;
  onOperationalStatus?: () => void;
  onFinanceOrders?: () => void;
  onEvent: (event: NotificationPushEvent) => void;
  onEnd?: () => void;
  signal?: AbortSignal;
}) {
  let buffer = '';
  return requestClient.requestSSE(
    '/system/notifications/my/stream',
    undefined,
    {
      headers: { 'X-Client-Silent-Error': '1', 'X-Background-Request': 'true' },
      onEnd: options.onEnd,
      onMessage(chunk) {
        buffer = `${buffer}${chunk}`.replaceAll('\r\n', '\n');
        let separator = buffer.indexOf('\n\n');
        while (separator >= 0) {
          const block = buffer.slice(0, separator);
          buffer = buffer.slice(separator + 2);
          const lines = block.split('\n');
          if (lines.some((line) => line.trim() === ':heartbeat' || line.trim() === ': heartbeat')) options.onHeartbeat?.();
          const eventName =
            lines
              .find((line) => line.startsWith('event:'))
              ?.slice(6)
              .trim() || 'message';
          const data = lines
            .filter((line) => line.startsWith('data:'))
            .map((line) => line.slice(5).trimStart())
            .join('\n');
          if (eventName === 'connected') {
            options.onConnected?.();
          } else if (eventName === 'operational-status') {
            options.onOperationalStatus?.();
          } else if (eventName === 'finance-orders') {
            options.onFinanceOrders?.();
          } else if (
            (eventName === 'message' || eventName === 'popup') &&
            data
          ) {
            options.onEvent(JSON.parse(data) as NotificationPushEvent);
          }
          separator = buffer.indexOf('\n\n');
        }
      },
      signal: options.signal,
    },
  );
}

export function fetchUnreadNotificationCount(silent = false) {
  return requestClient.get<number | { count?: number; unreadCount?: number }>(
    '/system/notifications/my/unread-count',
    { headers: { 'X-Background-Request': 'true', ...(silent ? { 'X-Client-Silent-Error': '1' } : {}) } },
  );
}

export function markNotificationRead(id: number, silent = false) {
  return requestClient.post(
    '/system/notifications/my/read',
    { id },
    silent ? { headers: { 'X-Client-Silent-Error': '1' } } : undefined,
  );
}

export function markAllNotificationsRead() {
  return requestClient.post('/system/notifications/my/read-all');
}

export function clearMyNotifications() {
  return requestClient.post('/system/notifications/my/clear');
}

export function hideMyNotification(id: number) {
  return requestClient.post('/system/notifications/my/hide', { id });
}

export async function fetchManagedNotifications(query?: SystemPageQuery) {
  const data = await requestClient.post<any>(
    '/system/notifications/manage/list',
    buildPageQuery(query),
  );
  return normalizePage<NotificationRecord>(data);
}

export function sendNotification(data: {
  severity?: NotificationSeverity;
  content: string;
  popup?: boolean;
  targetIds?: number[];
  targetType: NotificationTargetType;
  title: string;
}) {
  return requestClient.post('/system/notifications/send', {
    severity: data.severity ?? 'INFO',
    content: data.content,
    popup: Boolean(data.popup),
    target: { ids: data.targetIds ?? [], type: data.targetType },
    title: data.title,
  });
}

export function fetchNotificationTargets() {
  return requestClient.post<{
    accounts: Array<{ uid: number; username: string }>;
    roles: Array<{ id: number; name: string }>;
  }>('/system/notifications/targets');
}

export function fetchNotificationChannels() {
  return requestClient.post<NotificationChannel[]>(
    '/system/notification-config/channel/list',
  );
}

export function inspectTelegramChannel(id: number) {
  return requestClient.post<NotificationChannel>('/system/notification-config/telegram/inspect', { id });
}

export function saveNotificationChannel(data: NotificationChannel) {
  return requestClient.post<NotificationChannel>(
    '/system/notification-config/channel/save',
    data,
  );
}

export function deleteNotificationChannel(id: number) {
  return requestClient.post('/system/notification-config/channel/delete', {
    id,
  });
}

export function testNotificationChannel(data: {
  id: number;
  recipients?: string;
}) {
  return requestClient.post('/system/notification-config/channel/test', data);
}

export function getGoogleAuthorization(data: {
  configId: number;
  redirectUri?: string;
}) {
  return requestClient.post<{ state: string; url: string }>(
    '/system/notification-config/email/google/authorize',
    data,
  );
}

export function completeGoogleAuthorization(data: {
  code: string;
  configId: number;
  redirectUri?: string;
  state: string;
}) {
  return requestClient.post(
    '/system/notification-config/email/google/callback',
    data,
  );
}

export function fetchNotificationTemplates() {
  return requestClient.post<NotificationTemplate[]>(
    '/system/notification-config/template/list',
  );
}

export function fetchNotificationTemplateLanguages() {
  return requestClient.post<NotificationTemplateLanguage[]>(
    '/system/notification-config/template/languages',
  );
}

export function saveNotificationTemplate(data: NotificationTemplate) {
  return requestClient.post<NotificationTemplate>(
    '/system/notification-config/template/save',
    data,
  );
}

export function translateNotificationTemplate(data: {
  channelType?: NotificationTemplateChannelType;
  content: string;
  sourceLang?: string;
  targetLanguages?: string[];
  title?: string;
}) {
  return requestClient.post<NotificationTemplateTranslateResult>(
    '/system/notification-config/template/translate',
    data,
  );
}

export function deleteNotificationTemplate(id: number) {
  return requestClient.post('/system/notification-config/template/delete', {
    id,
  });
}

export async function fetchPublicSettings() {
  const settings = await requestClient.get<SiteSettings>(
    '/system/settings/public',
  );
  setBusinessTimeZone(settings.timeZone);
  if (settings.serverDisplayTimeZone || settings.timeZone) setCurrentTimezone(settings.serverDisplayTimeZone || settings.timeZone!);
  return settings;
}

export function fetchSiteSettings() {
  return requestClient.get<SiteSettings>('/system/settings');
}

export async function updateSiteSettings(data: SiteSettings) {
  const result = await requestClient.put<SiteSettings>(
    '/system/settings',
    data,
  );
  ++clockRevision;
  clockRequest = undefined;
  setBusinessTimeZone(result.timeZone);
  const displayZone = result.serverDisplayTimeZone || result.timeZone;
  if (displayZone) {
    setCurrentTimezone(displayZone);
    useTimezoneStore().$patch({ timezone: displayZone });
  }
  window.dispatchEvent(new CustomEvent('exp:time-zone-changed', { detail: result }));
  return result;
}

let clockRequest: Promise<ServerClock> | undefined;
let clockRevision = 0;
export function fetchServerTime() {
  if (clockRequest) return clockRequest;
  const revision = clockRevision;
  const request = requestClient
    .get<ServerClock>('/system/time', {
      headers: { 'X-Client-Silent-Error': '1' },
      timeout: 10_000,
    })
    .then((clock) => {
      if (revision === clockRevision) {
        setCurrentTimezone(clock.timeZone);
        setBusinessTimeZone(clock.systemTimeZone);
      }
      return clock;
    })
    .finally(() => {
      if (clockRequest === request) clockRequest = undefined;
    });
  clockRequest = request;
  return request;
}

export function fetchTimeZoneOptions(
  params: {
    limit?: number;
    q?: string;
    selected?: string;
  } = {},
) {
  return requestClient.get<TimeZoneOption[]>('/system/time/time-zones', {
    params,
  });
}

export function updateOwnTimeZone(timeZone: string) {
  return requestClient.put<ServerClock>('/system/time/time-zone', { timeZone });
}

export async function fetchIpWhitelist(query?: SystemPageQuery) {
  const data = await requestClient.post<any[]>('/system/ip-whitelist/list');
  const key = String(query?.filterParams?.key ?? '')
    .trim()
    .toLowerCase();
  const items = key
    ? data.filter((item) =>
        `${item.ipPattern || ''} ${item.description || ''}`
          .toLowerCase()
          .includes(key),
      )
    : data;
  return normalizePage<IpWhitelistRecord>({
    current: query?.current ?? 1,
    records: items,
    size: query?.size ?? items.length,
    total: items.length,
  });
}

export function createIpWhitelist(data: IpWhitelistRecord) {
  return requestClient.post('/system/ip-whitelist/create', {
    ...data,
    enabled: data.enabled ? 1 : 0,
  });
}

export function updateIpWhitelist(data: IpWhitelistRecord) {
  return requestClient.post('/system/ip-whitelist/update', {
    ...data,
    enabled: data.enabled ? 1 : 0,
  });
}

export function deleteIpWhitelist(id: number) {
  return requestClient.post('/system/ip-whitelist/delete', { id });
}

export function fetchIpWhitelistStatus() {
  return requestClient.get<boolean | IpWhitelistStatus>(
    '/system/ip-whitelist/status',
  );
}

export function updateIpWhitelistStatus(enabled: boolean) {
  return requestClient.post('/system/ip-whitelist/status/update', { enabled });
}

export function fetchTotpStatus() {
  return requestClient.get<TotpStatus>('/auth/totp/status');
}

export function setupTotp() {
  return requestClient.post<TotpSetup>('/auth/totp/setup');
}

export function confirmTotp(code: string) {
  return requestClient.post('/auth/totp/confirm', { code });
}

export function disableTotp(code: string) {
  return requestClient.post('/auth/totp/disable', { code });
}

export function fetchAccountTotpStatus(uid: number) {
  return requestClient.post<TotpStatus>(
    '/system/user_role/account/totp/status',
    { uid },
  );
}

export function requireAccountTotp(uid: number, required: boolean) {
  return requestClient.post('/system/user_role/account/totp/require', {
    required,
    uid,
  });
}

export function resetAccountTotp(uid: number) {
  return requestClient.post('/system/user_role/account/totp/reset', { uid });
}
