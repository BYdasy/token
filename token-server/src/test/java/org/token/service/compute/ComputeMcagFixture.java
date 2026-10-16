package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import org.junit.jupiter.api.Tag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.config.compute.ComputeMcagProperties;
import org.token.repository.compute.ComputeData;
import org.token.util.PasswordUtils;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static org.token.repository.compute.ComputeData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 隔离数据库验证真实资金事务；不连接商户 MySQL，不运行真实链上交易。 */
class ComputeMcagFixture {
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    FixtureComputeData data;
    ComputeMcagDepositService deposits;
    ComputeBusinessService business;
    ComputeSystemMessageService messages;
    ComputeMcagProperties config;
    void setup() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:mcag_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000");
        jdbc = new JdbcTemplate(source); tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP FOR 'org.token.service.compute.ComputeWorkflowIntegrationTest.utcTimestamp'");
        TokenTestSchema.create(jdbc, "compute_member", "system_setting", "compute_member_session", "compute_recovery_request", "compute_currency", "compute_wallet", "compute_ledger", "compute_request", "compute_model", "compute_model_price", "compute_level", "compute_product", "compute_order", "compute_order_settlement", "compute_deposit", "compute_fee_rule", "compute_transfer_rule", "compute_transfer", "compute_withdrawal", "compute_address", "compute_job", "compute_job_run", "compute_mcag_address", "compute_mcag_event", "compute_mcag_cursor");
        TokenTestSchema.apply(jdbc);
        ComputeMcagTestSchema.apply(jdbc);
        jdbc.update("INSERT INTO compute_member(id,username,password_hash,invite_code,is_test,created_at,updated_at) VALUES(1,'mcag_test','unused','mcag_invite',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(2,'mcag_other','unused','mcag_other_invite',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO compute_currency(currency,network,enabled) VALUES('USDT','TRC20',1)");
        TokenTestSchema.seedCurrencies(jdbc);
        ComputeMcagTestSchema.seedMapping(jdbc,"USDT","TRC20","USDT_TRON","TRON",8);
        data = new FixtureComputeData(jdbc, new ObjectMapper().findAndRegisterModules());
        config = new ComputeMcagProperties(); config.setEnabled(true); config.setMerchantId("mch_token"); config.setEnvironment("LIVE");
        var channel = new ComputeMcagProperties.Channel(); channel.setCurrency("USDT"); channel.setNetwork("TRC20"); channel.setAssetId("USDT_TRON"); channel.setNetworkId("TRON");
        config.getChannels().put("usdt-tron", channel);
        messages = mock(ComputeSystemMessageService.class);
        business = new ComputeBusinessService(data, new ComputeCurrencyService(data), new PasswordUtils(), messages, null, null, config);
        deposits = new ComputeMcagDepositService(data, config);
        bind(1);
    }
    private void bind(long member) {
        tx.executeWithoutResult(status -> deposits.bind(member, "COMPUTE", config.getChannels().get("usdt-tron"), fields(
                "merchant_id","mch_token","environment","LIVE","customer_id",ComputeMcagDepositService.customer(member,"COMPUTE"),
                "network_id","TRON","address_id","addr_"+member,"address","T"+member+"a".repeat(32),"purpose","CUSTOMER_DEPOSIT","status","ACTIVE","monitoring_ready",true)));
    }
}
