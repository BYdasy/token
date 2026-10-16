import { useAccess } from '@vben/access';
import { useI18n } from '@vben/locales';
import { formatDateTime } from '@vben/utils';
import { getBusinessTimeZone } from '#/utils/business-time-zone';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import timezone from 'dayjs/plugin/timezone';

import { COMPUTE_PERMISSIONS } from '#/constants/permissions';
import { LEDGER_KINDS, ledgerDisplayKind } from '#/constants/ledger';

import './business.css';

export const P = COMPUTE_PERMISSIONS;
dayjs.extend(utc);
dayjs.extend(timezone);
export function useCompute() {
  const { t } = useI18n();
  const { hasAccessByCodes, hasAccessByRoles } = useAccess();
  const can = (...codes: string[]) => {
    const authorities = [...codes, 'super'];
    return hasAccessByCodes(authorities) || hasAccessByRoles(authorities);
  };
  const c = (key: string) => t(`admin.compute.${key}`);
  const enumValues = new Set([
    'AUTO',
    'CUSTOM',
    'CPU',
    'DAILY',
    'CLICK',
    'INSTANT',
    'DELAYED',
    'RESET',
    'KEEP',
    'GPU',
    'A',
    'B',
    'C',
    'D',
    'COMPUTE',
    'PROJECT',
    'EARNINGS',
    'WEEK',
    'MONTH',
    'PENDING',
    'APPROVED',
    'REJECTED',
    'ACTIVE',
    'COMPLETED',
    'FAILED',
    'RUNNING',
    'SUCCESS',
    'SUCCEEDED',
    'QUEUED',
    'CANCELLED',
    'ISSUED',
    'CONSUMED',
    'EXPIRED',
    'DEPOSIT',
    'WITHDRAWAL',
    'TRANSFER',
    'PURCHASE',
    'INCOME',
    'PRINCIPAL',
    'TEAM_REWARD',
    'TEAM_REWARD_TEST',
    'REGISTRATION_REWARD',
    'TRANSFER_OUT',
    'TRANSFER_IN',
    'ORDER_BUY',
    'WITHDRAW_FREEZE',
    'WITHDRAW_APPROVED',
    'WITHDRAW_RELEASE',
    'ORDER_INCOME',
    'ORDER_PRINCIPAL',
    'AUTO_INCOME',
    'ACTIVITY_REWARD',
    'CHECKIN_REWARD',
    'ADMIN_SYSTEM',
    'ADMIN_SALARY',
    'username',
    'email',
    'phone',
    'WHEEL',
    'RED_PACKET',
    'FIXED',
    'RANDOM',
    'PAID',
    'NO_PRIZE',
    'MESSAGE',
    'TASK',
    'ANNOUNCEMENT',
    'ARTICLE',
    'GENERAL',
    'INVESTMENT',
    'ALL',
    'USER',
    'GROUP',
    'AGENT',
    'NONE',
    'INTERNAL',
    'EXTERNAL',
  ]);
  const enumLabel = (value: unknown) =>
    LEDGER_KINDS.includes(ledgerDisplayKind(value)) ? c(`v25.ledger.${ledgerDisplayKind(value)}`)
      : ['AUTO', 'CUSTOM'].includes(String(value)) ? c('v8.legacyProduct') : enumValues.has(String(value)) ? c(`enum.${value}`) : String(value ?? '—');
  const date = (value: unknown) =>
    value ? formatDateTime(String(value)) : '—';
  return { c, can, enumLabel, date };
}

export interface ComputeField {
  labelKey?: string;
  key: string;
  kind?:
    | 'text'
    | 'decimal'
    | 'number'
    | 'boolean'
    | 'select'
    | 'multiselect'
    | 'textarea'
    | 'time'
    | 'datetime'
    | 'password';
  required?: boolean;
  min?: number;
  max?: number;
  options?: Array<{ label: string; value: number | string }>;
  values?: string[];
  hint?: string;
  group?: string;
  disabled?: boolean;
  precision?: number;
  maxLength?: number;
  minLength?: number;
  validationKey?: string;
}

export function toUtc(value: string | undefined) {
  if (!value) return undefined;
  return /(?:Z|[+-]\d{2}:\d{2})$/.test(value)
    ? dayjs(value).toISOString()
    : dayjs.tz(value, getBusinessTimeZone()).toISOString();
}
export const fromUtc = (value: unknown) =>
  value
    ? dayjs(String(value))
        .tz(getBusinessTimeZone())
        .format('YYYY-MM-DDTHH:mm:ss')
    : '';

/** 仅用于前端范围校验；转换为缩放整数以避免金额浮点比较误差。 */
export function compareDecimal(left: unknown, right: unknown) {
  const scale = (value: unknown) => {
    const [whole = '0', fraction = ''] = String(value ?? '0').split('.');
    return (
      BigInt(whole || '0') * 100000000n +
      BigInt(fraction.padEnd(8, '0').slice(0, 8))
    );
  };
  return scale(left) > scale(right);
}
