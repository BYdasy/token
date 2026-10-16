package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import org.junit.jupiter.api.Tag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.config.compute.ComputeMcagProperties;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.token.repository.compute.ComputeData.*;

/** 仅在随机命名 H2 内存库验证币种规则与事务，不执行真实数据库脚本。 */
@Tag("regression")
class ComputeCurrencyServiceTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ComputeCurrencyService service;

    @Test
    void bitcoinTronAndEvmAddressesKeepTheirNetworkBoundary() {
        var btc = fields("assetType", "CRYPTO", "chainType", "BTC", "requiredFields", List.of());
        var evm = fields("assetType", "CRYPTO", "chainType", "EVM", "requiredFields", List.of());
        var tron = fields("assetType", "CRYPTO", "chainType", "TRON", "requiredFields", List.of());
        String legacy = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT";
        assertEquals(legacy, service.validateAddress(btc, fields("address", legacy)).address());
        String segwit = "bc1q" + "q".repeat(38);
        assertEquals(segwit, service.validateAddress(btc, fields("address", segwit)).address());
        assertThrows(BusinessException.class, () -> service.validateAddress(btc, fields("address", "0x" + "1".repeat(40))));
        assertThrows(BusinessException.class, () -> service.validateAddress(btc, fields("address", "BC1q" + "q".repeat(38))));
        assertThrows(BusinessException.class, () -> service.validateAddress(evm, fields("address", legacy)));
        assertThrows(BusinessException.class, () -> service.validateAddress(tron, fields("address", legacy)));
    }

    @BeforeEach
    void schema() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:currency_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        TokenTestSchema.create(jdbc, "compute_member", "system_setting", "compute_member_session", "compute_recovery_request", "compute_currency", "compute_wallet", "compute_ledger", "compute_request", "compute_model", "compute_model_price", "compute_level", "compute_product", "compute_order", "compute_order_settlement", "compute_deposit", "compute_fee_rule", "compute_transfer_rule", "compute_transfer", "compute_withdrawal", "compute_address", "compute_job", "compute_job_run", "compute_currency_asset", "compute_currency_network");
        jdbc.update("INSERT INTO system_setting(setting_key,setting_value,updated_at) VALUES('summaryCurrency','USDT',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO compute_currency(currency,network) VALUES('USDT','TRC20')");
        jdbc.update("INSERT INTO compute_currency_asset(asset_code,asset_name,created_at,updated_at) VALUES('USDT','泰达币',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO compute_currency_network(network_code,network_name,chain_type,created_at,updated_at) VALUES('TRC20','波场','TRON',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        service = new ComputeCurrencyService(new FixtureComputeData(jdbc, new ObjectMapper().findAndRegisterModules()));
    }

    private Map<String, Object> save(String kind, Long id, Map<String, Object> values) { return tx.execute(status -> service.save(kind, id, values)); }

    @Test void cryptoRateIdentifiersAreExplicitAndNeverAcceptUrls() {
        var asset = save("currency-assets", null, fields("assetCode","BTC","assetName","比特币","assetType","CRYPTO","coingeckoId","bitcoin"));
        assertEquals("bitcoin", asset.get("coingeckoId"));
        assertThrows(BusinessException.class, () -> save("currency-assets", ((Number)asset.get("id")).longValue(), fields("coingeckoId","https://invalid.example")));
        assertEquals("bitcoin", jdbc.queryForObject("SELECT coingecko_id FROM compute_currency_asset WHERE asset_code='BTC'",String.class));
    }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> page) { return (List<Map<String, Object>>) page.get("records"); }

    @Test
    void legacyChannelKeepsAdvancedModeAndMetadataIsFullyJoined() {
        Map<String, Object> channel = service.publicChannels().get(0);
        assertEquals("ADVANCED_RULE", channel.get("feeMode"));
        assertEquals("泰达币", channel.get("assetName"));
        assertEquals("TRON", channel.get("chainType"));
        assertEquals("",channel.get("networkIconUrl"));
        assertEquals(List.of(), channel.get("requiredFields"));
        for (String key : List.of("tokenStandard", "tokenContract", "supportAccountTypes", "withdrawFeeRate", "confirmBlocks", "assetAmountScale"))
            assertTrue(channel.containsKey(key), key);
    }

    @Test
    void createAndSearchCurrenciesAndSortWhitelist() {
        save("currency-assets", null, fields("assetCode","USD","assetName","美元","assetType","FIAT"));
        save("currency-networks", null, fields("networkCode","BANK_US","networkName","银行%专线","networkType","FIAT","chainType","BANK","requiredFields",List.of("holderName","bankAccountNo")));
        Map<String, Object> channel = save("currencies", null, fields("currency","USD","network","BANK_US","depositMin","20","withdrawMax","1000","amountScale",2));
        assertEquals("CHANNEL", channel.get("feeMode"));
        Map<String, Object> page = service.page("currencies", Map.of("assetType","FIAT","depositEnabled","true","keyword","%","sortField","DROP TABLE","size","1"));
        assertEquals(1L, number(page.get("total")));
        assertEquals("USD", rows(page).get(0).get("currency"));
        assertTrue(rows(service.page("currencies", Map.of("network","TRC20","withdrawEnabled","false"))).isEmpty());
        assertThrows(BusinessException.class, () -> save("currencies", null, fields("currency","USD","network","TRC20")));
        assertThrows(BusinessException.class, () -> save("currencies", number(channel.get("id")), fields("currency","USDT")));
    }

    @Test
    void switchesLimitsAndSupportedWalletsApplyBeforeFees() {
        save("currencies", 1L, fields("depositMin","10","withdrawMin","20","withdrawMax","200","supportAccountTypes","COMPUTE,EARNINGS"));
        service.requireChannel("USDT","TRC20","DEPOSIT","COMPUTE",new BigDecimal("10"));
        assertThrows(BusinessException.class, () -> service.requireChannel("USDT","TRC20","DEPOSIT","COMPUTE",new BigDecimal("9.999")));
        assertThrows(BusinessException.class, () -> service.requireChannel("USDT","TRC20","DEPOSIT","PROJECT",new BigDecimal("10")));
        assertThrows(BusinessException.class, () -> service.requireChannel("USDT","TRC20","WITHDRAW","EARNINGS",new BigDecimal("201")));
        save("currencies",1L,fields("withdrawEnabled",false));
        assertThrows(BusinessException.class, () -> service.requireChannel("USDT","TRC20","WITHDRAW","EARNINGS",new BigDecimal("100")));
        assertEquals("server.compute.currency.withdrawDisabled", assertThrows(BusinessException.class,
                () -> service.requireChannel("USDT","TRC20","ADDRESS","EARNINGS",null)).getMessage());
        service.requireChannel("USDT","TRC20","DEPOSIT","COMPUTE",new BigDecimal("10"));
    }

    @Test
    void channelRatioUsesFractionAndRoundsUpWithoutDoubleFee() {
        Map<String, Object> rule = fields("withdrawFeeFixed","1","withdrawFeeRate","0.005");
        assertEquals(new BigDecimal("1.50000000"), ComputeCurrencyService.channelFee(rule,new BigDecimal("100")));
        assertEquals(new BigDecimal("0.00000002"), ComputeCurrencyService.channelFee(fields("withdrawFeeRate","0.5"),new BigDecimal("0.00000003")));
        assertThrows(BusinessException.class, () -> ComputeCurrencyService.channelFee(fields("withdrawFeeFixed","1"),new BigDecimal("1")));
    }

    @Test
    void fiatFieldsAreValidatedAndCopiedIntoSnapshot() {
        save("currency-assets", null, fields("assetCode","BRL","assetName","雷亚尔","assetType","FIAT"));
        assertThrows(BusinessException.class, () -> save("currency-networks",null, fields("networkCode","PIX","networkName","PIX","networkType","FIAT","requiredFields",List.of("holderName"))));
        save("currency-networks",null, fields("networkCode","PIX","networkName","PIX","networkType","FIAT","chainType","PIX","requiredFields",List.of("holderName","pixKey","cpf")));
        var channel = save("currencies",null,fields("currency","BRL","network","PIX"));
        var address = service.validateAddress(channel,fields("fields",Map.of("holderName","测试收款人","pixKey","receiver@example.test","cpf","123")));
        assertEquals("receiver@example.test",address.address());
        assertEquals("123",address.fields().get("cpf"));
        assertTrue(address.snapshot().containsKey("fields"));
        assertThrows(BusinessException.class, () -> service.validateAddress(channel,fields("fields",Map.of("holderName","测试收款人","pixKey","receiver@example.test"))));
        assertThrows(BusinessException.class, () -> service.validateAddress(channel,fields("fields",Map.of("privateKey","forbidden"))));
    }

    @Test
    void memoAndNetworkAddressValidationCannotBeBypassed() {
        var channel = save("currencies",1L,fields("needMemo",true));
        String tron = "T" + "A".repeat(33);
        assertThrows(BusinessException.class, () -> service.validateAddress(channel,fields("address",tron)));
        assertEquals("123", service.validateAddress(channel,fields("address",tron,"memo","123")).memo());
        assertThrows(BusinessException.class, () -> service.validateAddress(channel,fields("address","0x"+"a".repeat(40),"memo","123")));
    }

    @Test
    void metadataCannotDisableLastSummaryCurrencyAndRollsBack() {
        assertThrows(BusinessException.class, () -> save("currency-assets",1L,fields("enabled",false)));
        assertThrows(BusinessException.class, () -> save("currency-networks",1L,fields("enabled",false)));
        assertEquals(1,service.publicChannels().size());
        assertThrows(BusinessException.class, () -> save("currency-assets",1L,fields("assetType","FIAT")));
    }

    @Test
    void synchronizedMetadataRemainsReadOnlyEvenWhenRemoteChannelIsInactive() throws Exception {
        TokenTestSchema.create(jdbc,"compute_mcag_channel");
        jdbc.update("UPDATE compute_currency SET token_standard='TRC20',token_contract='original-contract' WHERE id=1");
        jdbc.update("UPDATE compute_currency_network SET native_asset_code='TRX',confirm_blocks=20 WHERE id=1");
        jdbc.update("INSERT INTO compute_mcag_channel(merchant_id,environment,currency,network,asset_id,network_id,decimals,active,metadata_json,synced_at,created_at,updated_at) VALUES('merchant-test','LIVE','USDT','TRC20','USDT_TRON','TRON_MAINNET',6,0,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "{\"minimum_deposit\":\"1\",\"minimum_payout\":\"1\"}");
        var config=new ComputeMcagProperties();config.setEnabled(true);config.setMerchantId("merchant-test");
        service=new ComputeCurrencyService(new FixtureComputeData(jdbc,new ObjectMapper().findAndRegisterModules()),config);
        assertTrue(service.publicChannels().isEmpty());
        assertEquals(1,rows(service.page("currencies",Map.of())).size());
        assertEquals("server.compute.unsupportedCurrency",assertThrows(BusinessException.class,
                ()->service.requireChannel("USDT","TRC20","ADDRESS",null,null)).getMessage());

        var network=save("currency-networks",1L,fields("chainType","EVM","nativeAssetCode","ETH","confirmBlocks",1,"networkName","保留本地显示名"));
        assertEquals("TRON",network.get("chainType"));assertEquals("TRX",network.get("nativeAssetCode"));
        assertEquals(20,number(network.get("confirmBlocks")));assertEquals(true,network.get("gatewaySynced"));
        assertEquals("保留本地显示名",network.get("networkName"));
        save("currencies",1L,fields("tokenStandard","ERC20","tokenContract","changed","amountScale",8,"withdrawFeeFixed","3","withdrawEnabled",false));
        assertEquals("original-contract",jdbc.queryForObject("SELECT token_contract FROM compute_currency WHERE id=1",String.class));
        assertEquals("TRC20",jdbc.queryForObject("SELECT token_standard FROM compute_currency WHERE id=1",String.class));
        assertEquals(6,jdbc.queryForObject("SELECT amount_scale FROM compute_currency WHERE id=1",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT withdraw_enabled FROM compute_currency WHERE id=1",Integer.class));
        assertEquals(0,new BigDecimal("3").compareTo(jdbc.queryForObject("SELECT withdraw_fee_fixed FROM compute_currency WHERE id=1",BigDecimal.class)));
        save("currencies",1L,fields("amountScale",4));
        assertEquals(4,jdbc.queryForObject("SELECT amount_scale FROM compute_currency WHERE id=1",Integer.class));
        config.setEnabled(false);
        var disabledIntegrationChannel=rows(service.page("currencies",Map.of())).get(0);
        assertEquals(true,disabledIntegrationChannel.get("gatewaySynced"));
        assertEquals(6,number(disabledIntegrationChannel.get("chainDecimals")));
        config.setEnabled(true);
        jdbc.update("UPDATE compute_mcag_channel SET active=1,deposit_enabled=0,payout_enabled=0");
        assertEquals(1,service.publicChannels().size());
        assertEquals("server.compute.currency.withdrawDisabled",assertThrows(BusinessException.class,
                ()->service.requireChannel("USDT","TRC20","ADDRESS","EARNINGS",null)).getMessage());
        jdbc.update("UPDATE compute_currency SET withdraw_enabled=1 WHERE id=1");
        // 本地打开也不能绕过远端提款关闭。
        assertEquals("server.compute.currency.withdrawDisabled",assertThrows(BusinessException.class,
                ()->service.requireChannel("USDT","TRC20","ADDRESS","EARNINGS",null)).getMessage());
        jdbc.update("UPDATE compute_mcag_channel SET payout_enabled=1");
        assertDoesNotThrow(()->service.requireChannel("USDT","TRC20","ADDRESS","EARNINGS",null));
        jdbc.update("DELETE FROM compute_mcag_channel");
        assertTrue(service.publicChannels().isEmpty());
    }

    @Test
    void concurrentDisablingLeavesOneSummaryChannel() throws Exception {
        save("currency-networks",null,fields("networkCode","ERC20","networkName","以太坊","chainType","EVM"));
        var second = save("currencies",null,fields("currency","USDT","network","ERC20"));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { try { save("currencies",1L,fields("enabled",false)); return true; } catch (BusinessException expected) { return false; } });
            var next = pool.submit(() -> { try { save("currencies",number(second.get("id")),fields("enabled",false)); return true; } catch (BusinessException expected) { return false; } });
            assertNotEquals(first.get(8,TimeUnit.SECONDS), next.get(8,TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        assertEquals(1,service.publicChannels().size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void polygonOnlyExposesTheMappedChannelAndKeepsHistoricalIdentityAndSwitches() throws Exception {
        TokenTestSchema.create(jdbc,"compute_mcag_channel");
        save("currency-assets",null,fields("assetCode","USDT0","assetName","USDT","assetType","CRYPTO"));
        save("currency-networks",null,fields("networkCode","POLYGON","networkName","POL","chainType","EVM","nativeAssetCode","POL"));
        var legacy=save("currencies",null,fields("currency","USDT","network","POLYGON","enabled",false));
        var canonical=save("currencies",null,fields("currency","USDT0","network","POLYGON","withdrawEnabled",false,"withdrawMin","2"));
        jdbc.update("INSERT INTO compute_mcag_channel(merchant_id,environment,currency,network,asset_id,network_id,decimals,active,deposit_enabled,payout_enabled,metadata_json,synced_at,created_at,updated_at) VALUES('merchant-test','LIVE','USDT0','POLYGON','USDT_POLYGON','POLYGON_MAINNET',6,1,1,1,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "{\"minimum_deposit\":\"1\",\"minimum_payout\":\"1\"}");
        var config=new ComputeMcagProperties();config.setEnabled(true);config.setMerchantId("merchant-test");
        var data=new FixtureComputeData(jdbc,new ObjectMapper().findAndRegisterModules());
        service=new ComputeCurrencyService(data,config);
        var result=service.page("currencies",Map.of("network","POLYGON","size","1"));
        assertEquals(1L,number(result.get("total")));assertEquals("USDT0",rows(result).get(0).get("currency"));
        assertTrue(rows(service.page("currencies",Map.of("network","POLYGON","size","1","page","2"))).isEmpty());
        assertTrue(rows(service.page("currencies",Map.of("currency","USDT","network","POLYGON"))).isEmpty());
        var options=(List<Map<String,Object>>)service.options().get("channels");
        assertEquals(List.of("USDT0"),options.stream().filter(row->"POLYGON".equals(row.get("network"))).map(row->text(row.get("currency"))).toList());
        assertEquals("USDT0",service.publicChannels().get(0).get("currency"));
        assertEquals("USDT_POLYGON",service.publicChannels().get(0).get("gatewayAssetId"));
        assertEquals(false,service.publicChannels().get(0).get("withdrawEnabled"));
        assertEquals("server.compute.currency.duplicate",assertThrows(BusinessException.class,
                ()->save("currencies",number(legacy.get("id")),fields("enabled",true))).getMessage());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_currency WHERE network='POLYGON'",Integer.class));
        assertEquals("USDT",data.required("compute_currency",number(legacy.get("id")),false).get("currency"));
        assertEquals(0,jdbc.queryForObject("SELECT withdraw_enabled FROM compute_currency WHERE id=?",Integer.class,number(canonical.get("id"))));
        config.setEnabled(false);
        assertEquals(1L,number(service.page("currencies",Map.of("network","POLYGON")).get("total")));
        config.setMerchantId("other-merchant");
        assertEquals(2L,number(service.page("currencies",Map.of("network","POLYGON")).get("total")));
        config.setMerchantId("merchant-test");
        jdbc.update("UPDATE compute_currency SET enabled=1 WHERE id=?",number(legacy.get("id")));
        assertEquals(2L,number(service.page("currencies",Map.of("network","POLYGON")).get("total")));
        jdbc.update("UPDATE compute_currency SET enabled=0 WHERE id=?",number(legacy.get("id")));
        jdbc.update("UPDATE compute_mcag_channel SET asset_id='UNRELATED_ASSET'");
        assertEquals(2L,number(service.page("currencies",Map.of("network","POLYGON")).get("total")));
    }
}
