import { getCountries, parsePhoneNumberFromString, type CountryCode } from "libphonenumber-js";

/** 注册、登录与找回共用国际标准号码，允许用户直接粘贴带区号的号码。 */
export function normalizePhoneNumber(value: string, country: string): string | undefined {
  const input = value.trim();
  if (!/^[+0-9\s().-]+$/.test(input)) return undefined;
  const compact = input.replace(/[\s().-]/g, '').replace(/^00/, '+');
  if (!/^\+?\d+$/.test(compact)) return undefined;
  const region = getCountries().includes(country as CountryCode) ? country as CountryCode : undefined;
  // 完整国际号码以自带区号为准；本地号码仅用所选国家补区号，不按各国号码长度强制拒绝。
  const phone = parsePhoneNumberFromString(compact, region);
  return phone && /^\+[1-9][0-9]{5,14}$/.test(phone.number) ? phone.number : undefined;
}
