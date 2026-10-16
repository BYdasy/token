package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.service.SystemSettingService;
import org.token.repository.compute.ComputeData;
import org.token.util.PasswordUtils;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComputeBusinessTimezoneTest {
    @Test void dashboardCountsPostedIncomeWithinSystemDaysWithoutTreatingVipBasisAsInvestedPrincipal() {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:business_zone_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc=new JdbcTemplate(source);
        for(String table:List.of("compute_order","compute_auto_contract"))jdbc.execute("CREATE TABLE "+table+"(id BIGINT,member_id BIGINT,currency VARCHAR(16),status VARCHAR(20),amount DECIMAL(20,8),paid_income DECIMAL(20,8))");
        jdbc.execute("CREATE TABLE compute_wallet(id BIGINT,member_id BIGINT,wallet_type VARCHAR(16),currency VARCHAR(16),available DECIMAL(20,8),frozen DECIMAL(20,8))");
        jdbc.execute("CREATE TABLE compute_order_settlement(member_id BIGINT,order_id BIGINT,created_at TIMESTAMP,income DECIMAL(20,8))");
        jdbc.execute("CREATE TABLE compute_auto_settlement(member_id BIGINT,contract_id BIGINT,created_at TIMESTAMP,income DECIMAL(20,8))");
        jdbc.update("INSERT INTO compute_order VALUES(1,1,'USDT','ACTIVE',100,5),(2,1,'BTC','ACTIVE',200,8),(3,2,'USDT','ACTIVE',300,9)");
        jdbc.update("INSERT INTO compute_auto_contract VALUES(1,1,'USDT','COMPLETED',50,4),(2,1,'USDT','COMPLETED',900,0)");
        ZoneId zone=ZoneId.of("Pacific/Kiritimati");LocalDate today=LocalDate.now(zone);
        LocalDateTime boundary=LocalDateTime.ofInstant(today.atStartOfDay(zone).toInstant(),ZoneOffset.UTC);
        jdbc.update("INSERT INTO compute_order_settlement VALUES(1,1,?,2)",boundary.minusNanos(1000));
        jdbc.update("INSERT INTO compute_order_settlement VALUES(1,1,?,3)",boundary);
        jdbc.update("INSERT INTO compute_order_settlement VALUES(1,2,?,800)",boundary);
        jdbc.update("INSERT INTO compute_order_settlement VALUES(2,3,?,900)",boundary);
        jdbc.update("INSERT INTO compute_auto_settlement VALUES(1,1,?,4)",boundary.plusHours(1));
        var data=new FixtureComputeData(jdbc,new ObjectMapper());
        var currencies=mock(ComputeCurrencyService.class);when(currencies.defaultCurrency()).thenReturn("USDT");
        var settings=mock(SystemSettingService.class);when(settings.resolveSystemTimeZone()).thenReturn(zone.getId());
        var service=new ComputeBusinessService(data,currencies,new PasswordUtils(),null,settings);
        Map<String,Object> result=service.dashboard(1);
        assertEquals(zone.getId(),result.get("incomeTimeZone"));
        assertEquals(0,new BigDecimal("7").compareTo(new BigDecimal(result.get("todayIncome").toString())));
        assertEquals(0,new BigDecimal("100").compareTo(new BigDecimal(result.get("activePrincipal").toString())));
        @SuppressWarnings("unchecked") var trend=(List<Map<String,Object>>)result.get("incomeTrend");
        assertEquals(7,trend.size());
        assertEquals(today.toString(),trend.get(6).get("day"));
        assertEquals(0,new BigDecimal("2").compareTo(new BigDecimal(trend.get(5).get("income").toString())));
        assertEquals(0,new BigDecimal("7").compareTo(new BigDecimal(trend.get(6).get("income").toString())));
    }
}
