import { describe, expect, it } from 'vitest';
import { nativeDepositAmount } from './native-deposit-amount';

describe('管理端逐笔充值原币金额', () => {
  it('移除扩列补齐的尾零，保留全部十八位和三十位有效小数', () => {
    const amount = '11.696130139510566704000000000000';
    expect(nativeDepositAmount('deposits', { accountingModel: 'SINGLE_RECEIPT', amount })).toBe('11.696130139510566704');
    const tiny = '0.000000000000000000000000000001';
    expect(nativeDepositAmount('deposits', { accountingModel: 'SINGLE_RECEIPT', amount: tiny })).toBe(tiny);
    const large = '99999999999999999999.123456789012345678901234567890';
    expect(nativeDepositAmount('deposits', { accountingModel: 'SINGLE_RECEIPT', amount: large })).toBe('99999999999999999999.12345678901234567890123456789');
  });

  it('整数不留小数点，无效值不猜测或转换指数金额', () => {
    expect(nativeDepositAmount('deposits', { accountingModel: 'SINGLE_RECEIPT', amount: '12.000000000000000000000000000000' })).toBe('12');
    expect(nativeDepositAmount('deposits', { accountingModel: 'SINGLE_RECEIPT', amount: '0.000000000000000000000000000000' })).toBe('0');
    for (const amount of [null, undefined, '', '1e-30', NaN])
      expect(nativeDepositAmount('deposits', { accountingModel: 'SINGLE_RECEIPT', amount })).toBe('—');
  });

  it('提款、旧协议和手工充值保持原有文本', () => {
    const amount = '12.34000000';
    expect(nativeDepositAmount('withdrawals', { accountingModel: 'SINGLE_RECEIPT', amount })).toBe(amount);
    expect(nativeDepositAmount('deposits', { accountingModel: 'COLLECTION_BATCH', amount })).toBe(amount);
    expect(nativeDepositAmount('deposits', { amount })).toBe(amount);
  });
});
