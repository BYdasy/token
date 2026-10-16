package org.token.service.compute;

import org.token.repository.compute.ComputeUsdAccountingRepository;

import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

import static org.token.repository.compute.ComputeData.*;

/** 平台以 USD 计价，USDT 仅为既有钱包的兼容编码；Token 不作为真实资产存储。 */
public final class ComputeUsdAccounting {
    public static final String UNIT = "USD";
    public static final String WALLET_CURRENCY = "USDT";
    private final ComputeData data;
    private final ComputeUsdAccountingRepository repository;

    public ComputeUsdAccounting(ComputeData data) { this.data = data;
        this.repository = new ComputeUsdAccountingRepository(data);
    }

    public static int version(Map<String,Object> record) {
        long value = record.get("accountingVersion") == null ? 1 : number(record.get("accountingVersion"));
        if (value != 1 && value != 2) throw new BusinessException("server.compute.requestConflict");
        return (int) value;
    }

    /** 每次直接读本地成功行情，不外呼、不使用界面估值配置或进程内缓存。 */
    public Map<String,Object> strictQuote(String currency) {
        if (Set.of("USD", "USDT", "USDC", "USDT0").contains(currency)) {
            String at = Instant.now().toString();
            return fields("usdRate", BigDecimal.ONE.toPlainString(), "provider", "FIXED", "sourceAt", at, "refreshedAt", at);
        }
        var assets = repository.strictQuoteCurrencyAssetRows(currency);
        if (assets.size() != 1) throw new BusinessException("server.compute.v10.rateUnavailable");
        boolean fiat = "FIAT".equals(assets.get(0).get("assetType"));
        var rows = repository.strictQuoteExchangeRateRows(fiat ? "FIAT" : "ASSET", currency);
        if (rows.size() != 1 || decimal(rows.get(0).get("rate")).signum() <= 0)
            throw new BusinessException("server.compute.v10.rateUnavailable");
        var row = rows.get(0);
        BigDecimal rate = decimal(row.get("rate"));
        if (fiat) rate = BigDecimal.ONE.divide(rate, 18, RoundingMode.HALF_UP);
        rate = rate.setScale(18, RoundingMode.HALF_UP);
        if (rate.signum() <= 0 || rate.precision() - rate.scale() > 18)
            throw new BusinessException("server.compute.v10.rateUnavailable");
        // MySQL JSON 数字和无类型 JSON 读取可能转为浮点；报价快照用十进制文本保留与 DECIMAL 列一致的精度。
        return fields("usdRate", rate.toPlainString(), "provider", row.get("provider"), "sourceAt", row.get("sourceAt"), "refreshedAt", row.get("refreshedAt"));
    }

    public static BigDecimal usdAmount(BigDecimal nativeAmount, BigDecimal rate) {
        return convert(nativeAmount, rate, RoundingMode.DOWN);
    }

    /** 冻结向上保留8位，避免原币金额超过实际冻结的USD购买力。 */
    public static BigDecimal withdrawalUsd(BigDecimal nativeAmount, BigDecimal rate) {
        return convert(nativeAmount, rate, RoundingMode.UP);
    }

    private static BigDecimal convert(BigDecimal amount, BigDecimal rate, RoundingMode rounding) {
        if (amount.signum() < 0 || rate.signum() <= 0) throw new BusinessException("server.compute.invalidAmount");
        BigDecimal result = amount.multiply(rate).setScale(8, rounding);
        if (result.precision() - result.scale() > 20) throw new BusinessException("server.compute.invalidAmount");
        return result;
    }

    public static BigDecimal nativeAvailable(BigDecimal usd, BigDecimal rate, int scale) {
        if (usd.signum() < 0 || rate.signum() <= 0) throw new BusinessException("server.compute.invalidAmount");
        return usd.divide(rate, Math.max(0, Math.min(8, scale)), RoundingMode.DOWN);
    }

    /** 历史记录只读固定财务快照，行情刷新绝不改变订单中显示的换算金额。 */
    public void presentRecords(String resource, List<Map<String,Object>> records) {
        String kind = switch (resource) { case "deposits" -> "DEPOSIT"; case "withdrawals" -> "WITHDRAWAL"; default -> null; };
        if (kind == null || records.isEmpty()) return;
        List<Long> ids = records.stream().filter(row -> version(row) == 1)
                .map(row -> number(row.get("id"))).toList();
        Map<Long,Map<String,Object>> snapshots = new HashMap<>();
        if (!ids.isEmpty()) {
            for (var row : new ComputeUsdAccountingRepository(data).legacySnapshots(kind, ids)) snapshots.put(number(row.get("businessId")), row);
        }
        for (var row : records) {
            if (version(row) == 2) continue;
            var snapshot = snapshots.get(number(row.get("id")));
            if (snapshot == null || snapshot.get("usdAmount") == null) continue;
            row.put("accountingCurrency", UNIT);
            row.put("accountingRate", snapshot.get("usdRate"));
            row.put("accountingAmount", snapshot.get("usdAmount"));
            row.put("accountingFee", snapshot.get("usdFee"));
            row.put("accountingNetAmount", snapshot.get("usdNetAmount"));
            row.put("accountingStatus", "LEGACY_VALUED");
            row.put("accountingValuationSource", snapshot.get("snapshotSource"));
        }
    }
}
