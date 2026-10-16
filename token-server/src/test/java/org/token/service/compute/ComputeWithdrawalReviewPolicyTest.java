package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComputeWithdrawalReviewPolicyTest {
    JdbcTemplate jdbc;
    ComputeWithdrawalReviewPolicy policy;
    @BeforeEach void setup() {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:review_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(source);
        org.token.service.compute.TokenTestSchema.create(jdbc,"system_setting");
        jdbc.execute("CREATE TABLE compute_currency_asset(asset_code VARCHAR(20),asset_type VARCHAR(20))");
        jdbc.execute("CREATE TABLE compute_exchange_rate(rate_type VARCHAR(20),currency VARCHAR(20),rate DECIMAL(30,12))");
        policy=new ComputeWithdrawalReviewPolicy(new FixtureComputeData(jdbc,new ObjectMapper()));
    }
    private Map<String,Object> decision(String currency,String amount) { return policy.decision(currency,new BigDecimal(amount),true,false); }
    @Test void defaultKeepsAllWithdrawalsForManualReviewAndConfigIsReadFresh() {
        assertEquals(true,decision("USDT","20").get("required"));
        policy.save(Map.of("mode","THRESHOLD","thresholdToken","100"));
        assertEquals(false,decision("USDT","20").get("required"));
        policy.save(Map.of("mode","ALL","thresholdToken","100"));
        assertEquals(true,decision("USDT","20").get("required"));
    }
    @Test void exactThresholdAndGrossAmountAreReviewedButLowerValueMayQueue() {
        policy.save(Map.of("mode","THRESHOLD","thresholdToken","100"));
        assertEquals(false,decision("USDT","99.99999999").get("required"));
        assertEquals(true,decision("USDT","100").get("required"));
        assertEquals(true,decision("USDT","100.00000001").get("required"));
    }
    @Test void conversionIsSnapshottedAndMissingRateCannotAutoPay() {
        policy.save(Map.of("mode","THRESHOLD","thresholdToken","100"));
        jdbc.update("INSERT INTO compute_currency_asset VALUES('BTC','CRYPTO'),('CNY','FIAT')");
        jdbc.update("INSERT INTO compute_exchange_rate VALUES('ASSET','BTC',50000),('FIAT','CNY',7)");
        assertEquals(true,decision("BTC","0.002").get("required"));
        var below=decision("BTC","0.001");assertEquals(false,below.get("required"));assertEquals("50.00000000",below.get("amountToken"));
        assertEquals(true,decision("CNY","700").get("required"));
        assertEquals("RATE_UNAVAILABLE",decision("UNKNOWN","1").get("reason"));
    }
    @Test void testAndManualChannelsNeverQueueAndMalformedSettingsAreRejected() {
        policy.save(Map.of("mode","THRESHOLD","thresholdToken","100"));
        assertEquals(true,policy.decision("USDT",BigDecimal.ONE,true,true).get("required"));
        assertEquals(true,policy.decision("USDT",BigDecimal.ONE,false,false).get("required"));
        for(String value:List.of("0","-1","0.000000001","NaN","1000000000001"))
            assertThrows(BusinessException.class,()->policy.save(Map.of("mode","THRESHOLD","thresholdToken",value)));
    }
    @Test void versionTwoUsesConfirmedGrossUsdWithoutConsultingCurrentRate() {
        policy.save(Map.of("mode","THRESHOLD","thresholdToken","2"));
        var decision=policy.decision("TRX",new BigDecimal("20"),true,false,new BigDecimal("0.1"),new BigDecimal("2"));
        assertEquals(true,decision.get("required"));
        assertEquals("THRESHOLD",decision.get("reason"));
        policy.save(Map.of("mode","THRESHOLD","thresholdToken","2.00000001"));
        assertEquals(false,policy.decision("TRX",new BigDecimal("20"),true,false,new BigDecimal("0.1"),new BigDecimal("2")).get("required"));
        assertThrows(BusinessException.class,()->policy.decision("TRX",new BigDecimal("20"),true,false,new BigDecimal("0.1"),new BigDecimal("20")));
    }
}
