package org.token.service.compute;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.token.repository.compute.ComputeData;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ComputeWithdrawalSettingsService {
    private final ComputeData data;
    private final ComputeAgentScope scope;

    public Map<String, Object> limit() {
        scope.requireGlobalScope();
        return new ComputeWithdrawalLimitPolicy(data).configuration();
    }

    @Transactional
    public Map<String, Object> saveLimit(Map<String, Object> body) {
        scope.requireGlobalScope();
        return new ComputeWithdrawalLimitPolicy(data).save(body);
    }

    public Map<String, Object> review() {
        scope.requireGlobalScope();
        return new ComputeWithdrawalReviewPolicy(data).configuration();
    }

    @Transactional
    public Map<String, Object> saveReview(Map<String, Object> body) {
        scope.requireGlobalScope();
        return new ComputeWithdrawalReviewPolicy(data).save(body);
    }
}
