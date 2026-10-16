import { chineseLanguageLabel } from '#/components/business/businessLanguages';

/** 国家只使用注册/登录时保存的地区事实，不根据当前 IP 重新定位。 */
const countryNames = new Intl.DisplayNames(['zh-CN'], {
  type: 'region',
  fallback: 'none',
});

/** 兼容旧接口返回英文名；保留实际中文配置和完整语言地区，不另发语言查询。 */
export function memberLanguageName(code: unknown, configuredName: unknown): string {
  return chineseLanguageLabel(code, configuredName) || '—';
}

export function memberCountryName(value: unknown): string {
  const country = String(value ?? '').trim();
  if (!country) return '—';
  if (!/^[a-z]{2}$/i.test(country)) return country;
  return countryNames.of(country.toUpperCase()) || country.toUpperCase();
}

export function memberAccessOrigin(ip: unknown, country: unknown): string {
  return `${String(ip ?? '').trim() || '—'} / ${memberCountryName(country)}`;
}

export function memberAmountAndCount(amount: unknown, count: unknown): string {
  return `${amount ?? '—'} / ${count ?? '—'}`;
}
