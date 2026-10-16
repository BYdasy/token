package org.token.service.compute;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeCatalogRepository;
import org.token.repository.support.FixtureComputeData;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 仅隔离 H2 读取：合并筛选必须先于分页，同时保持真实付款状态、会员范围和后台旧筛选。 */
class ComputeUserPaymentStatusGroupTest {
    private JdbcTemplate jdbc;
    private ComputeCatalogRepository repository;
    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:user_payment_group_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        TokenTestSchema.create(jdbc, "compute_deposit", "compute_withdrawal");
        repository = new ComputeCatalogRepository(new FixtureComputeData(jdbc, new ObjectMapper()));
    }
    private void insert(String resource, long id, long memberId, String status, String payment, String chain) {
        if (resource.equals("deposits")) {
            jdbc.update("INSERT INTO compute_deposit(id,member_id,wallet_type,currency,network,amount,status,payment_status,chain_status,created_at) VALUES(?,?,'COMPUTE','USDT','TRC20',10,?,?,?,TIMESTAMP '2026-10-14 00:00:00')", id, memberId, status, payment, chain);
        } else {
            jdbc.update("INSERT INTO compute_withdrawal(id,member_id,currency,network,amount,fee,net_amount,address,rule_snapshot,status,payment_status,chain_status,created_at) VALUES(?,?,'USDT','TRC20',10,1,9,'fixture-address','{}',?,?,?,TIMESTAMP '2026-10-14 00:00:00')", id, memberId, status, payment, chain);
        }
    }
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> rows(Map<String,Object> page) { return (List<Map<String,Object>>) page.get("records"); }
    @Test void successMatchesExistingUserPaymentStatePrecedenceForBothResources() {
        for (String resource : List.of("deposits", "withdrawals")) {
            insert(resource,1,1,"APPROVED","MANUAL_PENDING","NOT_APPLICABLE");
            insert(resource,2,1,"PENDING","MCAG_VERIFIED","CONFIRMED");
            insert(resource,3,1,"PROCESSING","MCAG_SUCCEEDED","CONFIRMED");
            insert(resource,4,1,"APPROVED","MCAG_REVIEW","CONFIRMED");
            insert(resource,5,1,"APPROVED","MCAG_SUCCEEDED","ORPHANED");
            insert(resource,6,1,"APPROVED","MCAG_DISABLED_ADDRESS","CONFIRMED");
            insert(resource,7,1,"APPROVED","MCAG_REVERSED","CONFIRMED");
            insert(resource,8,1,"APPROVED","MCAG_HELD","CONFIRMED");
            insert(resource,9,1,"APPROVED","MCAG_PENDING","WAITING");
            insert(resource,10,1,"APPROVED","MCAG_FAILED","FAILED");
            var success = repository.page(resource,1L,Map.of("statusGroup","SUCCESS"));
            assertEquals(3L,success.get("total"));
            assertEquals(List.of(3L,2L,1L),rows(success).stream().map(row -> row.get("id")).toList());
            assertEquals(7L,repository.page(resource,1L,Map.of("statusGroup","NOT_SUCCESS")).get("total"));
        }
    }
    @Test void notSuccessfulIncludesPendingWithoutRelabelingAndFiltersBeforeCountAndPage() {
        for (String resource : List.of("deposits", "withdrawals")) {
            insert(resource,1,1,"PENDING","MANUAL_PENDING","NOT_APPLICABLE");
            insert(resource,2,1,"APPROVED","MANUAL_PENDING","NOT_APPLICABLE");
            insert(resource,3,1,"PROCESSING","MCAG_QUEUED","WAITING");
            insert(resource,4,2,"PENDING","MANUAL_PENDING","NOT_APPLICABLE");
            var page = repository.page(resource,1L,Map.of("statusGroup","NOT_SUCCESS","page","2","size","1","memberId","2"));
            assertEquals(2L,page.get("total")); assertEquals(1,rows(page).size());
            assertEquals(1L,rows(page).get(0).get("id"));
            assertEquals("PENDING",rows(page).get(0).get("status"));
            assertEquals("MANUAL_PENDING",rows(page).get(0).get("paymentStatus"));
            assertEquals(3L,repository.page(resource,1L,Map.of()).get("total"));
        }
    }
    @Test void adminStatusIsUnchangedAndInvalidUserGroupsAreRejected() {
        insert("deposits",1,1,"APPROVED","MANUAL_PENDING","NOT_APPLICABLE");
        insert("deposits",2,1,"APPROVED","MCAG_REVIEW","CONFIRMED");
        insert("deposits",3,1,"PENDING","MANUAL_PENDING","NOT_APPLICABLE");
        assertEquals(2L,repository.page("deposits",null,Map.of("status","APPROVED","statusGroup","NOT_SUCCESS")).get("total"));
        assertThrows(BusinessException.class, () -> repository.page("deposits",1L,Map.of("statusGroup","FAILED")));
        assertThrows(BusinessException.class, () -> repository.page("orders",1L,Map.of("statusGroup","SUCCESS")));
    }
}
