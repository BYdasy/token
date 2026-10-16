const precision = 100_000_000n;

/** 加减输入仅处理十进制文本；最终价格、额度和结算仍由服务端报价决定。 */
export function productInputUnits(value: string): bigint | null {
  const match = /^(\d{1,18})(?:\.(\d{1,8}))?$/.exec(value);
  if (!match) return null;
  return BigInt(match[1]!) * precision + BigInt((match[2] || '').padEnd(8, '0'));
}

function inputText(value: bigint): string {
  const fraction = (value % precision).toString().padStart(8, '0').replace(/0+$/, '');
  return `${value / precision}${fraction ? `.${fraction}` : ''}`;
}

export function stepProductInput(value: string, direction: -1 | 1, minimum: string, maximum: string): string {
  const lower = productInputUnits(minimum), upper = productInputUnits(maximum);
  if (lower === null || upper === null || lower > upper) return value;
  const current = productInputUnits(value);
  if (current === null) return inputText(lower);
  const next = current + BigInt(direction) * precision;
  return inputText(next < lower ? lower : next > upper ? upper : next);
}

export function canStepProductInput(value: string, direction: -1 | 1, minimum: string, maximum: string): boolean {
  const lower = productInputUnits(minimum), upper = productInputUnits(maximum);
  if (lower === null || upper === null || lower > upper) return false;
  const current = productInputUnits(value);
  return current === null || (direction < 0 ? current > lower : current < upper);
}
