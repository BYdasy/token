package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.repository.compute.ComputeData;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ComputeTestMemberParticipationTest {
    @Test void missingOrInvalidCutoverFailsClosedAndExactUtcBoundaryIsIncluded() {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:test_cutover_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc=new JdbcTemplate(source);
        org.token.service.compute.TokenTestSchema.create(jdbc,"system_setting");
        var policy=new ComputeTestMemberParticipation(new FixtureComputeData(jdbc,new ObjectMapper()));
        assertFalse(policy.allows(true,"2026-09-28T12:00:00Z"));
        assertTrue(policy.allows(false,"2020-01-01T00:00:00Z"));
        var args=new ArrayList<Object>();assertEquals(" AND m.is_test=0",policy.sourceFilter("m.is_test","d.reviewed_at",args));assertTrue(args.isEmpty());
        jdbc.update("INSERT INTO system_setting(group_name,setting_key,setting_value) VALUES('compute',?,?)",ComputeTestMemberParticipation.SETTING,"invalid");
        assertFalse(policy.allows(true,"2026-09-28T12:00:00Z"));
        jdbc.update("UPDATE system_setting SET setting_value='2026-09-28T12:00:00.123456Z'");
        assertFalse(policy.allows(true,"2026-09-28T12:00:00.123455Z"));
        assertTrue(policy.allows(true,"2026-09-28T20:00:00.123456+08:00"));
        assertTrue(policy.allows(true,"2026-09-28T12:00:00.123457Z"));
        assertTrue(policy.allows(true,LocalDateTime.parse("2026-09-28T12:00:00.123456")));
        assertTrue(policy.allows(true,java.sql.Timestamp.valueOf("2026-09-28 12:00:00.123456")));
        assertFalse(policy.allows(true,null));
        assertEquals(" AND (m.is_test=0 OR d.reviewed_at>=?)",policy.sourceFilter("m.is_test","d.reviewed_at",args));
        assertEquals(LocalDateTime.parse("2026-09-28T12:00:00.123456"),args.get(0));
    }
}
