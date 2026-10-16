package org.token.service.compute;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/** 区块浏览器仅接受完整交易哈希和 HTTP(S) 地址，未知网络不猜链。 */
final class ComputeTransactionExplorer {
    private ComputeTransactionExplorer() { }
    private static final Map<String,String> DEFAULTS = Map.ofEntries(
            Map.entry("TRC20","https://tronscan.org"),Map.entry("TRON","https://tronscan.org"),Map.entry("TRON_MAINNET","https://tronscan.org"),
            Map.entry("BEP20","https://bscscan.com"),Map.entry("BSC","https://bscscan.com"),Map.entry("BSC_MAINNET","https://bscscan.com"),
            Map.entry("ERC20","https://etherscan.io"),Map.entry("ETH","https://etherscan.io"),Map.entry("ETHEREUM","https://etherscan.io"),Map.entry("ETHEREUM_MAINNET","https://etherscan.io"),
            Map.entry("POL","https://polygonscan.com"),Map.entry("POLYGON","https://polygonscan.com"),Map.entry("POLYGON_MAINNET","https://polygonscan.com"),
            Map.entry("BTC","https://mempool.space"),Map.entry("BITCOIN","https://mempool.space"),Map.entry("BITCOIN_MAINNET","https://mempool.space"));
    static String url(String configured, String network, String hash) {
        if (hash == null || !hash.matches("(?i)(?:0x)?[0-9a-f]{64}")) return "";
        String base = configured == null ? "" : configured.trim();
        if (base.isEmpty()) base = DEFAULTS.getOrDefault(network.toUpperCase(Locale.ROOT), "");
        if (base.isEmpty()) return "";
        try {
            boolean placeholder = base.contains("{txHash}");
            URI uri = URI.create(base.replace("{txHash}",hash));
            if (!java.util.Set.of("http","https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo()!=null) return "";
            if (placeholder) return uri.toASCIIString();
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String prefix = base.replaceAll("/+$", "");
            if (host.equals("tronscan.org") || host.endsWith(".tronscan.org"))
                return uri.getScheme()+"://"+uri.getRawAuthority()+"/#/transaction/"+hash.replaceFirst("(?i)^0x","");
            if (uri.getRawQuery()!=null || uri.getRawFragment()!=null) return "";
            return prefix+(prefix.endsWith("/tx") ? "/" : "/tx/")+hash;
        } catch (IllegalArgumentException invalid) { return ""; }
    }
}
