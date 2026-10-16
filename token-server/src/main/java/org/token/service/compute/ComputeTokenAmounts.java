package org.token.service.compute;

import org.token.repository.compute.ComputeTokenAmountsRepository;

import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Set;
import static org.token.repository.compute.ComputeData.*;

/** Token 输入与原币金额的十进制换算；不兑换钱包，不请求外部行情。 */
public final class ComputeTokenAmounts {
    private ComputeTokenAmounts() { }

    public static BigDecimal rate(ComputeData data, String currency) {
        if (Set.of("USD", "USDT", "USDC", "USDT0", "TOKEN").contains(currency)) return BigDecimal.ONE;
        var assets = new ComputeTokenAmountsRepository(data).rateCurrencyAssetRows(currency);
        boolean fiat = !assets.isEmpty() && "FIAT".equals(assets.get(0).get("assetType"));
        var rates = new ComputeTokenAmountsRepository(data).rateExchangeRateRows(fiat ? "FIAT" : "ASSET", currency);
        if (!rates.isEmpty() && decimal(rates.get(0).get("rate")).signum() > 0) {
            BigDecimal value = decimal(rates.get(0).get("rate"));
            return fiat ? BigDecimal.ONE.divide(value, 18, RoundingMode.HALF_UP) : value;
        }
        Map<String,Object> config = data.jsonMap(data.setting("userPresentation", "{}"));
        if (config.get("valuation") instanceof Map<?,?> valuation && valuation.get("assetUsdRates") instanceof Map<?,?> legacy) {
            BigDecimal value = decimal(legacy.get(currency));
            if (value.signum() > 0) return value;
        }
        throw new BusinessException("server.compute.v10.rateUnavailable");
    }

    public static BigDecimal asToken(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(8, RoundingMode.HALF_UP);
    }

    public static BigDecimal toNative(BigDecimal token, BigDecimal rate) {
        if (token.signum() <= 0 || token.scale() > 8 || token.precision() - token.scale() > 18 || rate.signum() <= 0)
            throw new BusinessException("server.compute.invalidAmount");
        // 向下收敛到现有账本精度，不向用户多收取一个最小单位。
        BigDecimal result = token.divide(rate, 8, RoundingMode.DOWN);
        if (result.signum() <= 0 || result.precision() - result.scale() > 18)
            throw new BusinessException("server.compute.invalidAmount");
        return result;
    }

    public static BigDecimal requestAmount(ComputeData data, Map<String,Object> request, String currency) {
        BigDecimal value = decimal(request.get("amount"));
        if (!"TOKEN".equals(text(request.get("amountUnit")))) return value;
        BigDecimal currentRate = rate(data, currency);
        if (decimal(request.get("expectedAssetRate")).compareTo(currentRate) != 0)
            throw new BusinessException("server.compute.v10.rateChanged");
        return toNative(value, currentRate);
    }
}
