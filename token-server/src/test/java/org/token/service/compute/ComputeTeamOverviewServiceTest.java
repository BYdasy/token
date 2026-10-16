package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.service.SystemSettingService;
import org.token.repository.compute.ComputeData;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 隔离内存库验证可见层级、本人到账返点及自然日边界；不访问业务库。 */
class ComputeTeamOverviewServiceTest {
    private JdbcTemplate jdbc;
    private ComputeTeamOverviewService service;
    private SystemSettingService settings;
    private static final Instant CURRENT = Instant.parse("2026-09-12T04:00:00Z");

    @BeforeEach void setup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:team_ui2_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        FixtureComputeData data = new FixtureComputeData(jdbc, new ObjectMapper());
        settings = mock(SystemSettingService.class);
        when(settings.resolveSystemTimeZone()).thenReturn("Asia/Shanghai");
        service = new ComputeTeamOverviewService(data, new ComputeMemberRelations(jdbc), settings);
        jdbc.execute("CREATE TABLE compute_member(id BIGINT PRIMARY KEY,username VARCHAR(50),inviter_id BIGINT,level INT DEFAULT 1,enabled INT DEFAULT 1,is_test INT DEFAULT 0,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE compute_level(id INT PRIMARY KEY,image_url VARCHAR(255))");
        jdbc.update("INSERT INTO compute_level VALUES(1,'/vip1.png')");
        jdbc.update("INSERT INTO compute_member(id,username,inviter_id,is_test,created_at) VALUES"
                + "(1,'owner',NULL,0,'2026-08-01 00:00:00'),(2,'direct',1,0,'2026-09-11 15:59:59'),"
                + "(3,'tester',2,1,'2026-09-11 16:00:00'),(4,'formal-child',3,0,'2026-09-12 01:00:00'),"
                + "(5,'older-direct',1,0,'2026-09-10 01:00:00'),(900,'outside',NULL,0,'2026-09-12 01:00:00')");
        org.token.service.compute.TokenTestSchema.create(jdbc,"system_setting");
        jdbc.execute("CREATE TABLE compute_ledger(id BIGINT AUTO_INCREMENT PRIMARY KEY,member_id BIGINT,currency VARCHAR(16),amount DECIMAL(28,8),kind VARCHAR(30),wallet_type VARCHAR(16),frozen_change DECIMAL(28,8),reference_id VARCHAR(40),event_key VARCHAR(100),created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE compute_reward_award(id BIGINT PRIMARY KEY,member_id BIGINT,source_member_id BIGINT,source_kind VARCHAR(40),currency VARCHAR(16),amount DECIMAL(28,8))");
        jdbc.update("INSERT INTO compute_member(id,username,inviter_id,created_at) VALUES(6,'too-deep',4,'2026-09-12 01:00:00')");
        reward(1,1,2,"VIP_FIRST_DEPOSIT","USDT","10","2026-09-11 15:59:59");
        reward(2,1,3,"VIP_INCOME","USDT","2","2026-09-11 16:00:00");
        reward(3,1,4,"PRODUCT_INCOME","USDT","3","2026-09-12 01:00:00");
        reward(4,1,6,"VIP_DEPOSIT","USDT","100","2026-09-12 01:00:00");
        reward(5,1,900,"VIP_DEPOSIT","USDT","1000","2026-09-12 01:00:00");
        reward(6,2,3,"VIP_DEPOSIT","USDT","2000","2026-09-12 01:00:00");
        reward(7,1,1,"DEPOSIT_SELF","USDT","3000","2026-09-12 01:00:00");
        reward(8,1,2,"EFFECTIVE","USDT","4000","2026-09-12 01:00:00");
        // 不创建产品/充值/旧协议表：移除业绩区域后不再查询无用聚合。
    }

    @Test void onlyOwnPostedRebatesFromCurrentVisibleLevelsCount() {
        var result=overview(Map.of());var summary=map(result.get("summary"));
        assertEquals(3,result.get("supportedDepth"));assertEquals(4L,summary.get("memberCount"));
        assertEquals(2L,summary.get("directCount"));assertEquals(2L,summary.get("todayNewMembers"));
        assertAmount("15",summary.get("totalRewardToken"));
        assertAmount("5",summary.get("todayRewardToken"));
        assertFalse(summary.containsKey("totalInvestmentToken"));assertFalse(result.containsKey("currencySummaries"));
        jdbc.update("UPDATE compute_member SET is_test=1 WHERE id=1");
        jdbc.update("UPDATE compute_ledger SET kind='TEAM_REWARD_TEST' WHERE member_id=1");
        assertAmount("15",map(overview(Map.of()).get("summary")).get("totalRewardToken"));
    }

    @Test void currentDepthAndRelationshipChangesRestrictHistoricalRewardsWithoutRewritingThem() {
        jdbc.update("INSERT INTO system_setting(group_name,setting_key,setting_value) VALUES('compute','teamSupportedDepth','2')");
        assertAmount("12",map(overview(Map.of()).get("summary")).get("totalRewardToken"));
        assertEquals(3L,map(overview(Map.of()).get("summary")).get("memberCount"));
        assertNull(map(overview(Map.of("depth","3")).get("members")).get("depth"));
        assertEquals(3L,map(overview(Map.of("depth","3")).get("members")).get("total"));
        jdbc.update("UPDATE system_setting SET setting_value='4'");
        assertAmount("115",map(overview(Map.of()).get("summary")).get("totalRewardToken"));
        jdbc.update("UPDATE compute_member SET inviter_id=900 WHERE id=3");
        assertAmount("10",map(overview(Map.of()).get("summary")).get("totalRewardToken"));
        assertEquals(8,jdbc.queryForObject("SELECT COUNT(*) FROM compute_reward_award",Integer.class));
    }

    @Test void unpostedRewardsAndConflictingLedgerFactsDoNotCount() {
        reward(9,1,2,"DEPOSIT_DIRECT","USDT","1","2026-09-12 01:00:00");
        jdbc.update("UPDATE compute_ledger SET amount=2 WHERE reference_id='9'");
        reward(10,1,2,"VIP_DEPOSIT","USDT","1","2026-09-12 01:00:00");
        jdbc.update("UPDATE compute_ledger SET event_key='V9_REWARD:010' WHERE reference_id='10'");
        reward(11,1,2,"VIP_DEPOSIT","USDT","1","2026-09-12 01:00:00");
        jdbc.update("UPDATE compute_ledger SET currency='USDC' WHERE reference_id='11'");
        reward(12,1,2,"VIP_DEPOSIT","USDT","1","2026-09-12 01:00:00");
        jdbc.update("DELETE FROM compute_ledger WHERE reference_id='12'");
        reward(13,1,2,"VIP_INCOME","USDT","1","2026-09-12 01:00:00");
        jdbc.update("UPDATE compute_ledger SET wallet_type='COMPUTE' WHERE reference_id='13'");
        assertAmount("15",map(overview(Map.of()).get("summary")).get("totalRewardToken"));
    }

    @Test void missingHistoricalValuationIsMarkedWithoutCurrentPriceEstimates() {
        reward(9,1,2,"VIP_INCOME","BTC","0.5","2026-09-12 01:00:00");
        var result=overview(Map.of());
        assertAmount("15",map(result.get("summary")).get("totalRewardToken"));
        assertEquals(false,map(result.get("rewardValuation")).get("complete"));
        assertEquals(1L,map(result.get("rewardValuation")).get("unpricedCount"));
        assertEquals(Set.of("BTC"),map(result.get("rewardValuation")).get("unpricedCurrencies"));
    }

    @Test void rewardTrendUsesPostingTimeAndNaturalDaysWithZeroFill() {
        var result=overview(Map.of("fromDate","2026-09-11","toDate","2026-09-13"));
        var trend=rows(result.get("trend"));assertEquals(3,trend.size());
        assertEquals(1L,trend.get(0).get("newMembers"));assertEquals(2L,trend.get(1).get("newMembers"));
        assertAmount("10",trend.get(0).get("rewardToken"));assertAmount("5",trend.get(1).get("rewardToken"));
        assertAmount("0",trend.get(2).get("rewardToken"));
        assertAmount("5",map(overview(Map.of("fromDate","2026-09-12","toDate","2026-09-12")).get("summary")).get("periodRewardToken"));
        // 今日值不受图表期间影响；开始秒包含，次日边界排除。
        reward(9,1,2,"VIP_DEPOSIT","USDT","7","2026-09-12 16:00:00");
        var previousDay=map(overview(Map.of("fromDate","2026-09-11","toDate","2026-09-11")).get("summary"));
        assertAmount("10",previousDay.get("periodRewardToken"));
        assertAmount("5",previousDay.get("todayRewardToken"));
    }

    @Test void pagingAndDepthFilterKeepCompleteSummaryAndNeverReturnPrivateFields() {
        var result = overview(Map.of("depth", "3", "pageSize", "1"));
        var members = map(result.get("members"));
        assertEquals(1L, members.get("total"));
        var member = rows(members.get("records")).get(0);
        assertEquals("formal-child", member.get("username")); assertEquals("tester", member.get("inviterUsername"));
        assertEquals(Set.of("username", "inviterUsername", "level", "createdAt", "depth", "avatarUrl"), member.keySet());
        assertEquals("/vip1.png", member.get("avatarUrl"));
        assertEquals(4L, map(result.get("summary")).get("memberCount"));
        var empty = map(overview(Map.of("depth", "3", "page", "2", "pageSize", "1")).get("members"));
        assertEquals(1L, empty.get("total")); assertTrue(rows(empty.get("records")).isEmpty());
    }

    @Test void childEntryOnlySeesItsOwnDescendantsAndEmptyTeamsHaveCompleteZeros() {
        var child = service.overview(3, Map.of(), CURRENT);
        assertEquals(2L, map(child.get("summary")).get("memberCount"));
        assertEquals(2L, service.invitation(3).get("memberCount"));
        assertEquals("formal-child", rows(map(child.get("members")).get("records")).get(0).get("username"));
        var empty = service.overview(900, Map.of(), CURRENT);
        assertEquals(0L, map(empty.get("summary")).get("memberCount"));
        assertAmount("0", map(empty.get("summary")).get("totalRewardToken"));
        assertEquals(Map.of("memberCount", 0L, "totalRewardsToken", "0.00000000", "rewardsByCurrency", List.of(), "complete", true), service.invitation(900));
        assertEquals(3, rows(empty.get("structure")).size());
        assertTrue(rows(empty.get("structure")).stream().allMatch(row -> numberValue(row.get("memberCount")) == 0));
        assertEquals(true, map(empty.get("rewardValuation")).get("complete")); assertEquals(7, rows(empty.get("trend")).size());
    }

    @Test void invalidDatePaginationAndCyclesFailClearlyInsteadOfTruncating() {
        for (var query : List.of(Map.of("fromDate", "invalid"), Map.of("fromDate", "2026-01-01", "toDate", "2026-09-12"),
                Map.of("fromDate", "2026-09-13", "toDate", "2026-09-12"), Map.of("pageSize", "51"), Map.of("depth", "201"))) {
            assertThrows(BusinessException.class, () -> overview(query));
        }
        jdbc.update("UPDATE compute_member SET inviter_id=4 WHERE id=1");
        assertThrows(BusinessException.class, () -> overview(Map.of()));
        assertThrows(BusinessException.class, () -> service.invitation(1));
    }

    @Test void dstTransitionUsesActualNaturalDayLength() {
        when(settings.resolveSystemTimeZone()).thenReturn("America/New_York");
        jdbc.update("UPDATE compute_member SET created_at='2026-03-09 03:30:00' WHERE id=2");
        jdbc.update("UPDATE compute_member SET created_at='2026-03-09 04:00:00' WHERE id=3");
        jdbc.update("UPDATE compute_ledger SET created_at='2026-03-09 03:30:00' WHERE reference_id='1'");
        jdbc.update("UPDATE compute_ledger SET created_at='2026-03-09 04:00:00' WHERE reference_id='2'");
        var result = service.overview(1, Map.of("fromDate", "2026-03-08", "toDate", "2026-03-09"), Instant.parse("2026-03-09T03:30:00Z"));
        var trend = rows(result.get("trend"));
        assertEquals(1L, trend.get(0).get("newMembers")); assertEquals(1L, trend.get(1).get("newMembers"));
        assertEquals(1L, map(result.get("summary")).get("todayNewMembers"));
        assertAmount("10", trend.get(0).get("rewardToken"));
        assertAmount("10", map(result.get("summary")).get("todayRewardToken"));
        assertAmount("2", trend.get(1).get("rewardToken"));
    }

    private void reward(long id,long recipient,long source,String kind,String currency,String amount,String at) {
        jdbc.update("INSERT INTO compute_reward_award VALUES(?,?,?,?,?,?)",id,recipient,source,kind,currency,new BigDecimal(amount));
        jdbc.update("INSERT INTO compute_ledger(member_id,currency,amount,kind,wallet_type,frozen_change,reference_id,event_key,created_at) VALUES(?,?,?,'TEAM_REWARD','EARNINGS',0,?,?,?)",recipient,currency,new BigDecimal(amount),String.valueOf(id),"V9_REWARD:"+id,at);
    }
    private Map<String, Object> overview(Map<String, String> query) {
        var result = service.overview(1, query, CURRENT);
        var invitation = service.invitation(1);
        assertEquals(map(result.get("summary")).get("memberCount"), invitation.get("memberCount"));
        assertAmount(map(result.get("summary")).get("totalRewardToken").toString(), invitation.get("totalRewardsToken"));
        assertEquals(map(result.get("rewardValuation")).get("complete"), invitation.get("complete"));
        return result;
    }
    private static long numberValue(Object value) { return ((Number) value).longValue(); }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> rows(Object value) { return (List<Map<String, Object>>) value; }
    private static void assertAmount(String expected, Object actual) { assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString()))); }
}
