package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 固定时间、隔离 H2；覆盖系统时区、周界、跨币网计数和覆盖规则，不连接支付或真实数据库。 */
class ComputeWithdrawalLimitPolicyTest {
    private JdbcTemplate jdbc;
    private ComputeWithdrawalLimitPolicy policy;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    @BeforeEach void setup() {
        var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:withdrawal-limit-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        org.token.service.compute.TokenTestSchema.create(jdbc,"system_setting");
        jdbc.execute("CREATE TABLE compute_withdrawal(member_id BIGINT,status VARCHAR(32),created_at TIMESTAMP,currency VARCHAR(20),network VARCHAR(20))");
        policy = new ComputeWithdrawalLimitPolicy(new FixtureComputeData(jdbc, new ObjectMapper()));
    }
    private void insert(long member,String status,String instant,String currency,String network) {
        jdbc.update("INSERT INTO compute_withdrawal VALUES(?,?,?,?,?)",member,status,LocalDateTime.ofInstant(Instant.parse(instant), ZoneOffset.UTC),currency,network);
    }
    private Map<String,Object> allowed(Map<String,Object> rule, String instant) {
        return policy.assertAllowed(1, rule, SHANGHAI, Instant.parse(instant));
    }
    @Test void defaultIsOnePerDayAndPendingOrdersCountAcrossNetworks() {
        var quote = allowed(Map.of(),"2026-09-21T03:00:00Z");
        assertEquals(1,quote.get("count")); assertEquals(1L,quote.get("remaining"));
        insert(1,"PENDING","2026-09-21T01:00:00Z","USDT","TRON");
        var error=assertThrows(BusinessException.class,()->allowed(Map.of(),"2026-09-21T03:00:00Z"));
        assertEquals("server.compute.v17.withdrawalLimitReached",error.getMessage());
    }
    @Test void usesSystemCalendarDayAndExcludesTheNextDayBoundary() {
        insert(1,"APPROVED","2026-09-20T15:59:59Z","USDT","BSC");
        insert(1,"PROCESSING","2026-09-21T16:00:00Z","BTC","BTC");
        var quote=allowed(Map.of(),"2026-09-21T00:00:00Z");
        assertEquals(0L,quote.get("used")); assertEquals("2026-09-21T16:00:00Z",quote.get("resetsAt"));
        insert(1,"APPROVED","2026-09-20T16:00:00Z","USDC","POLYGON");
        assertThrows(BusinessException.class,()->allowed(Map.of(),"2026-09-21T00:00:00Z"));
    }
    @Test void weeklyLimitStartsMondayAndCountsAllSuccessfulAndPendingOrders() {
        policy.save(Map.of("enabled",true,"period","WEEK","count",3));
        insert(1,"APPROVED","2026-09-20T15:59:59Z","USDT","BSC");
        insert(1,"APPROVED","2026-09-20T16:00:00Z","USDT","BSC");
        insert(1,"PROCESSING","2026-09-22T10:00:00Z","USDC","ETH");
        var quote=allowed(Map.of(),"2026-09-25T01:00:00Z");
        assertEquals(2L,quote.get("used")); assertEquals("2026-09-27T16:00:00Z",quote.get("resetsAt"));
        insert(1,"PENDING","2026-09-24T01:00:00Z","BTC","BTC");
        assertThrows(BusinessException.class,()->allowed(Map.of(),"2026-09-25T01:00:00Z"));
    }
    @Test void releasedFailuresAndOtherUsersDoNotOccupySlots() {
        insert(1,"REJECTED","2026-09-21T01:00:00Z","USDT","TRON");
        insert(1,"FAILED","2026-09-21T01:00:00Z","USDT","TRON");
        insert(2,"PENDING","2026-09-21T01:00:00Z","USDT","TRON");
        assertEquals(0L,allowed(Map.of(),"2026-09-21T03:00:00Z").get("used"));
    }
    @Test void unlimitedAndCustomRulesOverrideOnlyTheSelectedGlobalSetting() {
        insert(1,"APPROVED","2026-09-21T01:00:00Z","USDT","TRON");
        assertEquals(false,allowed(Map.of("withdrawalLimitMode","UNLIMITED"),"2026-09-21T03:00:00Z").get("enabled"));
        assertEquals(1L,allowed(Map.of("withdrawalLimitMode","DAY","withdrawalLimitCount",2),"2026-09-21T03:00:00Z").get("remaining"));
        policy.save(Map.of("enabled",false,"period","DAY","count",1));
        assertEquals(false,allowed(Map.of(),"2026-09-21T03:00:00Z").get("enabled"));
        assertThrows(BusinessException.class,()->allowed(Map.of("withdrawalLimitMode","DAY","withdrawalLimitCount",1),"2026-09-21T03:00:00Z"));
    }
    @Test void usesDstAwareMidnightAndRejectsMalformedConfiguration() {
        var quote=policy.assertAllowed(1,Map.of(),ZoneId.of("America/New_York"),Instant.parse("2026-03-08T07:00:00Z"));
        assertEquals("2026-03-09T04:00:00Z",quote.get("resetsAt"));
        assertThrows(BusinessException.class,()->policy.save(Map.of("enabled",true,"period","MONTH","count",1)));
        assertThrows(BusinessException.class,()->policy.save(Map.of("enabled",true,"period","DAY","count","1.1")));
        assertThrows(BusinessException.class,()->policy.save(Map.of("enabled",true,"period","DAY","count",0)));
        var values=new LinkedHashMap<String,Object>();
        ComputeWithdrawalLimitPolicy.ruleValues(Map.of(),Map.of("withdrawalLimitMode","WEEK","withdrawalLimitCount",4),values);
        assertEquals("WEEK",values.get("withdrawal_limit_mode")); assertEquals(4,values.get("withdrawal_limit_count"));
    }
}
