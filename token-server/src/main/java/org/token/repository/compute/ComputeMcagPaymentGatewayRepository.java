package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeMcagPaymentGatewayMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeMcagPaymentGateway 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeMcagPaymentGatewayRepository {
    private final ComputeMcagPaymentGatewayMapper mapper;

    @Autowired
    public ComputeMcagPaymentGatewayRepository(ComputeMcagPaymentGatewayMapper mapper) { this.mapper = mapper; }

    public ComputeMcagPaymentGatewayRepository(ComputeData data) {
        this(data.mapper(ComputeMcagPaymentGatewayMapper.class));
    }

    public Map<String, Object> getOrCreateAddressMemberRow(Object... args) {
        return RepositoryResults.required(mapper.getOrCreateAddressMemberRow(args));
    }

    public List<Map<String, Object>> requestReconciliationMcagCollectionBatchRows(Object... args) {
        return ComputeData.normalizeRows(mapper.requestReconciliationMcagCollectionBatchRows(args));
    }

    public long payoutStatusMcagPayoutCount(String merchant, String environment) {
        return RepositoryResults.scalar(mapper.payoutStatusMcagPayoutCount(merchant, environment), Long.class);
    }

    public List<Map<String, Object>> payoutStatusMcagPayoutRows(String merchant, String environment, int pageSize, long offset) {
        return ComputeData.normalizeRows(mapper.payoutStatusMcagPayoutRows(merchant, environment, pageSize, offset));
    }

    public Map<String, Object> watchOrdersMcagCursorRow(Object... args) {
        return RepositoryResults.required(mapper.watchOrdersMcagCursorRow(args));
    }

    public int watchOrdersMcagCursorWrite(Object... args) {
        return mapper.watchOrdersMcagCursorWrite(args);
    }

    public List<Map<String, Object>> retryPricedDepositsMcagDepositOrderRows(Object... args) {
        return ComputeData.normalizeRows(mapper.retryPricedDepositsMcagDepositOrderRows(args));
    }

    public int retryPricedDepositsMcagDepositOrderWrite(Object... args) {
        return mapper.retryPricedDepositsMcagDepositOrderWrite(args);
    }

    public Map<String, Object> watchBatchesMcagCursorRow(Object... args) {
        return RepositoryResults.required(mapper.watchBatchesMcagCursorRow(args));
    }

    public int watchBatchesMcagCursorWrite(Object... args) {
        return mapper.watchBatchesMcagCursorWrite(args);
    }

    public Map<String, Object> createPayoutMcagPayoutRow(Object... args) {
        return RepositoryResults.required(mapper.createPayoutMcagPayoutRow(args));
    }

    public long depositReviewCount(String merchant, String environment) {
        return RepositoryResults.scalar(mapper.depositReviewCount(merchant, environment), Long.class);
    }

    public List<Map<String, Object>> depositReviews(String merchant, String environment, int pageSize, long offset) {
        var rows = ComputeData.normalizeRows(mapper.depositReviews(merchant, environment, pageSize, offset));
        for (var row : rows) for (String field : List.of("canReconcile", "canRecover", "missingLocalOrder", "credited"))
            row.put(field, ComputeData.enabled(row.get(field)));
        return rows;
    }

    public long depositAfterSequence(String merchant, String environment) {
        return RepositoryResults.scalar(mapper.depositAfterSequence(merchant, environment), Long.class);
    }

    public Map<String, Object> reconciliationDepositOrder(String merchant, String environment, String reference) {
        var rows = ComputeData.normalizeRows(mapper.reconciliationDepositOrder(merchant, environment, reference));
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }
}
