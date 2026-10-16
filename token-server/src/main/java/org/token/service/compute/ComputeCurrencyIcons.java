package org.token.service.compute;

import java.util.Map;

/** 固定版本的公开 CC0 图标，仅为已确认的币种及网络生成默认数据库配置。 */
final class ComputeCurrencyIcons {
    private static final String CDN = "https://cdn.jsdelivr.net/npm/cryptocurrency-icons@0.18.1/svg/color/";
    private static final Map<String,String> ASSETS = Map.of("BTC","btc","ETH","eth","BNB","bnb","TRX","trx",
            "USDT","usdt","USDT0","usdt","USDC","usdc","POL","matic","MATIC","matic");
    private static final Map<String,String> NETWORKS = Map.of("BITCOIN_MAINNET","btc","ETHEREUM_MAINNET","eth",
            "BSC_MAINNET","bnb","TRON_MAINNET","trx","POLYGON_MAINNET","matic");
    private ComputeCurrencyIcons() { }
    static String asset(String currency) { return url(ASSETS.get(currency)); }
    static String network(String networkId) { return url(NETWORKS.get(networkId)); }
    private static String url(String icon) { return icon == null ? "" : CDN + icon + ".svg"; }
}
