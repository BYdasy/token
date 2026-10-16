package org.token.service.compute;

import org.token.exception.BusinessException;
import org.token.config.compute.ComputeMcagProperties;

import java.math.BigDecimal;
import java.util.*;

/** 归集仅消费权威摘要；逐笔充值由 DEPOSIT_ORDER 独立核验，明细按界面需求分页。 */
final class ComputeMcagBatchReader {
    private static final Set<String> SWEEP_STATES = Set.of("WAITING", "QUEUED", "CONFIRMING", "SUCCEEDED", "FAILED", "REVIEW");
    private ComputeMcagBatchReader() { }

    static Map<String, Object> read(ComputeMcagClient client, ComputeMcagProperties config, String reference) {
        if (reference == null || reference.isBlank() || reference.length() > 128) throw invalid();
        var response = client.request("collection-batches/query", Map.of("batch_id", reference, "view", "summary"));
        if (!(response.get("items") instanceof List<?> rows) || rows.size() != 1 || !(rows.get(0) instanceof Map<?, ?>)) throw invalid();
        @SuppressWarnings("unchecked") var batch = (Map<String, Object>) rows.get(0);
        if (!reference.equals(batch.get("batch_id"))) throw invalid();
        validate(config, batch);
        return batch;
    }

    static void validate(ComputeMcagProperties config, Map<String, Object> batch) {
        if (!"LIVE".equals(config.getEnvironment()) || !Objects.equals(config.getMerchantId(), batch.get("merchant_id"))
                || !Objects.equals(config.getEnvironment(), batch.get("environment"))) throw invalid();
        for (String key : List.of("batch_id", "address_id", "asset_id", "network_id")) required(batch, key, 128);
        required(batch, "address", 200);
        if (!(batch.get("customer_id") instanceof String customer) || customer.length() > 128) throw invalid();
        String purpose = required(batch, "purpose", 32);
        if (!Set.of("CUSTOMER_DEPOSIT", "SERVICE_FEE").contains(purpose)
                || "CUSTOMER_DEPOSIT".equals(purpose) && customer.isBlank()
                || "SERVICE_FEE".equals(purpose) && !customer.isEmpty()) throw invalid();
        if (!"RECEIPT_SWEEP".equals(batch.get("accounting_model")) || !"INDEPENDENT".equals(batch.get("credit_status"))
                || !Set.of("READY", "REVIEW").contains(Objects.toString(batch.get("status"), "")) || !SWEEP_STATES.contains(Objects.toString(batch.get("sweep_status"), ""))) throw invalid();
        // 明细页不能混入后台消费；数量只作摘要核验，不按数量发起请求或分配内存。
        if (!Boolean.TRUE.equals(batch.get("details_available")) || batch.containsKey("items")
                || batch.containsKey("receipt_has_more") || batch.containsKey("receipt_next_cursor")) throw invalid();
        positiveLong(batch.get("resource_version"));
        positiveLong(batch.get("receipt_count"));
        BigDecimal total = amount(batch.get("amount"));
        if (total.signum() <= 0 || amount(batch.get("credited_amount")).add(amount(batch.get("uncredited_amount"))).compareTo(total) != 0) throw invalid();
    }

    static long positiveLong(Object value) {
        try {
            if (value instanceof Float || value instanceof Double) throw new IllegalArgumentException();
            long result = new BigDecimal(Objects.toString(value, "")).longValueExact();
            if (result < 1) throw new IllegalArgumentException();
            return result;
        } catch (IllegalArgumentException | ArithmeticException failure) { throw invalid(); }
    }

    private static BigDecimal amount(Object value) {
        try {
            if (!(value instanceof String text) || text.length() > 100 || !text.matches("[0-9]+(?:\\.[0-9]+)?")) throw new IllegalArgumentException();
            BigDecimal result = new BigDecimal(text);
            if (result.signum() < 0) throw new IllegalArgumentException();
            return result;
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }

    private static String required(Map<String, Object> value, String key, int limit) {
        if (!(value.get(key) instanceof String text) || text.isBlank() || text.length() > limit) throw invalid();
        return text;
    }
    private static BusinessException invalid() { return new BusinessException(502, "server.compute.payment.invalidResponse"); }
}