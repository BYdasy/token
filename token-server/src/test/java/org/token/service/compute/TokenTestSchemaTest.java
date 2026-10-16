package org.token.service.compute;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TokenTestSchemaTest {
    @Test void currentSnapshotLoadsWithCurrentColumnsAndUniqueness() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:current_schema_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        TokenTestSchema.createAll(jdbc);
        assertEquals(88, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('ad_detail','account_totp','admin_login_attempt','compute_setting','compute_agent_line','compute_mcag_deposit','compute_mcag_collection_receipt')", Integer.class));
        assertEquals(30, jdbc.queryForObject("SELECT numeric_scale FROM information_schema.columns WHERE table_name='compute_deposit' AND column_name='amount'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='compute_mcag_deposit_order' AND column_name='credit_origin'", Integer.class));
        jdbc.update("INSERT INTO ad_master(uid,username) VALUES(1,'fixture-admin')");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO ad_master(uid,username) VALUES(2,'fixture-admin')"));
        jdbc.update("INSERT INTO admin_security(admin_uid,updated_at) VALUES(1,CURRENT_TIMESTAMP)");
        assertEquals(0, jdbc.queryForObject("SELECT failed_count FROM admin_security WHERE admin_uid=1", Integer.class));
        assertNull(jdbc.queryForObject("SELECT totp_last_used_counter FROM admin_security WHERE admin_uid=1", Long.class));
    }
}
