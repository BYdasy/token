import { useSession } from '@/stores/session';
import { money } from '@/utils/format';
import { computed } from 'vue';

/** Token只是USD展示名称；历史原币估值仅供展示，新资金换算由服务端固定。 */
export function useToken() {
  const session = useSession();
  const unitName = computed(() => session.config?.presentation?.unitName?.trim() || 'token');
  // 仅供含展示计价名称的既有词条调用，不处理币种代码、模型Token用量或接口数据。
  const withUnit = (text: string) => text.replace(/\bToken\b/g, () => unitName.value);
  function tokenValue(amount: unknown, currency: unknown): number | null {
    if (amount === null || amount === undefined || amount === '') return null;
    const value = Number(amount);
    const code = String(currency || session.config?.defaultCurrency || '').toUpperCase();
    const rate = ['TOKEN', 'USD', 'USDT', 'USDC', 'USDT0'].includes(code)
      ? 1 : Number(session.config?.presentation?.valuation.assetUsdRates[code]);
    if (!Number.isFinite(value) || !Number.isFinite(rate) || rate <= 0) return null;
    const converted = value * rate;
    return Number.isFinite(converted) ? converted : null;
  }
  function tokenAmount(amount: unknown, currency: unknown, digits = 2): string {
    return money(tokenValue(amount, currency), digits);
  }
  function tokenTotal(rows: { amount: unknown; currency: unknown }[]): number | null {
    let total = 0;
    for (const row of rows) {
      if (Number(row.amount) === 0) continue;
      const value = tokenValue(row.amount, row.currency);
      if (value === null) return null;
      total += value;
    }
    return total;
  }
  return { tokenAmount, tokenValue, tokenTotal, unitName, withUnit };
}
