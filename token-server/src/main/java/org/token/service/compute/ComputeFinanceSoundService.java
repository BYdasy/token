package org.token.service.compute;

import org.token.repository.compute.ComputeFinanceSoundRepository;

import lombok.RequiredArgsConstructor;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 财务提醒是全局展示配置；不修改审核、订单状态、手续费或用户资金。 */
@Service
public class ComputeFinanceSoundService {
    @org.springframework.beans.factory.annotation.Autowired
    public ComputeFinanceSoundService(ComputeData data, ComputeAgentScope scope, ComputeFinanceSoundRepository repository) {
        this.data = data;
        this.scope = scope;
        this.repository = repository;
    }

    public ComputeFinanceSoundService(ComputeData data, ComputeAgentScope scope) {
        this(data, scope, new ComputeFinanceSoundRepository(data));
    }

    private final ComputeData data;
    private final ComputeFinanceSoundRepository repository;
    private final ComputeAgentScope scope;

    public Map<String, Object> adminSettings() {
        var result = new LinkedHashMap<>(settings(-1));
        result.put("global", scope.isGlobalScope());
        return result;
    }

    public Map<String, Object> settings(long knownVersion) {
        var row = repository.settingsFinanceSoundConfigRow();
        long revision = ComputeData.number(row.get("revision"));
        if (revision == knownVersion) return Map.of("version", revision, "unchanged", true);
        var stored = repository.settingsFinanceSoundConfigRow2();
        var saved = data.jsonMap(stored.get("configJson"));
        var result = new LinkedHashMap<String,Object>();
        for(String kind:List.of("deposits","withdrawals")) {
            Object raw=saved.get(kind);
            result.put(kind,Map.of("enabled",raw instanceof Map<?,?> config && Boolean.TRUE.equals(config.get("enabled"))));
        }
        result.put("intervalSeconds",interval(saved.getOrDefault("intervalSeconds",5)));
        result.put("version", ComputeData.number(stored.get("revision")));
        result.put("updatedAt", stored.get("updatedAt"));
        return result;
    }

    @Transactional
    public Map<String, Object> save(Map<String, Object> input) {
        scope.requireGlobalScope();
        long version;
        try { version = Long.parseLong(Objects.toString(input.get("version"), "")); }
        catch (NumberFormatException error) { throw invalid(); }
        if (version < 0) throw invalid();
        var value = Map.of("deposits", normalize(input.get("deposits")), "withdrawals", normalize(input.get("withdrawals")),
                "intervalSeconds",interval(input.getOrDefault("intervalSeconds",5)));
        if (repository.saveFinanceSoundConfigWrite(data.json(value), scope.currentAdminUid(), ComputeData.now(), version) != 1) throw new BusinessException("server.compute.v12.soundChanged");
        return adminSettings();
    }

    static Map<String, Object> normalize(Object input) {
        if (!(input instanceof Map<?, ?> row) || !(row.get("enabled") instanceof Boolean enabled)) throw invalid();
        return Map.of("enabled", enabled);
    }
    static int interval(Object raw) {
        try {int value=new java.math.BigDecimal(Objects.toString(raw,"")).intValueExact();if(value>=1&&value<=3600)return value;}
        catch(NumberFormatException|ArithmeticException ignored) { }
        throw invalid();
    }
    private static BusinessException invalid() { return new BusinessException("server.compute.invalidInput"); }
}
