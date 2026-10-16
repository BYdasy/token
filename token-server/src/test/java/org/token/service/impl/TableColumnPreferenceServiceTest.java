package org.token.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TableColumnPreferenceServiceTest {
    private TableColumnPreferenceService service;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:columns_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE compute_admin_table_columns(admin_uid INT,page_key VARCHAR(100),columns_json VARCHAR(16000),updated_at TIMESTAMP,PRIMARY KEY(admin_uid,page_key))");
        service = new TableColumnPreferenceService(jdbc, new ObjectMapper());
    }

    @Test
    void preferencesPersistOrderAndRemainIsolatedByAccountAndPage() {
        assertNull(service.read(2, "user:members").get("columns"));
        service.save(2, "user:members", List.of("username", "email", "username"));
        service.save(3, "user:members", List.of("phone"));
        service.save(2, "report:team", List.of("totalUser"));
        assertEquals(List.of("username", "email"), service.read(2, "user:members").get("columns"));
        assertEquals(List.of("phone"), service.read(3, "user:members").get("columns"));
        assertEquals(List.of("totalUser"), service.read(2, "report:team").get("columns"));
        service.save(2, "user:members", List.of("email", "username"));
        assertEquals(List.of("email", "username"), service.read(2, "user:members").get("columns"));
    }

    @Test
    void rejectsNonKeyContentBeforePersisting() {
        assertThrows(BusinessException.class, () -> service.save(2, "members", List.of("<script>alert(1)</script>")));
        assertThrows(BusinessException.class, () -> service.save(2, "bad'page", List.of("id")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_admin_table_columns", Integer.class));
    }
}
