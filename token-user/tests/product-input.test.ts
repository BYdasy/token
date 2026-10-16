import { describe, expect, it } from 'vitest';
import { canStepProductInput, productInputUnits, stepProductInput } from '../src/utils/product-input';

describe('项目购买输入加减', () => {
  it('按1份加减且不突破整数份数范围', () => {
    expect(stepProductInput('1', -1, '1', '1000000000')).toBe('1');
    expect(canStepProductInput('1', -1, '1', '1000000000')).toBe(false);
    expect(stepProductInput('2', -1, '1', '1000000000')).toBe('1');
    expect(stepProductInput('999999999', 1, '1', '1000000000')).toBe('1000000000');
    expect(canStepProductInput('1000000000', 1, '1', '1000000000')).toBe(false);
    expect(stepProductInput('', 1, '1', '3')).toBe('1');
  });

  it('金额保留八位小数且大额加减不经过Number', () => {
    expect(stepProductInput('0.12345678', 1, '0.00000001', '999999999999999999.99999999')).toBe('1.12345678');
    expect(stepProductInput('999999999999999998.12345678', 1, '0.00000001', '999999999999999999.99999999')).toBe('999999999999999999.12345678');
    expect(stepProductInput('999999999999999999.12345678', -1, '0.00000001', '999999999999999999.99999999')).toBe('999999999999999998.12345678');
    expect(productInputUnits('0.000000001')).toBeNull();
    expect(productInputUnits('1000000000000000000')).toBeNull();
  });

  it('临界步长夹在配置范围内，无法定价的范围不猜测金额', () => {
    expect(stepProductInput('1.2', -1, '0.7', '1.5')).toBe('0.7');
    expect(stepProductInput('1.2', 1, '0.7', '1.5')).toBe('1.5');
    expect(canStepProductInput('1.5', 1, '0.7', '1.5')).toBe(false);
    expect(stepProductInput('3', 1, '', '')).toBe('3');
    expect(canStepProductInput('3', 1, '', '')).toBe(false);
  });
});
