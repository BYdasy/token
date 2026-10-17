package org.token.service.compute;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.token.repository.compute.ComputeDisplayNameRepository;
import org.token.repository.support.FixtureComputeData;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 永久客户归属的只读投影，使用当前结构快照创建隔离 H2。 */
class ComputeMerchantCustomerNamesTest {
    @Test void customerNamesAreScopedByMerchantAndEnvironmentAndRequireAnExistingMember() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:merchant_names_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        TokenTestSchema.create(jdbc, "compute_member", "compute_mcag_customer_identity");
        jdbc.update("INSERT INTO compute_member(id,username,password_hash,invite_code,enabled,created_at,updated_at) VALUES(123,'existing-user','test-only','EXIST123',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(456,'disabled-user','test-only','DISAB456',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO compute_mcag_customer_identity(merchant_id,environment,customer_id,member_id,wallet_type,created_at) VALUES "
                + "('current','LIVE','opaque-customer',123,'PROJECT',CURRENT_TIMESTAMP),"
                + "('current','LIVE','token_123_project_2',456,'PROJECT',CURRENT_TIMESTAMP),"
                + "('foreign','LIVE','foreign-customer',123,'PROJECT',CURRENT_TIMESTAMP),"
                + "('current','SANDBOX','other-environment',123,'PROJECT',CURRENT_TIMESTAMP),"
                + "('current','LIVE','removed-customer',789,'PROJECT',CURRENT_TIMESTAMP)");
        var repository = new ComputeDisplayNameRepository(new FixtureComputeData(jdbc, new ObjectMapper()));
        var rows = repository.merchantCustomerNames("current", "LIVE", Set.of("opaque-customer", "token_123_project_2",
                "foreign-customer", "other-environment", "removed-customer", "token_123_project"));
        assertEquals(2, rows.size());
        var names = rows.stream().collect(java.util.stream.Collectors.toMap(row -> row.get("customerId"), row -> row.get("username")));
        assertEquals("existing-user", names.get("opaque-customer"));
        assertEquals("disabled-user", names.get("token_123_project_2"));
        assertTrue(repository.merchantCustomerNames("current", "LIVE", Set.of()).isEmpty());
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM compute_mcag_customer_identity", Integer.class));
    }
}
