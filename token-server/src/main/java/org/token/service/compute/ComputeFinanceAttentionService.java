package org.token.service.compute;

import lombok.RequiredArgsConstructor;
import org.token.config.compute.ComputeMcagProperties;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.token.repository.compute.ComputeData.fields;
import static org.token.repository.compute.ComputeData.text;

/** 从本地已落库状态读取提醒快照，不向第三方查单、不触发资金或审核操作。 */
@Service
public class ComputeFinanceAttentionService {
    private final ComputeData data;
    private final ComputeAgentScope scope;
    private final ComputeMcagProperties gateway;
    private final org.token.repository.compute.ComputeFinanceAttentionRepository repository;
    @org.springframework.beans.factory.annotation.Autowired
    public ComputeFinanceAttentionService(ComputeData data,ComputeAgentScope scope,ComputeMcagProperties gateway,
            org.token.repository.compute.ComputeFinanceAttentionRepository repository) {
        this.data=data;this.scope=scope;this.gateway=gateway;this.repository=repository;
    }
    public ComputeFinanceAttentionService(ComputeData data,ComputeAgentScope scope,ComputeMcagProperties gateway) {
        this(data,scope,gateway,new org.token.repository.compute.ComputeFinanceAttentionRepository(data));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> snapshot(boolean withdrawal) {
        var rows=repository.snapshot(withdrawal,gateway.isEnabled(),gateway.getMerchantId(),gateway.getEnvironment(),scope.queryScope());
        boolean truncated = rows.size() > 500;
        if (truncated) rows = new ArrayList<>(rows.subList(0, 500));
        for (var row : rows) {
            // 不把每次轮询更新时间当成新事件；仅实际订单、状态、原因或权威版本变化产生新键。
            row.put("eventKey", org.token.service.compute.ComputeCredentials.digest(data.json(List.of(text(row.get("source")), text(row.get("reference")),
                    text(row.get("attentionState")), text(row.get("version")), text(row.get("reason"))))));
            row.remove("version");
            row.remove("reason");
        }
        return fields("records", rows, "truncated", truncated);
    }

}
