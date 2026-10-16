import { describe, expect, it } from 'vitest';
import { hasValidInvitedMemberTiers, normalizeInvitedMemberThreshold } from './invitedMemberTiers';

describe('邀请有效成员人数阶梯', () => {
  it('接受接口的八位尾零人数并以整数回显', () => {
    const tiers = ['1.00000000', '2.00000000', '5.00000000', '10.00000000']
      .map((threshold) => ({ threshold, selfCount: Number(threshold) }));
    expect(hasValidInvitedMemberTiers(tiers)).toBe(true);
    expect(tiers.map((tier) => normalizeInvitedMemberThreshold(tier.threshold))).toEqual(['1', '2', '5', '10']);
  });

  it('支持纯整数及边界，并去掉前导零', () => {
    expect(normalizeInvitedMemberThreshold(1)).toBe('1');
    expect(normalizeInvitedMemberThreshold('0002.00')).toBe('2');
    expect(normalizeInvitedMemberThreshold('1000000.00000000')).toBe('1000000');
  });

  it.each(['0', '1000001', '-1', '+1', '1.5', '1.00000001', '1.0000000000000001', '1e2', 'NaN', '', null, undefined, true, {}])(
    '拒绝小数、超界或非法人数：%s', (value) => {
      expect(normalizeInvitedMemberThreshold(value)).toBeUndefined();
    },
  );

  it('按人数值判断重复，不允许通过格式差异重复奖励', () => {
    expect(hasValidInvitedMemberTiers([
      { threshold: '1', selfCount: 1 }, { threshold: '01.00000000', selfCount: 2 },
    ])).toBe(false);
  });

  it.each([0, -1, 1.5, 1000001, '1', null, undefined, NaN, Infinity])('获得次数必须是有效的正整数：%s', (selfCount) => {
    expect(hasValidInvitedMemberTiers([{ threshold: '1.00000000', selfCount }])).toBe(false);
  });

  it('阶梯数量与次数保留原上限', () => {
    expect(hasValidInvitedMemberTiers([])).toBe(false);
    expect(hasValidInvitedMemberTiers(undefined)).toBe(false);
    const tiers = Array.from({ length: 30 }, (_, index) => ({ threshold: String(index + 1), selfCount: 1000000 }));
    expect(hasValidInvitedMemberTiers(tiers)).toBe(true);
    expect(hasValidInvitedMemberTiers([...tiers, { threshold: '31', selfCount: 1 }])).toBe(false);
  });
});
