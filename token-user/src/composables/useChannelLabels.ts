import { useI18n } from "vue-i18n";
import type { Currency } from "@/api/types";
import { currencyLabel } from "@/utils/currency";
import { networkName } from "@/utils/network";

/** 名称复用前台语言包；缺译使用通道名称，未知网络保留标准编码。 */
export function useChannelLabels() {
  const { t, te, locale } = useI18n();
  function label(key: string, fallback: string) {
    if (te(key, locale.value) || te(key, "en")) return t(key);
    return fallback;
  }
  const symbol = (channel: Pick<Currency, 'currency'>) => currencyLabel(channel.currency);
  const asset = (channel: Currency) => label(`user.currency.asset.${symbol(channel)}.name`, symbol(channel));
  const network = (channel: Pick<Currency, 'network'> & Partial<Pick<Currency, 'networkName'>>) =>
    label(`user.currency.network.${channel.network}.name`, networkName(channel));
  const depositHint = (channel: Currency) => label(`user.currency.channel.${channel.currency}.${channel.network}.depositHint`, "");
  return { asset, network, depositHint, symbol };
}
