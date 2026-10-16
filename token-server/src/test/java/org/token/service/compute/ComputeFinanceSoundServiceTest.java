package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 仅隔离 H2 与模拟管理员；校验声音开关、合并间隔、配置版本和代理范围。 */
class ComputeFinanceSoundServiceTest {
    private ComputeFinanceSoundService service;
    private JdbcTemplate jdbc;
    private ComputeAgentScope scope;
    private final Map<String, Object> disabled = Map.of("enabled", false, "states", List.of("PENDING"), "source", "", "fileName", "");
    @BeforeEach void setup() throws Exception {
        var database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:sound_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(database);
        jdbc.execute("CREATE TABLE compute_finance_sound_config(id INT PRIMARY KEY,config_json VARCHAR(3000000),revision BIGINT,updated_by INT,updated_at TIMESTAMP)");
        var mapper = new ObjectMapper().findAndRegisterModules();
        jdbc.update("INSERT INTO compute_finance_sound_config VALUES(1,?,0,NULL,CURRENT_TIMESTAMP)", mapper.writeValueAsString(Map.of("deposits", disabled, "withdrawals", disabled)));
        scope = mock(ComputeAgentScope.class); when(scope.isGlobalScope()).thenReturn(true); when(scope.currentAdminUid()).thenReturn(7);
        service = new ComputeFinanceSoundService(new FixtureComputeData(jdbc, mapper), scope);
    }
    @Test void runtimeReturnsOnlyVersionWhenNothingChanged() {
        assertEquals(Map.of("version", 0L, "unchanged", true), service.settings(0));
        assertTrue(service.settings(-1).containsKey("deposits"));
    }
    @Test void globalSettingsAreVersionedAndNeverOverwriteConcurrentEditor() {
        var saved = service.save(Map.of("version", 0, "deposits", disabled, "withdrawals", disabled));
        assertEquals(1L, saved.get("version")); assertEquals(true, saved.get("global"));
        var conflict = assertThrows(BusinessException.class, () -> service.save(Map.of("version", 0, "deposits", disabled, "withdrawals", disabled)));
        assertEquals("server.compute.v12.soundChanged", conflict.getMessage());
        assertEquals(1L, jdbc.queryForObject("SELECT revision FROM compute_finance_sound_config", Long.class));
    }
    @Test void scopedAdministratorCannotSaveGlobalPolicy() {
        doThrow(ComputeAgentScope.denied()).when(scope).requireGlobalScope();
        assertThrows(BusinessException.class, () -> service.save(Map.of("version", 0, "deposits", disabled, "withdrawals", disabled)));
        assertEquals(0L, jdbc.queryForObject("SELECT revision FROM compute_finance_sound_config", Long.class));
    }
    @Test void onlySwitchesArePersistedAndLegacyAudioIsNotReturned() {
        assertEquals(Map.of("enabled",true),ComputeFinanceSoundService.normalize(Map.of("enabled",true,"source","old-audio","states",List.of())));
        assertEquals(Map.of("enabled",false),service.settings(-1).get("deposits"));
        var saved=service.save(Map.of("version",0,"deposits",Map.of("enabled",true),"withdrawals",Map.of("enabled",false),"intervalSeconds",5));
        assertEquals(5,saved.get("intervalSeconds"));
        assertEquals(Map.of("enabled",true),saved.get("deposits"));
        assertThrows(BusinessException.class,()->ComputeFinanceSoundService.normalize(Map.of("enabled","true")));
    }
    @Test void intervalMustBeAnIntegerWithinOneAnd3600Seconds() {
        assertEquals(1,ComputeFinanceSoundService.interval(1));assertEquals(3600,ComputeFinanceSoundService.interval(3600));
        for(Object invalid:List.of(0,-1,3601,"1.5","NaN"))assertThrows(BusinessException.class,()->ComputeFinanceSoundService.interval(invalid));
    }
}
