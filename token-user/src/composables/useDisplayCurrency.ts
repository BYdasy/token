import { computed, ref } from 'vue';
import { useSession } from '@/stores/session';
import { money } from '@/utils/format';

const selected = ref(localStorage.getItem('token-user-display-fiat') || 'USD');
export function useDisplayCurrency() {
  const session = useSession();
  const rates = computed(() => ({ USD: '1', ...session.config?.presentation?.valuation.fiatPerUsd }));
  const currencies = computed(() => Object.keys(rates.value).filter(code => Number(rates.value[code as keyof typeof rates.value]) > 0));
  const currency = computed(() => currencies.value.includes(selected.value) ? selected.value : 'USD');
  function select(code: string) {
    if (!currencies.value.includes(code)) return;
    selected.value = code;
    localStorage.setItem('token-user-display-fiat', code);
  }
  // 仅用于明确标注的近似展示；实际资金继续由服务端十进制账本计算。
  function approximate(token: string | number | undefined) {
    if (token === undefined) return '—';
    return money(Number(token) * Number(rates.value[currency.value as keyof typeof rates.value]), 2);
  }
  return { currencies, currency, select, approximate };
}
