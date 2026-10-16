package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.token.repository.compute.ComputeData.*;
import static org.junit.jupiter.api.Assertions.*;

class ComputeVipModelAllocationTest {
    @Test void newExecutionsSelectEnabledModelsAndProvidersWithoutChangingPreviousCandidateSnapshot() {
        FixtureComputeData data = fixture();
        var level = fields("autoProviderIds", List.of(1, 2));
        var first = ComputeVipModelAllocation.models(data, level);
        assertEquals(List.of(1L, 2L), first.stream().map(row -> number(row.get("modelId"))).toList());
        data.jdbc().update("UPDATE compute_model SET enabled=1 WHERE id=3");
        data.jdbc().update("UPDATE compute_provider SET enabled=1 WHERE id=2");
        assertEquals(List.of(1L, 2L, 3L, 4L), ComputeVipModelAllocation.models(data, level).stream().map(row -> number(row.get("modelId"))).toList());
        assertEquals(2, first.size());
    }

    @Test void legacyModelBindingResolvesProvidersButExplicitEmptyBindingDoesNotRestoreIt() {
        FixtureComputeData data = fixture();
        assertEquals(List.of(1L), ComputeVipModelAllocation.providerIds(data, fields("autoModelIds", List.of(1, 2))));
        assertEquals(2, ComputeVipModelAllocation.models(data, fields("autoModelIds", List.of(1))).size());
        assertTrue(ComputeVipModelAllocation.models(data, fields("autoModelIds", List.of(1), "autoProviderIds", List.of())).isEmpty());
        data.jdbc().execute("CREATE TABLE compute_level(id BIGINT,auto_provider_ids VARCHAR(200))");
        data.jdbc().update("INSERT INTO compute_level VALUES(1,'[1,2]'),(2,NULL)");
        assertEquals(List.of(1L, 2L), ComputeVipModelAllocation.providerIds(data, data.one("SELECT * FROM compute_level WHERE id=1")));
        assertTrue(ComputeVipModelAllocation.providerIds(data, data.one("SELECT * FROM compute_level WHERE id=2")).isEmpty());
    }

    @Test void providerIdentifiersRejectFractionsNegativeAndNonArrayValues() {
        for (Object value : List.of(List.of(0), List.of(-1), List.of("1.5"), List.of("x"), "[1]"))
            assertThrows(BusinessException.class, () -> ComputeVipModelAllocation.normalizedIds(value));
        assertEquals(List.of(1L, 2L), ComputeVipModelAllocation.normalizedIds(List.of(2, 1, 2)));
    }

    private FixtureComputeData fixture() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:vip_models_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE compute_provider(id BIGINT PRIMARY KEY,name VARCHAR(100),enabled INT)");
        jdbc.execute("CREATE TABLE compute_model(id BIGINT PRIMARY KEY,provider_id BIGINT,name VARCHAR(100),enabled INT)");
        jdbc.update("INSERT INTO compute_provider VALUES(1,'供应商甲',1),(2,'供应商乙',0),(3,'未绑定供应商',1)");
        jdbc.update("INSERT INTO compute_model VALUES(1,1,'模型甲',1),(2,1,'模型乙',1),(3,1,'停用模型',0),(4,2,'停用供应商模型',1),(5,3,'未绑定模型',1)");
        return new FixtureComputeData(jdbc, new ObjectMapper().findAndRegisterModules());
    }
}
