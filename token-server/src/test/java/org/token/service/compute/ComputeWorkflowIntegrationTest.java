package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import org.junit.jupiter.api.Tag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.service.compute.ComputeAccountService;
import org.token.model.request.compute.ComputeAuthRequests;
import org.token.service.compute.ComputeBusinessService;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.token.exception.BusinessException;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.token.repository.compute.ComputeData.fields;

/** 隔离内存数据库验证资金闭环，不连接用户的 MySQL，不加载 Spring 启动任务。 */
@Tag("regression")
public class ComputeWorkflowIntegrationTest {
    private static final String PAY_PASSWORD = "Pay123456";
    private static final String PAY_PASSWORD_HASH = new PasswordUtils().hashPassword(PAY_PASSWORD);
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private ComputeBusinessService business;
    private ComputeAccountService accounts;
    private long memberId;

    private void preparePaymentPassword() {
        jdbc.update("UPDATE compute_member SET pay_password_hash=? WHERE id=?", PAY_PASSWORD_HASH, memberId);
    }

    private long boundAddressId() {
        var ids=jdbc.queryForList("SELECT id FROM compute_address WHERE member_id=? AND currency='USDT' AND network='TRC20'",Long.class,memberId);
        if(!ids.isEmpty())return ids.get(0);
        jdbc.update("INSERT INTO compute_address(member_id,label,currency,network,address,created_at) VALUES(?,'已绑定提款地址','USDT','TRC20',?,CURRENT_TIMESTAMP)",memberId,"T"+"1".repeat(33));
        return jdbc.queryForObject("SELECT id FROM compute_address WHERE member_id=? AND currency='USDT' AND network='TRC20'",Long.class,memberId);
    }

    public static LocalDateTime utcTimestamp(int precision) { return LocalDateTime.now(ZoneOffset.UTC); }

    @BeforeEach
    void prepareIsolatedDatabase() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:compute_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP FOR 'org.token.service.compute.ComputeWorkflowIntegrationTest.utcTimestamp'");
        TokenTestSchema.create(jdbc, "compute_member", "system_setting", "compute_member_session", "compute_recovery_request", "compute_currency", "compute_wallet", "compute_ledger", "compute_request", "compute_model", "compute_model_price", "compute_level", "compute_product", "compute_order", "compute_order_settlement", "compute_deposit", "compute_fee_rule", "compute_transfer_rule", "compute_transfer", "compute_withdrawal", "compute_address", "compute_job", "compute_job_run");





        jdbc.update("INSERT INTO system_setting(group_name,setting_key,setting_value) VALUES('compute','teamSupportedDepth','3')");
        jdbc.update("INSERT INTO compute_currency(currency,network,enabled) VALUES('USDT','TRC20',1)");
        jdbc.update("INSERT INTO compute_level(auto_provider_ids,id,name,threshold,enabled) VALUES('[]',1,'基础',0,1)");
        TokenTestSchema.create(jdbc,"compute_provider");
        jdbc.update("INSERT INTO compute_provider(id,code,name,enabled) VALUES(1,'TEST','测试供应方',1)");
        jdbc.update("INSERT INTO compute_model(provider_id,name,input_price,output_price,volatility,enabled,updated_at) VALUES(1,'模型',1,2,5,1,UTC_TIMESTAMP(6))");
        for (String mode : List.of("A", "B", "C", "D")) {
            jdbc.update("""
                    INSERT INTO compute_product(name,type,currency,min_amount,max_amount,duration_days,daily_rate_min,daily_rate_max,
                    settlement_mode,enabled,description,updated_at) VALUES(?,?,'USDT',1,10000,2,0.1,0.1,?,1,'验证四模式',UTC_TIMESTAMP(6))
                    """, "模式" + mode, "GPU", mode);
        }
        jdbc.update("""
                INSERT INTO compute_fee_rule(name,enabled,priority,currency,network,min_amount,rate,fixed_fee,minimum_fee,free_count,period,updated_at)
                VALUES('默认',1,0,'USDT','TRC20',0,1,0,0,0,'MONTH',UTC_TIMESTAMP(6))
                """);
        jdbc.update("""
                INSERT INTO compute_transfer_rule(from_wallet,to_wallet,currency,enabled,min_amount,max_amount,rate,fixed_fee,minimum_fee,updated_at)
                VALUES('EARNINGS','COMPUTE','USDT',1,1,10000,1,0,0,UTC_TIMESTAMP(6))
                """);
        TokenTestSchema.apply(jdbc);
        // 当前提款仍执行真实本地地址保护；复用生产73号结构的隔离夹具，不跳过归属守卫。
        ComputeMcagTestSchema.apply(jdbc);
        jdbc.update("UPDATE compute_product SET daily_rate_min=0.1,daily_rate_max=0.1");
        business = new ComputeBusinessService(new FixtureComputeData(jdbc, new ObjectMapper().findAndRegisterModules()));
        ComputeAgentScope scope = mock(ComputeAgentScope.class);
        when(scope.queryScope()).thenReturn(new org.token.model.query.MemberScope(true,List.of()));
        when(scope.current()).thenReturn(new ComputeAgentScope.Scope(true,List.of(),true));
        when(scope.relations()).thenReturn(new ComputeMemberRelations(jdbc));
        when(scope.resolveInviter(null)).thenReturn(null);
        accounts = new ComputeAccountService(jdbc, new PasswordUtils(), business, scope);
        Map<String, Object> registered = transaction.execute(status -> accounts.register(
                new ComputeAuthRequests.Register("tester", "tester@example.invalid", null, "TestPass987!", null)));
        memberId = ((Number) ((Map<?, ?>) registered.get("member")).get("id")).longValue();
    }

    private Map<String,Object> quotedOrderRequest(String requestId,long productId,String amount) {
        var quotes=new ComputeProductQuoteService(new FixtureComputeData(jdbc,new ObjectMapper().findAndRegisterModules()));
        var quote=transaction.execute(status->quotes.quote(memberId,Map.of("productId",productId,"amount",amount)));
        return Map.of("requestId",requestId,"productId",productId,"quoteId",quote.get("quoteId"));
    }

    @Test
    void payPasswordRequiresLoginAndPreviousPaymentCredentials() {
        assertEquals(false, accounts.member(memberId).get("hasPayPassword"));
        // 空白旧数据也属于未设置，首次设置不能要求不存在的原支付密码。
        jdbc.update("UPDATE compute_member SET pay_password_hash=' ' WHERE id=?", memberId);
        assertEquals(false, accounts.member(memberId).get("hasPayPassword"));
        assertThrows(BusinessException.class, () -> transaction.executeWithoutResult(status -> accounts.changePayPassword(memberId,
                Map.of("loginPassword", "wrong", "newPayPassword", "123456"))));
        transaction.executeWithoutResult(status -> accounts.changePayPassword(memberId, Map.of("loginPassword", "TestPass987!", "newPayPassword", "123456")));
        assertEquals(true, accounts.member(memberId).get("hasPayPassword"));
        assertThrows(BusinessException.class, () -> transaction.executeWithoutResult(status -> accounts.changePayPassword(memberId,
                Map.of("loginPassword", "TestPass987!", "newPayPassword", "654321"))));
        transaction.executeWithoutResult(status -> accounts.changePayPassword(memberId, Map.of("loginPassword", "TestPass987!", "oldPayPassword", "123456", "newPayPassword", "654321")));
        String hash = jdbc.queryForObject("SELECT pay_password_hash FROM compute_member WHERE id=?", String.class, memberId);
        assertTrue(new PasswordUtils().matchesPassword("654321", hash)); assertFalse(accounts.member(memberId).containsKey("payPasswordHash"));
        assertThrows(BusinessException.class, () -> transaction.executeWithoutResult(status -> accounts.changePayPassword(memberId,
                Map.of("loginPassword", "TestPass987!", "oldPayPassword", "654321", "newPayPassword", "12345"))));
    }

    @Test
    void fourSettlementModesAndWithdrawalReviewPreserveBalances() {
        preparePaymentPassword();
        for (String wallet : List.of("COMPUTE", "PROJECT")) {
            Map<String, Object> deposit = transaction.execute(status -> business.deposit(memberId,
                    Map.of("requestId", "deposit-" + wallet, "walletType", wallet, "currency", "USDT", "network", "TRC20", "amount", "1000")));
            long id = ((Number) deposit.get("id")).longValue();
            transaction.executeWithoutResult(status -> business.reviewDeposit(id, true, "已核验", 1));
            assertThrows(BusinessException.class, () -> transaction.execute(status -> business.reviewDeposit(id, true, "重复审核", 1)));
        }
        for (long product = 1; product <= 4; product++) {
            long productId = product;
            var request = quotedOrderRequest("order-mode-" + product, product, "100");
            Map<String, Object> order = transaction.execute(status -> business.buyOrder(memberId, request));
            long id = ((Number) order.get("id")).longValue();
            assertEquals(id, ((Number) transaction.execute(status -> business.buyOrder(memberId, request)).get("id")).longValue());
            LocalDateTime starts = LocalDateTime.ofInstant(Instant.parse(order.get("startsAt").toString()), ZoneOffset.UTC);
            transaction.executeWithoutResult(status -> business.settleOrder(id, starts.plusDays(1)));
            BigDecimal dayOne = jdbc.queryForObject("SELECT paid_income FROM compute_order WHERE id=?", BigDecimal.class, id);
            assertEquals(0, dayOne.compareTo(productId < 3 ? BigDecimal.ZERO : new BigDecimal("0.1")));
            transaction.executeWithoutResult(status -> business.settleOrder(id, starts.plusDays(2)));
            Integer repeated = transaction.execute(status -> business.settleOrder(id, starts.plusDays(3)));
            assertEquals(Integer.valueOf(0), repeated);
        }
        assertBalance("COMPUTE", "1000", "0");
        assertBalance("PROJECT", "600", "0");
        assertBalance("EARNINGS", "200.8", "0");
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM compute_order WHERE status='COMPLETED'", Integer.class));

        Map<String, Object> withdrawal = transaction.execute(status -> business.withdraw(memberId, fields(
                "requestId", "withdraw-reject", "currency", "USDT", "network", "TRC20", "amount", "100", "address", "T" + "1".repeat(33), "addressId", boundAddressId(), "expectedFee", "1", "expectedNetAmount", "99", "expectedAccountingRate","1","expectedAccountingAmount","100","payPassword", PAY_PASSWORD)));
        assertEquals(0, new BigDecimal(withdrawal.get("fee").toString()).compareTo(BigDecimal.ONE));
        assertBalance("EARNINGS", "100.8", "100");
        long withdrawalId = ((Number) withdrawal.get("id")).longValue();
        transaction.executeWithoutResult(status -> business.reviewWithdrawal(withdrawalId, false, "退回", 1));
        assertBalance("EARNINGS", "200.8", "0");
        assertThrows(BusinessException.class, () -> transaction.execute(status -> business.reviewWithdrawal(withdrawalId, true, "重复", 1)));
        var second = transaction.execute(status -> business.withdraw(memberId, fields(
                "requestId", "withdraw-approve", "currency", "USDT", "network", "TRC20", "amount", "100", "address", "T" + "1".repeat(33), "addressId", boundAddressId(), "expectedFee", "1", "expectedNetAmount", "99", "expectedAccountingRate","1","expectedAccountingAmount","100","payPassword", PAY_PASSWORD)));
        transaction.executeWithoutResult(status -> business.reviewWithdrawal(((Number) second.get("id")).longValue(), true, "已付款", 1));
        assertBalance("EARNINGS", "100.8", "0");
        transaction.executeWithoutResult(status -> business.transfer(memberId, Map.of("requestId", "transfer-earnings", "fromWallet", "EARNINGS", "toWallet", "COMPUTE", "currency", "USDT", "amount", "100", "payPassword", PAY_PASSWORD)));
        assertBalance("EARNINGS", "0.8", "0");
        assertBalance("COMPUTE", "1099", "0");
    }

    @Test
    void failedAndConcurrentRequestsCannotCreateMoneyOrOverspend() throws Exception {
        assertThrows(BusinessException.class, () -> transaction.execute(status -> business.deposit(memberId, Map.of(
                "requestId", "earnings-deposit", "walletType", "EARNINGS", "currency", "USDT", "network", "TRC20", "amount", "100"))));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_request", Integer.class));
        var deposit = transaction.execute(status -> business.deposit(memberId, Map.of("requestId", "initial-deposit", "walletType", "PROJECT", "currency", "USDT", "network", "TRC20", "amount", "100")));
        transaction.executeWithoutResult(status -> business.reviewDeposit(((Number) deposit.get("id")).longValue(), true, "核验", 1));
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String requestId = "concurrent-" + i;
                futures.add(executor.submit(() -> {
                    ready.countDown(); start.await(5, TimeUnit.SECONDS);
                    try { transaction.execute(status -> business.buyOrder(memberId, quotedOrderRequest(requestId, 1, "80"))); return true; }
                    catch (BusinessException expected) { return false; }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            int successes = 0;
            for (var future : futures) if (future.get(15, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        } finally { executor.shutdownNow(); }
        assertBalance("PROJECT", "20", "0");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM compute_order", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM compute_request", Integer.class));
    }

    @Test
    void changedWithdrawalQuoteRequiresNewConsentAndSuccessfulReplayKeepsOriginalFee() {
        preparePaymentPassword();
        var deposit = transaction.execute(status -> business.deposit(memberId, Map.of("requestId", "quote-deposit", "walletType", "PROJECT", "currency", "USDT", "network", "TRC20", "amount", "100")));
        transaction.executeWithoutResult(status -> business.reviewDeposit(((Number) deposit.get("id")).longValue(), true, "核验", 1));
        var order = transaction.execute(status -> business.buyOrder(memberId, quotedOrderRequest("quote-order", 1, "100")));
        LocalDateTime starts = LocalDateTime.ofInstant(Instant.parse(order.get("startsAt").toString()), ZoneOffset.UTC);
        transaction.executeWithoutResult(status -> business.settleOrder(((Number) order.get("id")).longValue(), starts.plusDays(2)));
        assertBalance("EARNINGS", "100.2", "0");
        var noQuote = Map.<String, Object>of("requestId", "quote-withdraw", "currency", "USDT", "network", "TRC20", "amount", "100", "address", "T" + "1".repeat(33), "addressId", boundAddressId(), "payPassword", PAY_PASSWORD);
        BusinessException missing = assertThrows(BusinessException.class, () -> transaction.execute(status -> business.withdraw(memberId, noQuote)));
        assertEquals("server.compute.quoteRequired", missing.getMessage());
        Map<String, Object> confirmed = new java.util.LinkedHashMap<>(noQuote);
        var originalQuote = business.quote(memberId, Map.of("currency", "USDT", "network", "TRC20", "amount", "100"));
        confirmed.put("expectedFee", originalQuote.get("fee")); confirmed.put("expectedNetAmount", originalQuote.get("netAmount"));
        confirmed.put("expectedAccountingRate",originalQuote.get("accountingRate"));confirmed.put("expectedAccountingAmount",originalQuote.get("accountingAmount"));
        jdbc.update("UPDATE compute_fee_rule SET rate=2");
        BusinessException changed = assertThrows(BusinessException.class, () -> transaction.execute(status -> business.withdraw(memberId, confirmed)));
        assertEquals("server.compute.quoteChanged", changed.getMessage());
        assertBalance("EARNINGS", "100.2", "0");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_withdrawal", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_request WHERE request_id='quote-withdraw'", Integer.class));
        var refreshedQuote = business.quote(memberId, Map.of("currency", "USDT", "network", "TRC20", "amount", "100"));
        confirmed.put("expectedFee", refreshedQuote.get("fee")); confirmed.put("expectedNetAmount", refreshedQuote.get("netAmount"));
        confirmed.put("expectedAccountingRate",refreshedQuote.get("accountingRate"));confirmed.put("expectedAccountingAmount",refreshedQuote.get("accountingAmount"));
        var accepted = transaction.execute(status -> business.withdraw(memberId, confirmed));
        assertEquals(0, new BigDecimal(accepted.get("fee").toString()).compareTo(new BigDecimal("2")));
        assertBalance("EARNINGS", "0.2", "100");
        jdbc.update("UPDATE compute_fee_rule SET rate=3");
        var replay = transaction.execute(status -> business.withdraw(memberId, confirmed));
        assertEquals(((Number) accepted.get("id")).longValue(), ((Number) replay.get("id")).longValue());
        assertEquals(0, new BigDecimal(replay.get("fee").toString()).compareTo(new BigDecimal("2")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM compute_withdrawal", Integer.class));
        assertBalance("EARNINGS", "0.2", "100");
    }

    @Test
    void monetarySummariesKeepDifferentCurrenciesSeparate() {
        jdbc.update("INSERT INTO compute_currency(currency,network,enabled) VALUES('USDC','ERC20',1)");
        TokenTestSchema.seedCurrencies(jdbc);
        jdbc.update("""
                INSERT INTO compute_product(name,type,currency,min_amount,max_amount,duration_days,daily_rate_min,daily_rate_max,
                settlement_mode,enabled,description,updated_at) VALUES('USDC产品','GPU','USDC',1,10000,2,0.1,0.1,'A',1,'币种隔离',UTC_TIMESTAMP(6))
                """);
        jdbc.update("UPDATE compute_product SET daily_rate_min=0.1,daily_rate_max=0.1 WHERE name='USDC产品'");
        var deposit = transaction.execute(status -> business.deposit(memberId, Map.of("requestId", "currency-USDT",
                "walletType", "PROJECT", "currency", "USDT", "network", "TRC20", "amount", "1000")));
        transaction.executeWithoutResult(status -> business.reviewDeposit(((Number) deposit.get("id")).longValue(), true, "核验", 1));
        transaction.executeWithoutResult(status -> business.buyOrder(memberId, quotedOrderRequest("buy-USDT", 1, "100")));
        // 新品只允许USD兼容币种；即使用户有历史USDC余额，也不能重新购买原币产品。
        transaction.executeWithoutResult(status -> business.changeWallet(memberId,"PROJECT","USDC",new BigDecimal("1000"),BigDecimal.ZERO,
                "ADMIN_SYSTEM","legacy-usdc-funding","历史原币资金夹具"));
        var rejected=assertThrows(BusinessException.class,()->transaction.execute(status -> business.buyOrder(memberId,
                quotedOrderRequest("buy-USDC", 5, "1000"))));
        assertEquals("server.compute.unsupportedCurrency",rejected.getMessage());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_order WHERE currency='USDC'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_request WHERE request_id='buy-USDC'",Integer.class));
        assertEquals(0,new BigDecimal("1000").compareTo(jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=? AND wallet_type='PROJECT' AND currency='USDC'",BigDecimal.class,memberId)));
        assertBalance("PROJECT","900","0");

        // 直接保存升级前已经存在的原币合同与扣款，验证已有义务仍按原快照履约。
        var data=new FixtureComputeData(jdbc,new ObjectMapper().findAndRegisterModules());
        LocalDateTime starts=LocalDateTime.of(2026,9,10,0,0);
        long legacyOrder=transaction.execute(status->{
            long id=data.insert("compute_order",fields("member_id",memberId,"product_id",5,"product_name","历史USDC产品","product_type","GPU",
                    "source_wallet","PROJECT","currency","USDC","amount",new BigDecimal("1000"),"daily_rate",new BigDecimal("0.1"),
                    "duration_days",2,"settlement_mode","A","product_snapshot","{}",
                    "expected_income",new BigDecimal("2"),"paid_income",BigDecimal.ZERO,"settled_days",0,"status","ACTIVE",
                    "starts_at",starts,"matures_at",starts.plusDays(2),"created_at",starts));
            business.changeWallet(memberId,"PROJECT","USDC",new BigDecimal("-1000"),BigDecimal.ZERO,"ORDER_BUY","ORDER:"+id,String.valueOf(id));
            return id;
        });
        var summary = business.dashboard(memberId);
        assertEquals("USDT", summary.get("summaryCurrency"));
        assertEquals(0, new BigDecimal("100").compareTo(new BigDecimal(summary.get("activePrincipal").toString())));
        assertBalance("PROJECT", "900", "0");
        assertEquals(0, jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=? AND wallet_type='PROJECT' AND currency='USDC'", BigDecimal.class, memberId).signum());
        transaction.executeWithoutResult(status->business.settleOrder(legacyOrder,starts.plusDays(2)));
        Integer replay=transaction.execute(status->business.settleOrder(legacyOrder,starts.plusDays(3)));
        assertEquals(Integer.valueOf(0),replay);
        assertEquals(0,new BigDecimal("1002").compareTo(jdbc.queryForObject("SELECT available FROM compute_wallet WHERE member_id=? AND wallet_type='EARNINGS' AND currency='USDC'",BigDecimal.class,memberId)));
        assertEquals(0,new BigDecimal("2").compareTo(jdbc.queryForObject("SELECT paid_income FROM compute_order WHERE id=?",BigDecimal.class,legacyOrder)));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE reference_id=? AND kind IN ('ORDER_INCOME','ORDER_PRINCIPAL') AND currency='USDC'",Integer.class,String.valueOf(legacyOrder)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE reference_id=? AND kind IN ('ORDER_INCOME','ORDER_PRINCIPAL') AND currency='USDT'",Integer.class,String.valueOf(legacyOrder)));
        assertBalance("EARNINGS","0","0");assertBalance("PROJECT","900","0");
    }

    @Test
    void recoveryManagementShowsUserAndReviewerNamesWithoutNumericUserSearch() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS ad_master(uid INT PRIMARY KEY,username VARCHAR(100))");
        jdbc.update("INSERT INTO ad_master(uid,username) VALUES(77,'reviewer-name')");
        transaction.executeWithoutResult(status -> accounts.requestRecovery(new ComputeAuthRequests.Recovery("username", "tester")));
        jdbc.update("UPDATE compute_recovery_request SET reviewer_id=77");
        var result = accounts.recoveries(1,20,"tester",null,null);
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String,Object>>) result.get("records");
        assertEquals(1, rows.size());
        assertEquals("tester", rows.get(0).get("username"));
        assertEquals("reviewer-name", rows.get(0).get("reviewerUsername"));
        assertEquals(0L, accounts.recoveries(1,20,"no-such-username",null,null).get("total"));
        assertFalse(rows.get(0).containsKey("tokenHash"));
    }

    @Test
    void sessionsAndRecoveryAreRevocableAndOneTime() {
        var login = transaction.execute(status -> accounts.login("email", new ComputeAuthRequests.Login("tester@example.invalid", "TestPass987!")));
        String token = login.get("token").toString();
        assertEquals(memberId, accounts.authenticate(token).id());
        assertNull(accounts.authenticate("random-token"));
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value) VALUES('emailEnabled','false'),('phoneEnabled','false')");
        transaction.executeWithoutResult(status -> accounts.requestRecovery(new ComputeAuthRequests.Recovery("username", "tester")));
        long recovery = jdbc.queryForObject("SELECT id FROM compute_recovery_request", Long.class);
        var issued = transaction.execute(status -> accounts.issueRecovery(recovery, 1));
        String resetToken = issued.get("token").toString();
        assertNotEquals(resetToken, jdbc.queryForObject("SELECT token_hash FROM compute_recovery_request", String.class));
        transaction.executeWithoutResult(status -> accounts.resetPassword(new ComputeAuthRequests.Reset(resetToken, "NewPass987!")));
        assertNull(accounts.authenticate(token));
        assertThrows(BusinessException.class, () -> transaction.executeWithoutResult(status -> accounts.resetPassword(new ComputeAuthRequests.Reset(resetToken, "AnotherPass987!"))));
        var renewed = transaction.execute(status -> accounts.login("username", new ComputeAuthRequests.Login("tester", "NewPass987!")));
        assertNotNull(accounts.authenticate(renewed.get("token").toString()));
        jdbc.update("UPDATE compute_member SET enabled=0 WHERE id=?", memberId);
        assertNull(accounts.authenticate(renewed.get("token").toString()));
    }

    private void assertBalance(String wallet, String available, String frozen) {
        Map<String, Object> row = jdbc.queryForMap("SELECT available,frozen FROM compute_wallet WHERE member_id=? AND wallet_type=? AND currency='USDT'", memberId, wallet);
        assertEquals(0, new BigDecimal(available).compareTo((BigDecimal) row.get("available")));
        assertEquals(0, new BigDecimal(frozen).compareTo((BigDecimal) row.get("frozen")));
    }

    @Test
    void clickComputePaysImmediatelyWithoutChangingPrincipal() {
        var data = new FixtureComputeData(jdbc, new ObjectMapper().findAndRegisterModules());
        var service = new ComputeAutoService(data, business, new ComputeCurrencyService(data));
        jdbc.update("UPDATE compute_level SET auto_enabled=1,auto_click_rate_min=4,auto_click_rate_max=4,auto_provider_ids='[1]',auto_daily_limit=1");
        transaction.executeWithoutResult(status -> business.changeWallet(memberId, "COMPUTE", "USDT", new BigDecimal("100"), BigDecimal.ZERO, "ADMIN_SYSTEM", "fund-click", "test"));
        var run = transaction.execute(status -> service.activate(memberId, Map.of("requestId", "v8-click-001", "amount", "999999")));
        long id = ((Number) run.get("id")).longValue();
        assertEquals("100.00000000", run.get("amount"));
        assertEquals("COMPLETED", run.get("status"));
        assertBalance("COMPUTE", "100", "0");
        LocalDateTime ready = LocalDateTime.ofInstant(Instant.parse(run.get("resultAvailableAt").toString()), ZoneOffset.UTC);
        transaction.executeWithoutResult(status -> service.finishDetails(id, ready.minusSeconds(1)));
        assertBalance("EARNINGS", "4", "0");
        jdbc.update("UPDATE compute_level SET auto_click_rate_min=99,auto_click_rate_max=99");
        transaction.executeWithoutResult(status -> service.finishDetails(id, ready));
        transaction.executeWithoutResult(status -> service.finishDetails(id, ready.plusHours(5)));
        assertBalance("COMPUTE", "100", "0");
        assertBalance("EARNINGS", "4", "0");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM compute_auto_settlement", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM compute_order", Integer.class));
        assertEquals(run.get("orderNo"), transaction.execute(status -> service.activate(memberId, Map.of("requestId", "v8-click-001"))).get("orderNo"));
        assertFalse((Boolean) service.status(memberId).get("canExecute"));
        assertNotNull(service.status(memberId).get("nextAllowedAt"));
        assertThrows(BusinessException.class, () -> transaction.execute(status -> service.activate(memberId, Map.of("requestId", "v8-click-002"))));
        assertThrows(BusinessException.class, () -> service.record(memberId + 100, run.get("orderNo").toString()));
        var result = (Map<?, ?>) service.record(memberId, run.get("orderNo").toString()).get("executionResult");
        assertEquals("SUCCEEDED", result.get("executionState"));
        assertFalse(result.containsKey("source"));
    }

    @Test
    void onlyUsageDetailsAreDelayedWhileIncomeIsAlreadyPaidAndPrincipalUnchanged() {
        var data = new FixtureComputeData(jdbc, new ObjectMapper().findAndRegisterModules());
        var service = new ComputeAutoService(data, business, new ComputeCurrencyService(data));
        jdbc.update("UPDATE compute_level SET auto_enabled=1,auto_click_rate_min=1,auto_click_rate_max=1,auto_provider_ids='[1]',vip_details_delay_minutes=120,auto_daily_limit=10");
        transaction.executeWithoutResult(status -> business.changeWallet(memberId, "COMPUTE", "USDT", new BigDecimal("100"), BigDecimal.ZERO, "ADMIN_SYSTEM", "fund-delay", "test"));
        var run = transaction.execute(status -> service.activate(memberId, Map.of("requestId", "v8-delay-001")));
        long id = ((Number) run.get("id")).longValue();
        LocalDateTime ready = LocalDateTime.ofInstant(Instant.parse(run.get("resultAvailableAt").toString()), ZoneOffset.UTC);
        LocalDateTime created = LocalDateTime.ofInstant(Instant.parse(run.get("createdAt").toString()), ZoneOffset.UTC);
        assertEquals(7200, java.time.temporal.ChronoUnit.SECONDS.between(created, ready));
        transaction.executeWithoutResult(status -> service.finishDetails(id, ready.minusMinutes(1)));
        assertEquals("PENDING",service.record(memberId,run.get("orderNo").toString()).get("detailsStatus"));
        assertTrue(((List<?>)((Map<?,?>)run.get("executionResult")).get("models")).isEmpty());
        assertBalance("COMPUTE", "100", "0");
        assertBalance("EARNINGS", "1", "0");
        assertTrue((Boolean)service.status(memberId).get("canExecute"));
        jdbc.update("UPDATE compute_member SET enabled=0 WHERE id=?", memberId);
        transaction.executeWithoutResult(status -> service.finishDetails(id, ready));
        transaction.executeWithoutResult(status -> service.finishDetails(id, ready.plusHours(5)));
        assertEquals("READY",service.record(memberId,run.get("orderNo").toString()).get("detailsStatus"));
        assertEquals(1,((List<?>)((Map<?,?>)service.record(memberId,run.get("orderNo").toString()).get("executionResult")).get("models")).size());
        assertBalance("COMPUTE", "100", "0");
        assertBalance("EARNINGS", "1", "0");
    }

    @Test
    void fixedVipRewardUsesConfiguredAmountWithoutTokensOrModels() {
        var data = new FixtureComputeData(jdbc, new ObjectMapper().findAndRegisterModules());
        var service = new ComputeAutoService(data, business, new ComputeCurrencyService(data));
        jdbc.update("UPDATE compute_level SET auto_enabled=1,vip_reward_mode='FIXED',vip_fixed_reward=2.5,auto_provider_ids='[]',auto_daily_limit=2");
        var run = transaction.execute(status -> service.activate(memberId, Map.of("requestId", "fixed-vip-001", "amount", "999999")));
        assertEquals("FIXED",run.get("vipRewardMode"));
        assertBalance("COMPUTE","0","0");
        assertBalance("EARNINGS","2.5","0");
        assertEquals("COMPLETED",run.get("status"));
        assertEquals("PENDING",run.get("detailsStatus"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_ledger WHERE kind IN ('AUTO_START','AUTO_RELEASE')",Integer.class));
        transaction.execute(status -> service.activate(memberId, Map.of("requestId", "fixed-vip-001")));
        assertBalance("EARNINGS","2.5","0");
    }

    @Test
    void tasksGateOnlyVipOrWithdrawalAndNeverOrdinaryProductOrTransfer() {
        preparePaymentPassword();
        var data = new FixtureComputeData(jdbc,new ObjectMapper().findAndRegisterModules());
        var service = new ComputeAutoService(data,business,new ComputeCurrencyService(data));
        jdbc.update("UPDATE compute_level SET auto_enabled=1,auto_click_rate_min=1,auto_click_rate_max=1");
        long task=data.insert("compute_content",ComputeData.fields("kind","TASK","title","业务行为确认","body_html","<p>确认要求</p>","created_by",1,"status","PUBLISHED","task_mode",3,"target_type","USER","created_at",ComputeData.now(),"updated_at",ComputeData.now()));
        jdbc.update("INSERT INTO compute_content_recipient(content_id,member_id,created_at) VALUES(?,?,UTC_TIMESTAMP(6))",task,memberId);
        var vipFailure=assertThrows(BusinessException.class,()->transaction.execute(status -> service.activate(memberId,Map.of("requestId","task-vip-001"))));
        assertEquals("server.compute.v17.taskRestricted",vipFailure.getMessage());
        var withdrawalFailure=assertThrows(BusinessException.class,()->transaction.execute(status -> business.withdraw(memberId,Map.of("requestId","task-withdraw-001","currency","USDT","network","TRC20","amount","10","address","T"+"1".repeat(33),"addressId",boundAddressId(),"expectedFee","0.1","expectedNetAmount","9.9","payPassword",PAY_PASSWORD))));
        assertEquals("server.compute.v17.taskRestricted",withdrawalFailure.getMessage());
        transaction.executeWithoutResult(status -> business.changeWallet(memberId,"PROJECT","USDT",new BigDecimal("100"),BigDecimal.ZERO,"ADMIN_SYSTEM","task-project-fund","test"));
        transaction.executeWithoutResult(status -> business.changeWallet(memberId,"EARNINGS","USDT",new BigDecimal("100"),BigDecimal.ZERO,"ADMIN_SYSTEM","task-transfer-fund","test"));
        assertNotNull(transaction.execute(status -> business.buyOrder(memberId,quotedOrderRequest("task-product-001", 1, "100"))));
        assertNotNull(transaction.execute(status -> business.transfer(memberId,Map.of("requestId","task-transfer-001","fromWallet","EARNINGS","toWallet","COMPUTE","currency","USDT","amount","10","payPassword",PAY_PASSWORD))));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM compute_auto_contract",Integer.class));
        jdbc.update("UPDATE compute_content_recipient SET completed_at=UTC_TIMESTAMP(6) WHERE content_id=?",task);
        assertThrows(BusinessException.class,()->transaction.execute(status -> service.activate(memberId,Map.of("requestId","task-vip-001"))));
        assertThrows(BusinessException.class,()->transaction.execute(status -> business.withdraw(memberId,Map.of("requestId","task-withdraw-001","currency","USDT","network","TRC20","amount","10","address","T"+"1".repeat(33),"addressId",boundAddressId(),"expectedFee","0.1","expectedNetAmount","9.9","payPassword",PAY_PASSWORD))));
        jdbc.update("UPDATE compute_content_recipient SET restriction_released_at=UTC_TIMESTAMP(6),restriction_released_by=1 WHERE content_id=? AND member_id=?",task,memberId);
        assertNotNull(transaction.execute(status -> service.activate(memberId,Map.of("requestId","task-vip-001"))));
    }

    @Test
    void expiredVipBlocksNewComputeAndPeriodPoliciesPreserveOrResetOldCycle() {
        var data = new FixtureComputeData(jdbc, new ObjectMapper().findAndRegisterModules());
        var periods = new ComputeVipPeriodService(data, null);
        jdbc.update("UPDATE compute_level SET auto_enabled=1,vip_validity_days=2");
        transaction.executeWithoutResult(status -> periods.initialize(memberId, 1));
        var before = data.one("SELECT vip_started_at,vip_expires_at FROM compute_member WHERE id=?", memberId);
        jdbc.update("INSERT INTO compute_level(auto_provider_ids,id,name,threshold,enabled,vip_validity_days,vip_cycle_policy) VALUES('[]',2,'保留周期',0,1,90,'KEEP'),('[]',3,'重算周期',0,1,7,'RESET')");
        transaction.executeWithoutResult(status -> periods.changeLevel(memberId, 2));
        assertEquals(before, data.one("SELECT vip_started_at,vip_expires_at FROM compute_member WHERE id=?", memberId));
        transaction.executeWithoutResult(status -> periods.changeLevel(memberId, 3));
        var after = data.one("SELECT vip_started_at,vip_expires_at FROM compute_member WHERE id=?", memberId);
        assertNotEquals(before.get("vipExpiresAt"), after.get("vipExpiresAt"));
        jdbc.update("UPDATE compute_member SET level=1,vip_expires_at=? WHERE id=?", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), memberId);
        var service = new ComputeAutoService(data, business, new ComputeCurrencyService(data));
        assertEquals("server.compute.v8.vipExpired", service.status(memberId).get("reasonKey"));
        assertThrows(BusinessException.class, () -> transaction.execute(status -> service.activate(memberId, Map.of("requestId", "expired-vip-001"))));
    }

}
