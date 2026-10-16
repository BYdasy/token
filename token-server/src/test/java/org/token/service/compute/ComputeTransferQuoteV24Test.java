package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.util.PasswordUtils;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.token.repository.compute.ComputeData.*;
import static org.junit.jupiter.api.Assertions.*;

/** 隔离内存库验证划转预览与真实记账一致，不访问支付网关或部署数据库。 */
@Tag("regression")
class ComputeTransferQuoteV24Test {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ComputeBusinessService business;

    @BeforeEach void setup() throws Exception {
        var source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:transfer_quote_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        TokenTestSchema.create(jdbc, "compute_member", "system_setting", "compute_member_session", "compute_recovery_request", "compute_currency", "compute_wallet", "compute_ledger", "compute_request", "compute_model", "compute_model_price", "compute_level", "compute_product", "compute_order", "compute_order_settlement", "compute_deposit", "compute_fee_rule", "compute_transfer_rule", "compute_transfer", "compute_withdrawal", "compute_address", "compute_job", "compute_job_run");
        jdbc.update("INSERT INTO compute_currency(currency,network,enabled) VALUES('USDT','TRC20',1)");
        TokenTestSchema.apply(jdbc);
        jdbc.update("INSERT INTO compute_member(id,username,password_hash,pay_password_hash,invite_code,created_at,updated_at) VALUES(1,'member','unused',?,'invite',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",new PasswordUtils().hashPassword("Pay123456"));
        jdbc.update("INSERT INTO compute_wallet(member_id,wallet_type,currency,available,frozen,updated_at) VALUES(1,'EARNINGS','USDT',100,0,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO compute_transfer_rule(from_wallet,to_wallet,currency,enabled,min_amount,max_amount,rate,fixed_fee,minimum_fee,updated_at) VALUES('EARNINGS','COMPUTE','*',1,0,1000,2.5,0.1,1,CURRENT_TIMESTAMP)");
        business=new ComputeBusinessService(new FixtureComputeData(jdbc,new ObjectMapper().findAndRegisterModules()));
    }

    private Map<String,Object> request(String amount) {
        return fields("fromWallet","EARNINGS","toWallet","COMPUTE","amount",amount,"amountUnit","TOKEN","expectedAssetRate","1");
    }
    private Map<String,Object> submission(String id,String amount,Map<String,Object> quote) {
        var request=request(amount);
        request.putAll(fields("requestId",id,"payPassword","Pay123456","expectedFee",quote.get("fee"),"expectedNetAmount",quote.get("netAmount")));
        return request;
    }
    private void moneyEquals(String expected,Object actual){assertEquals(0,new BigDecimal(expected).compareTo(decimal(actual)));}

    @Test void previewAppliesMinimumFixedAndPercentageFeesWithoutMutatingFundsOrRequests() {
        var first=business.transferQuote(1,request("20"));
        moneyEquals("1",first.get("fee"));moneyEquals("19",first.get("netAmount"));
        var next=business.transferQuote(1,request("80"));
        moneyEquals("2.1",next.get("fee"));moneyEquals("77.9",next.get("netAmount"));
        assertFalse(first.containsKey("rule"));
        for(String table:List.of("compute_request","compute_transfer","compute_ledger"))
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
        moneyEquals("100",jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=1",BigDecimal.class));
    }

    @Test void commitMatchesPreviewAndRetryKeepsOriginalResultAfterConfigurationChanges() {
        var quote=business.transferQuote(1,request("80"));
        var request=submission("transfer-v24-retry","80",quote);
        var committed=tx.execute(status->business.transfer(1,request));
        moneyEquals("2.1",committed.get("fee"));moneyEquals("77.9",committed.get("netAmount"));
        moneyEquals("20",jdbc.queryForObject("SELECT available FROM compute_wallet WHERE wallet_type='EARNINGS'",BigDecimal.class));
        moneyEquals("77.9",jdbc.queryForObject("SELECT available FROM compute_wallet WHERE wallet_type='COMPUTE'",BigDecimal.class));
        jdbc.update("UPDATE compute_transfer_rule SET rate=10");
        var replay=tx.execute(status->business.transfer(1,request));
        assertEquals(number(committed.get("id")),number(replay.get("id")));
        moneyEquals("2.1",replay.get("fee"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger",Integer.class));
    }

    @Test void changedQuoteRollsBackAndCannotDebitBeforeUserAcceptsNewServiceFee() {
        var quote=business.transferQuote(1,request("80"));
        var request=submission("transfer-v24-stale","80",quote);
        jdbc.update("UPDATE compute_transfer_rule SET rate=10");
        assertEquals("server.compute.quoteChanged",assertThrows(BusinessException.class,()->tx.execute(status->business.transfer(1,request))).getMessage());
        moneyEquals("100",jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=1",BigDecimal.class));
        for(String table:List.of("compute_request","compute_transfer","compute_ledger"))
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
    }

    @Test void quoteAndCommitSelectCurrencySpecificRuleAndRejectUnavailableDirection() {
        jdbc.update("INSERT INTO compute_transfer_rule(from_wallet,to_wallet,currency,enabled,min_amount,max_amount,rate,fixed_fee,minimum_fee,updated_at) VALUES('EARNINGS','COMPUTE','USDT',1,0,1000,0,0,0,CURRENT_TIMESTAMP)");
        var quote=business.transferQuote(1,request("20"));
        moneyEquals("0",quote.get("fee"));
        var result=tx.execute(status->business.transfer(1,submission("transfer-v24-exact","20",quote)));
        moneyEquals("20",result.get("netAmount"));
        jdbc.update("UPDATE compute_transfer_rule SET enabled=0");
        assertEquals("server.compute.transferRuleMissing",assertThrows(BusinessException.class,()->business.transferQuote(1,request("20"))).getMessage());
    }

    @Test void quoteRejectsDisabledWalletAndCannotSelectNonUsdWallet() {
        var other=request("20");other.put("currency","BTC");
        assertEquals("server.compute.unsupportedCurrency",assertThrows(BusinessException.class,()->business.transferQuote(1,other)).getMessage());
        jdbc.update("UPDATE compute_currency SET support_account_types='EARNINGS'");
        assertEquals("server.compute.transferRuleMissing",assertThrows(BusinessException.class,()->business.transferQuote(1,request("20"))).getMessage());
    }
}
