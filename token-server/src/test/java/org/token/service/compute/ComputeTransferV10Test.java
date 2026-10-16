package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import org.junit.jupiter.api.Tag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeTokenAmounts;
import org.token.util.PasswordUtils;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.*;

/** 真实服务逻辑配合隔离 H2 验证方向状态、报价和原币资金守恒，不连接部署数据库。 */
@Tag("regression")
class ComputeTransferV10Test {
    private static final String PAY_PASSWORD = "Pay123456";
    private static final String PAY_PASSWORD_HASH = new PasswordUtils().hashPassword(PAY_PASSWORD);
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private FixtureComputeData data;
    private ComputeBusinessService business;

    @BeforeEach
    void prepare() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:transfer_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        TokenTestSchema.create(jdbc, "compute_member", "system_setting", "compute_member_session", "compute_recovery_request", "compute_currency", "compute_wallet", "compute_ledger", "compute_request", "compute_model", "compute_model_price", "compute_level", "compute_product", "compute_order", "compute_order_settlement", "compute_deposit", "compute_fee_rule", "compute_transfer_rule", "compute_transfer", "compute_withdrawal", "compute_address", "compute_job", "compute_job_run");
        jdbc.update("INSERT INTO compute_currency(currency,network,enabled) VALUES('USDT','TRC20',1),('BTC','BITCOIN',1)");
        TokenTestSchema.apply(jdbc);
        jdbc.update("INSERT INTO compute_member(id,username,password_hash,invite_code,created_at,updated_at) VALUES(1,'member','unused','invite',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("UPDATE compute_member SET pay_password_hash=? WHERE id=1", PAY_PASSWORD_HASH);
        jdbc.update("INSERT INTO compute_wallet(member_id,wallet_type,currency,available,frozen,updated_at) VALUES(1,'EARNINGS','USDT',100,0,CURRENT_TIMESTAMP),(1,'EARNINGS','BTC',1,0,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO compute_transfer_rule(from_wallet,to_wallet,currency,enabled,min_amount,max_amount,rate,fixed_fee,minimum_fee,updated_at) VALUES('EARNINGS','COMPUTE','*',1,0,100000,0,0,0,CURRENT_TIMESTAMP),('COMPUTE','PROJECT','*',0,0,100000,0,0,0,CURRENT_TIMESTAMP)");
        data = new FixtureComputeData(jdbc, new ObjectMapper().findAndRegisterModules());
        business = new ComputeBusinessService(data);
    }

    @Test
    void optionsAndSubmitBothRejectDisabledDirectionsAndWalletChannels() {
        assertEquals(1, ((List<?>) business.transferOptions(1).get("directions")).size());
        jdbc.update("UPDATE compute_transfer_rule SET enabled=0");
        assertTrue(((List<?>) business.transferOptions(1).get("directions")).isEmpty());
        assertThrows(BusinessException.class, () -> transaction.execute(status -> business.transfer(1, request("disabled-rule", "10", "1"))));
        jdbc.update("UPDATE compute_transfer_rule SET enabled=1 WHERE from_wallet='EARNINGS'");
        jdbc.update("UPDATE compute_currency SET support_account_types='EARNINGS' WHERE currency='USDT'");
        assertTrue(((List<?>) business.transferOptions(1).get("directions")).isEmpty());
        assertThrows(BusinessException.class, () -> transaction.execute(status -> business.transfer(1, request("disabled-wallet", "10", "1"))));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger", Integer.class));
    }

    @Test
    void tokenTransferUsesFixedAccountingWalletDespiteDisplayCurrencySettings() {
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value) VALUES('summaryCurrency','BTC')");
        jdbc.update("INSERT INTO compute_exchange_rate(rate_type,currency,rate,provider,source_id,source_at,refreshed_at) VALUES('ASSET','BTC',2000,'COINGECKO','bitcoin',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        Map<String,Object> request = request("token-transfer", "40", "1");
        Map<String,Object> first = transaction.execute(status -> business.transfer(1, request));
        assertEquals("USDT", first.get("currency"));
        assertEquals(0, new BigDecimal(first.get("amount").toString()).compareTo(new BigDecimal("40")));
        assertEquals(0, jdbc.queryForObject("SELECT available FROM compute_wallet WHERE wallet_type='EARNINGS' AND currency='USDT'", BigDecimal.class).compareTo(new BigDecimal("60")));
        assertEquals(0, jdbc.queryForObject("SELECT available FROM compute_wallet WHERE wallet_type='COMPUTE' AND currency='USDT'", BigDecimal.class).compareTo(new BigDecimal("40")));
        assertEquals(0, jdbc.queryForObject("SELECT available FROM compute_wallet WHERE wallet_type='EARNINGS' AND currency='BTC'", BigDecimal.class).compareTo(BigDecimal.ONE));
        assertEquals(((Number) first.get("id")).longValue(), ((Number) transaction.execute(status -> business.transfer(1, request)).get("id")).longValue());
        jdbc.update("UPDATE compute_exchange_rate SET rate=2500 WHERE currency='BTC'");
        assertThrows(BusinessException.class, () -> transaction.execute(status -> business.transfer(1, request("stale-rate", "400", "2000"))));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger", Integer.class));
    }

    @Test
    void clientCannotSelectAnotherCurrencyOrInventAnExchangeRate() {
        var request = new java.util.HashMap<>(request("other-currency", "10", "1"));
        request.put("currency", "BTC");
        assertThrows(BusinessException.class, () -> transaction.execute(status -> business.transfer(1, request)));
        assertThrows(BusinessException.class, () -> transaction.execute(status -> business.transfer(1, request("fake-rate", "10", "0.01"))));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger", Integer.class));
        assertThrows(BusinessException.class, () -> ComputeTokenAmounts.rate(data, "BTC"));
        assertEquals(new BigDecimal("0.33333333"), ComputeTokenAmounts.toNative(BigDecimal.ONE, new BigDecimal("3")));
    }

    private Map<String,Object> request(String requestId, String amount, String rate) {
        return Map.of("requestId", requestId, "fromWallet", "EARNINGS", "toWallet", "COMPUTE", "amount", amount, "amountUnit", "TOKEN", "expectedAssetRate", rate, "payPassword", PAY_PASSWORD);
    }

    @Test void transferPasswordIsCheckedBeforeRequestOrLedgerWrites() {
        assertPasswordGuard(new java.util.LinkedHashMap<>(request("protected-transfer", "10", "1")),
                input -> business.transfer(1, input));
    }

    @Test void withdrawalPasswordIsCheckedBeforeRequestOrFreezingMoney() {
        var input = new java.util.LinkedHashMap<String, Object>(Map.of("requestId", "protected-withdrawal", "currency", "USDT", "network", "TRC20",
                "amount", "10", "address", "T" + "1".repeat(33), "expectedFee", "0", "expectedNetAmount", "10"));
        assertPasswordGuard(input, request -> business.withdraw(1, request));
    }

    @Test void addressPasswordIsCheckedBeforeSavingAnyAddress() {
        var input = new java.util.LinkedHashMap<String, Object>(Map.of("currency", "USDT", "network", "TRC20", "address", "T" + "1".repeat(33), "label", "测试地址"));
        assertPasswordGuard(input, request -> business.saveAddress(1, request));
    }

    private void assertPasswordGuard(Map<String, Object> input, java.util.function.Consumer<Map<String, Object>> operation) {
        var balances = jdbc.queryForList("SELECT id,member_id,wallet_type,currency,available,frozen FROM compute_wallet ORDER BY id");
        jdbc.update("UPDATE compute_member SET pay_password_hash=NULL WHERE id=1");
        input.put("payPassword", PAY_PASSWORD);
        // 故意不包回滚事务：必须先校验凭据，不能依靠测试事务撤销已发生的写入。
        assertEquals("server.compute.v13.payPasswordRequired", assertThrows(BusinessException.class, () -> operation.accept(input)).getMessage());
        jdbc.update("UPDATE compute_member SET pay_password_hash=? WHERE id=1", PAY_PASSWORD_HASH);
        input.put("payPassword", "Wrong123456");
        assertEquals("server.compute.payPasswordInvalid", assertThrows(BusinessException.class, () -> operation.accept(input)).getMessage());
        input.remove("payPassword");
        assertEquals("server.compute.payPasswordInvalid", assertThrows(BusinessException.class, () -> operation.accept(input)).getMessage());
        for (String table : List.of("compute_request", "compute_transfer", "compute_withdrawal", "compute_address", "compute_ledger"))
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table + "不能在支付密码校验失败时写入");
        assertEquals(balances, jdbc.queryForList("SELECT id,member_id,wallet_type,currency,available,frozen FROM compute_wallet ORDER BY id"));
    }
}
