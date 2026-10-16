import { describe, expect, it } from 'vitest';
import { normalizePhoneNumber } from '../src/utils/phone';

describe('注册国际手机号规范化', () => {
  it.each([
    ['2025550123', 'US', '+12025550123'],
    ['0701234567', 'AF', '+93701234567'],
    ['66282', 'AC', '+24766282'],
  ])('所选国家 %s / %s 支持一至三位区号', (input, country, expected) => {
    expect(normalizePhoneNumber(input, country)).toBe(expected);
  });

  it('完整号码自带区号优先于国家选择，并接受常用粘贴格式', () => {
    expect(normalizePhoneNumber('+93 70 123 4567', 'US')).toBe('+93701234567');
    expect(normalizePhoneNumber('00 1 (202) 555-0123', 'AF')).toBe('+12025550123');
    expect(normalizePhoneNumber('  +247 66282  ', 'US')).toBe('+24766282');
    expect(normalizePhoneNumber('+1 202.555.0123', 'invalid')).toBe('+12025550123');
  });

  it.each(['', ' ', '+', '+1', '+93', '+247', '1', '00', '++12025550123', '202+5550123',
    'javascript:12025550123', '<img src=x>', '+1 2025550123 ext. 4', '+１２０２５５５０１２３', '+12025550123456789'])
  ('拒绝空值、不完整或非手机号内容：%s', (input) => {
    expect(normalizePhoneNumber(input, 'US')).toBeUndefined();
  });
});
