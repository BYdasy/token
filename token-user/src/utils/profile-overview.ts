import type { UserPresentation } from '@/api/presentation';

const walletTypes = ['COMPUTE', 'PROJECT', 'EARNINGS'] as const;

export function nonnegativeNumber(value: unknown): number | undefined {
  if ((typeof value !== 'number' && typeof value !== 'string') || String(value).trim() === '') return;
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? number : undefined;
}

/** 展示比例只读取服务端已计价的真实可用余额，不把冻结、赠送或本金混入。 */
export function profileAssetDistribution(assets: UserPresentation['assets']) {
  const wallets = walletTypes.map(type => {
    const rows = assets.wallets.filter(wallet => wallet.type === type);
    const values = rows.map(wallet => nonnegativeNumber(wallet.token));
    const amount = values.every(value => value !== undefined)
      ? values.reduce<number>((total, value) => total + value!, 0)
      : undefined;
    return { type, amount: amount !== undefined && Number.isFinite(amount) ? amount : undefined };
  });
  const sum = wallets.reduce((total, wallet) => total + (wallet.amount ?? 0), 0);
  const complete = assets.complete && Number.isFinite(sum) && wallets.every(wallet => wallet.amount !== undefined);
  let offset = 0;
  return {
    complete,
    total: complete ? sum : undefined,
    wallets: wallets.map(wallet => {
      const percent = complete ? (sum > 0 ? wallet.amount! / sum * 100 : 0) : undefined;
      const result = { ...wallet, percent, offset };
      offset += percent ?? 0;
      return result;
    }),
  };
}

export function profileSecurityScore(value: unknown) {
  const score = nonnegativeNumber(value);
  return score !== undefined && score <= 100 ? score : undefined;
}
