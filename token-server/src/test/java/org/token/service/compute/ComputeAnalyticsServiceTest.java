package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.service.SystemSettingService;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.token.repository.compute.ComputeData.fields;

/** 验证系统自然日、代理隔离、不可变资金估值与收益钱包余额，全部使用隔离内存数据库。 */
class ComputeAnalyticsServiceTest {
    JdbcTemplate jdbc; FixtureComputeData data; ComputeAnalyticsService service; ComputeAgentScope scope;
    ComputeFinanceSnapshotService snapshots; ComputeExchangeRateService rates; ComputeHomeFinanceService home;
    TransactionTemplate tx;
    @BeforeEach void setup() throws Exception {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:analytics_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(source);
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        org.token.service.compute.TokenTestSchema.create(jdbc,"system_setting");
        jdbc.execute("CREATE TABLE compute_member(id BIGINT PRIMARY KEY,is_test INT,group_id BIGINT,created_at TIMESTAMP,username VARCHAR(40))");
        for(String table:List.of("compute_deposit","compute_withdrawal","compute_order"))
            jdbc.execute("CREATE TABLE "+table+"(id BIGINT AUTO_INCREMENT PRIMARY KEY,order_no VARCHAR(32),member_id BIGINT,currency VARCHAR(12),amount DECIMAL(28,8),net_amount DECIMAL(28,8),fee DECIMAL(28,8),status VARCHAR(20),created_at TIMESTAMP,reviewed_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE compute_wallet(member_id BIGINT,currency VARCHAR(12),wallet_type VARCHAR(20),available DECIMAL(28,8),frozen DECIMAL(28,8))");
        TokenTestSchema.financeSnapshots(jdbc);
        scope=mock(ComputeAgentScope.class);when(scope.queryScope()).thenReturn(new org.token.model.query.MemberScope(false,List.of(1L,2L,4L)));
        var settings=mock(SystemSettingService.class);when(settings.resolveSystemTimeZone()).thenReturn("America/New_York");
        data=new FixtureComputeData(jdbc,new ObjectMapper());
        service=new ComputeAnalyticsService(data,scope,settings,mock(ComputeCurrencyService.class));
        rates=mock(ComputeExchangeRateService.class);
        when(rates.currentUsdQuote(anyString())).thenReturn(quote("1"));
        snapshots=new ComputeFinanceSnapshotService(data,rates);
        home=new ComputeHomeFinanceService(data,service,rates);
        jdbc.update("INSERT INTO compute_member VALUES(1,0,NULL,'2026-03-01 00:00:00','alpha'),(2,1,NULL,'2026-03-08 06:00:00','testuser'),(3,0,NULL,'2026-03-08 06:00:00','outside'),(4,0,0,'2026-03-08 07:00:00','beta')");
        jdbc.execute("ALTER TABLE compute_member ADD inviter_id BIGINT");
        jdbc.execute("ALTER TABLE compute_member ADD level BIGINT DEFAULT 1");
    }
    @Test void unifiedWithdrawalUsesFrozenAccountingQuoteAndPreservesSnapshotOnReplay(){
        var record=fields("id",901,"memberId",1,"orderNo","USD-SNAPSHOT","currency","TRX","amount","300","fee","3","netAmount","297",
                "accountingVersion",2,"accountingCurrency","USD","accountingRate","0.333333333333333333","accountingAmount","100","accountingFee","1","accountingNetAmount","99",
                "accountingQuoteJson",data.json(quote("0.333333333333333333")));
        snapshots.capture("WITHDRAWAL",record,LocalDateTime.parse("2026-03-08T12:00:00"));
        verifyNoInteractions(rates);
        var stored=data.one("SELECT * FROM compute_finance_snapshot WHERE business_id=901");
        assertDecimal("100",stored.get("usdAmount"));assertDecimal("99",stored.get("usdNetAmount"));
        assertEquals("USD_ACCOUNTING_V2",stored.get("snapshotSource"));
        record.put("accountingAmount","200");
        snapshots.capture("WITHDRAWAL",record,LocalDateTime.parse("2026-03-08T13:00:00"));
        assertDecimal("100",data.one("SELECT * FROM compute_finance_snapshot WHERE business_id=901").get("usdAmount"));
        verifyNoInteractions(rates);
    }

    @Test void springDstIncludesTestPeopleAndCountsButExcludesTheirAmounts(){
        deposit(1,"USDT","10.12345678","2026-03-09 03:59:59");
        deposit(1,"USDT","999","2026-03-09 04:00:00");
        when(rates.currentUsdQuote("BTC")).thenReturn(quote("50000"));
        deposit(1,"BTC","0.01","2026-03-08 07:00:00");
        deposit(2,"USDT","999","2026-03-08 07:00:00");
        deposit(3,"USDT","999","2026-03-08 07:00:00");
        var result=day();Map<?,?> current=(Map<?,?>)result.get("current");
        assertDecimal("510.12345678",current.get("depositAmount"));
        assertEquals(3L,current.get("depositCount"));assertEquals(2L,current.get("newMembers"));
        assertEquals(2L,current.get("depositMembers"));assertEquals(2L,current.get("firstDepositMembers"));
        assertEquals("USDT",result.get("summaryUnit"));assertFalse(result.containsKey("currencySummaries"));
        assertEquals("2026-03-08",((Map<?,?>)((List<?>)result.get("trend")).get(0)).get("day"));
    }
    @Test void equalPreviousWindowAndNetWithdrawalUseApprovalTime(){
        deposit(1,"USDT","7","2026-03-07 12:00:00");
        jdbc.update("INSERT INTO compute_withdrawal(order_no,member_id,currency,amount,net_amount,fee,status,created_at,reviewed_at) VALUES('WD01',1,'USDT',10,9,1,'APPROVED','2026-03-01 00:00:00','2026-03-08 12:00:00')");
        snapshots.capture("WITHDRAWAL",data.one("SELECT * FROM compute_withdrawal WHERE order_no='WD01'"),LocalDateTime.parse("2026-03-08T12:00:00"));
        var result=day();
        assertDecimal("7",((Map<?,?>)result.get("previous")).get("depositAmount"));
        assertDecimal("9",((Map<?,?>)result.get("current")).get("withdrawalAmount"));
        assertDecimal("-9",((Map<?,?>)result.get("current")).get("netFlow"));
        var totals=home.overview(Map.of());
        assertDecimal("10",totals.get("totalWithdrawals"));assertDecimal("1",totals.get("withdrawalFee"));
        assertDecimal("9",totals.get("withdrawalNetAmount"));assertDecimal("-2",totals.get("actualDepositWithdrawalDifference"));
        assertDecimal("1",((Map<?,?>)result.get("current")).get("withdrawalFee"));
        assertDecimal("10",((Map<?,?>)result.get("current")).get("withdrawalGrossAmount"));
    }
    @Test void replaysAndLaterPriceChangesNeverRevaluePostedHistoryAndMissingQuoteIsExplicit(){
        when(rates.currentUsdQuote("BTC")).thenReturn(quote("50000"));
        var posted=deposit(1,"BTC","0.01","2026-03-08 12:00:00");
        when(rates.currentUsdQuote("BTC")).thenReturn(quote("90000"));
        snapshots.capture("DEPOSIT",posted,LocalDateTime.parse("2026-03-08T13:00:00"));
        when(rates.currentUsdQuote("UNKNOWN")).thenReturn(Map.of());
        deposit(1,"UNKNOWN","9","2026-03-08 12:00:00");
        when(rates.currentUsdQuote("UNKNOWN")).thenReturn(quote("5"));
        var result=home.overview(Map.of());
        assertDecimal("500",result.get("totalDeposits"));assertEquals(1L,result.get("unvaluedCount"));
        assertDecimal("500",((Map<?,?>)day().get("current")).get("depositAmount"));
        assertEquals(1L,((Map<?,?>)day().get("current")).get("unvaluedCount"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_finance_snapshot",Integer.class));
        var missing=data.one("SELECT * FROM compute_finance_snapshot WHERE currency='UNKNOWN'");
        assertEquals("MISSING_RATE",missing.get("snapshotSource"));assertNull(missing.get("usdAmount"));
        var detail=(List<?>)home.details("DEPOSIT",Map.of()).get("records");
        assertEquals(2,detail.size());assertFalse(((Map<?,?>)detail.get(0)).containsKey("id"));
    }
    @Test void overviewIgnoresDateAndExcludesNonEarningsFrozenTestAndOutsideWallets(){
        jdbc.update("INSERT INTO compute_wallet VALUES(1,'USDT','EARNINGS',10,1000),(1,'BTC','EARNINGS',0.01,1),(1,'USDT','COMPUTE',999,0),(1,'USDT','PROJECT',999,0),(2,'USDT','EARNINGS',999,0),(3,'USDT','EARNINGS',999,0)");
        when(rates.currentUsdQuote("BTC")).thenReturn(quote("50000"));
        deposit(1,"USDT","20","2026-03-08 12:00:00");
        jdbc.update("INSERT INTO compute_order(member_id,status) VALUES(1,'ACTIVE'),(2,'ACTIVE'),(3,'ACTIVE'),(1,'FINISHED')");
        var result=home.overview(Map.of("startDate","2030-01-01","endDate","2030-01-02","groupId","0"));
        assertDecimal("510",result.get("totalBalance"));assertDecimal("20",result.get("totalDeposits"));
        assertEquals(3L,result.get("memberCount"));assertEquals(1L,result.get("totalDepositMembers"));
        jdbc.update("INSERT INTO system_setting(group_name,setting_key,setting_value) VALUES('compute','summaryCurrency','BTC')");
        assertEquals("USD",home.overview(Map.of()).get("summaryUnit"));assertEquals(true,home.overview(Map.of()).get("unitFallback"));
        assertEquals("BTC",data.setting("summaryCurrency",""));
    }
    @Test void homeActivationIncludesTestsOnlyAboveBaseAndMoneyUsesFixedSnapshots() {
        jdbc.update("UPDATE compute_member SET level=2 WHERE id IN (1,2,3)");
        deposit(1,"USDT","100","2026-03-08 12:00:00");deposit(2,"USDT","900","2026-03-08 12:00:00");
        for (long member : List.of(1L,2L,3L)) {
            jdbc.update("INSERT INTO compute_withdrawal(order_no,member_id,currency,amount,net_amount,fee,status,reviewed_at) VALUES(?,?,'TRX',100,90,10,'APPROVED','2026-03-08 12:00:00')","WD-"+member,member);
            when(rates.currentUsdQuote("TRX")).thenReturn(quote("0.2"));
            snapshots.capture("WITHDRAWAL",data.one("SELECT * FROM compute_withdrawal WHERE order_no=?","WD-"+member),LocalDateTime.parse("2026-03-08T12:00:00"));
        }
        when(rates.currentUsdQuote("TRX")).thenReturn(quote("999"));
        var result=home.overview(Map.of());assertEquals(3L,result.get("memberCount"));assertEquals(2L,result.get("activatedMemberCount"));
        assertEquals(2L,result.get("totalDepositMembers"));assertDecimal("100",result.get("totalDeposits"));
        assertDecimal("20",result.get("totalWithdrawals"));assertDecimal("2",result.get("withdrawalFee"));
        assertDecimal("18",result.get("withdrawalNetAmount"));assertDecimal("82",result.get("actualDepositWithdrawalDifference"));
        var global=(Map<?,?>)service.globalReportAt(Map.of("startDate","2026-03-08","endDate","2026-03-08"),Instant.parse("2026-03-08T15:00:00Z")).get("current");
        assertDecimal("20",global.get("withdrawalGrossAmount"));assertDecimal("2",global.get("withdrawalFee"));assertDecimal("18",global.get("withdrawalAmount"));
        assertEquals(2L,global.get("withdrawalCount"));
    }

    @Test void snapshotAndApprovalShareRollbackBoundary(){
        assertThrows(IllegalStateException.class,()->tx.execute(status->{
            deposit(1,"USDT","12","2026-03-08 12:00:00");
            throw new IllegalStateException("验证记账失败时回滚快照");
        }));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_deposit",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_finance_snapshot",Integer.class));
    }
    @Test void dailyAndRangePeopleAreDistinctAndFirstDepositChecksAllHistory(){
        deposit(1,"USDT","10","2026-03-01 12:00:00");
        deposit(1,"USDT","20","2026-03-08 12:00:00");
        deposit(1,"USDC","30","2026-03-09 12:00:00");
        deposit(4,"USDT","40","2026-03-08 13:00:00");
        deposit(4,"USDC","50","2026-03-08 13:00:00");
        deposit(4,"USDT","60","2026-03-09 12:00:00");
        deposit(2,"USDT","999","2026-03-08 14:00:00");
        deposit(3,"USDT","999","2026-03-08 14:00:00");
        var report=service.overviewAt(Map.of("startDate","2026-03-08","endDate","2026-03-09"),Instant.now());
        Map<?,?> total=(Map<?,?>)report.get("current");
        List<?> trend=(List<?>)report.get("trend");
        assertEquals(3L,total.get("depositMembers"));
        assertEquals(2L,total.get("firstDepositMembers"));
        assertEquals(6L,total.get("depositCount"));
        assertDecimal("200",total.get("depositAmount"));
        assertEquals(3L,((Map<?,?>)trend.get(0)).get("depositMembers"));
        assertEquals(2L,((Map<?,?>)trend.get(1)).get("depositMembers"));
        assertEquals(2L,((Map<?,?>)trend.get(0)).get("firstDepositMembers"));
        assertEquals(0L,((Map<?,?>)trend.get(1)).get("firstDepositMembers"));
        assertEquals(3L,home.overview(Map.of()).get("totalDepositMembers"));
        var daily=service.dailyReport(Map.of("startDate","2026-03-08","endDate","2026-03-09"));
        assertEquals(total,daily.get("current"));assertEquals(trend,daily.get("trend"));
        assertFalse(daily.containsKey("previous"));
    }
    @Test void successfulUnvaluedDepositStillCountsPersonButDoesNotInventValue(){
        when(rates.currentUsdQuote("UNKNOWN")).thenReturn(Map.of());
        deposit(4,"UNKNOWN","99","2026-03-08 12:00:00");
        Map<?,?> current=(Map<?,?>)day().get("current");
        assertEquals(1L,current.get("depositMembers"));
        assertEquals(1L,current.get("firstDepositMembers"));
        assertEquals(1L,current.get("unvaluedCount"));
        assertDecimal("0",current.get("depositAmount"));
    }
    @Test void excessiveAndReversedDatesAreRejectedBeforeQueries(){
        assertThrows(BusinessException.class,()->service.overviewAt(Map.of("startDate","2025-01-01","endDate","2026-01-01"),Instant.now()));
        assertThrows(BusinessException.class,()->service.overviewAt(Map.of("startDate","2026-01-02","endDate","2026-01-01"),Instant.now()));
    }
    @Test void teamUsernameResolvesExactlyInsideCurrentScopeAndCannotRevealOutsideUser(){
        deposit(1,"USDT","12","2026-03-08 12:00:00");
        var result=service.overviewAt(Map.of("startDate","2026-03-08","endDate","2026-03-08","teamUsername","alpha"),Instant.now());
        assertDecimal("12",((Map<?,?>)result.get("current")).get("depositAmount"));
        assertThrows(BusinessException.class,()->home.overview(Map.of("teamUsername","outside")));
        assertThrows(BusinessException.class,()->home.details("DEPOSIT",Map.of("teamUsername","alp")));
    }
    @Test void dashboardUsesBusinessTodayWithIndependentThirtyDayTrend(){
        deposit(1,"USDT","10","2026-03-07 12:00:00");
        deposit(1,"USDT","20","2026-03-08 12:00:00");
        deposit(4,"USDT","30","2026-03-09 03:59:59");
        deposit(4,"USDT","999","2026-03-09 04:00:00");
        var report=service.dashboardReportAt(Instant.parse("2026-03-09T03:30:00Z"));
        assertEquals("2026-03-08",report.get("startDate"));assertEquals("2026-03-08",report.get("endDate"));
        assertEquals("2026-02-07",report.get("trendStartDate"));assertEquals("2026-03-08",report.get("trendEndDate"));
        assertEquals("America/New_York",report.get("timeZone"));
        Map<?,?> current=(Map<?,?>)report.get("current");
        assertDecimal("50",current.get("depositAmount"));assertEquals(2L,current.get("depositMembers"));
        assertEquals(1L,current.get("firstDepositMembers"));assertEquals(2L,current.get("newMembers"));
        List<?> trend=(List<?>)report.get("trend");assertEquals(30,trend.size());
        assertDecimal("10",((Map<?,?>)trend.get(28)).get("depositAmount"));
        assertFalse(report.containsKey("previous"));
    }
    @Test void preciseGlobalRangeClipsBoundaryDaysIncludesEndSecondAndKeepsUniquePeople(){
        deposit(1,"USDT","999","2026-03-08 05:59:59");
        deposit(1,"USDT","10","2026-03-08 06:00:00");
        deposit(4,"USDT","20","2026-03-09 03:59:59");
        deposit(4,"USDC","30","2026-03-09 04:00:00");
        deposit(1,"USDT","40","2026-03-09 04:30:00.999999");
        deposit(1,"USDT","999","2026-03-09 04:30:01");
        deposit(2,"USDT","999","2026-03-09 04:00:00");
        deposit(3,"USDT","999","2026-03-09 04:00:00");
        jdbc.update("INSERT INTO compute_withdrawal(order_no,member_id,currency,amount,net_amount,fee,status,reviewed_at) VALUES('SECOND-END',1,'USDT',12,11,1,'APPROVED','2026-03-09 04:30:00.500000')");
        snapshots.capture("WITHDRAWAL",data.one("SELECT * FROM compute_withdrawal WHERE order_no='SECOND-END'"),LocalDateTime.parse("2026-03-09T04:30:00.500000"));
        var report=service.globalReportAt(Map.of("startTime","2026-03-08 01:00:00","endTime","2026-03-09 00:30:00"),Instant.now());
        assertEquals("2026-03-08 01:00:00",report.get("startTime"));assertEquals("2026-03-09 00:30:00",report.get("endTime"));
        Map<?,?> current=(Map<?,?>)report.get("current");
        assertDecimal("100",current.get("depositAmount"));assertDecimal("11",current.get("withdrawalAmount"));assertDecimal("89",current.get("netFlow"));
        assertEquals(5L,current.get("depositCount"));assertEquals(1L,current.get("withdrawalCount"));
        assertEquals(3L,current.get("depositMembers"));assertEquals(2L,current.get("firstDepositMembers"));assertEquals(2L,current.get("newMembers"));
        List<?> trend=(List<?>)report.get("trend");assertEquals(2,trend.size());
        assertDecimal("30",((Map<?,?>)trend.get(0)).get("depositAmount"));assertDecimal("70",((Map<?,?>)trend.get(1)).get("depositAmount"));
        assertEquals(1L,((Map<?,?>)trend.get(0)).get("firstDepositMembers"));assertEquals(1L,((Map<?,?>)trend.get(1)).get("firstDepositMembers"));
        assertEquals(2L,((Map<?,?>)trend.get(0)).get("depositMembers"));assertEquals(3L,((Map<?,?>)trend.get(1)).get("depositMembers"));
    }
    @Test void globalDefaultAndLegacyDatesRemainWholeBusinessDays(){
        var report=service.globalReportAt(Map.of(),Instant.parse("2026-03-09T03:30:00Z"));
        assertEquals("2026-02-07 00:00:00",report.get("startTime"));assertEquals("2026-03-08 23:59:59",report.get("endTime"));
        assertEquals(30,((List<?>)report.get("trend")).size());
        deposit(4,"USDT","8","2026-03-09 03:59:59.999999");
        deposit(4,"USDT","999","2026-03-09 04:00:00");
        var legacy=service.globalReportAt(Map.of("startDate","2026-03-08","endDate","2026-03-08"),Instant.now());
        assertDecimal("8",((Map<?,?>)legacy.get("current")).get("depositAmount"));
        assertEquals(service.dailyReport(Map.of("startDate","2026-03-08","endDate","2026-03-08")).get("current"),legacy.get("current"));
    }
    @Test void preciseGlobalRejectsInvalidSecondsMissingPairsOversizeAndAmbiguousTime(){
        for(var range:List.of(
                Map.of("startTime","2026-03-08 01:00:00"),
                Map.of("startTime","2026-03-08 01:00","endTime","2026-03-08 01:00:00"),
                Map.of("startTime","2026-03-08 01:00:01","endTime","2026-03-08 01:00:00"),
                Map.of("startTime","2026-02-30 01:00:00","endTime","2026-03-08 01:00:00"),
                Map.of("startTime","2026-01-01 00:00:00","endTime","2026-04-04 00:00:00"),
                Map.of("startTime","2026-03-08 02:30:00","endTime","2026-03-08 03:30:00"),
                Map.of("startTime","2026-11-01 01:30:00","endTime","2026-11-01 02:30:00"))) {
            var error=assertThrows(BusinessException.class,()->service.globalReportAt(range,Instant.now()));
            assertEquals("server.v36.analytics.timeRange",error.getMessage());
        }
        var sameSecond=service.globalReportAt(Map.of("startTime","2026-03-08 03:00:00","endTime","2026-03-08 03:00:00"),Instant.now());
        assertEquals(1,((List<?>)sameSecond.get("trend")).size());
    }
    private Map<String,Object> day(){return service.overviewAt(Map.of("startDate","2026-03-08","endDate","2026-03-08"),Instant.parse("2026-03-09T12:00:00Z"));}
    private Map<String,Object> deposit(long id,String currency,String amount,String time){
        String order="DP"+UUID.randomUUID().toString().substring(0,20);
        jdbc.update("INSERT INTO compute_deposit(order_no,member_id,currency,amount,status,reviewed_at) VALUES(?,?,?,?,'APPROVED',?)",order,id,currency,new BigDecimal(amount),java.sql.Timestamp.valueOf(time));
        var row=data.one("SELECT * FROM compute_deposit WHERE order_no=?",order);
        snapshots.capture("DEPOSIT",row,LocalDateTime.parse(time.replace(' ','T')));
        return row;
    }
    private static Map<String,Object> quote(String rate){return fields("usdRate",new BigDecimal(rate),"provider","TEST_CACHE","sourceAt","2026-03-08T00:00:00Z","refreshedAt","2026-03-08T00:01:00Z");}
    private static void assertDecimal(String expected,Object actual){assertEquals(0,new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())));}
}
