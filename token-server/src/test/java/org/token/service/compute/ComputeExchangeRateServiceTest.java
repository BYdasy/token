package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.repository.compute.ComputeData;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.token.config.compute.ComputeMcagProperties;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 官方JSON契约与隔离H2缓存测试；不调用外部API，不连接业务数据库。 */
class ComputeExchangeRateServiceTest {
    JdbcTemplate jdbc; FixtureComputeData data; ComputeExchangeRateClient client; ComputeExchangeRateService service;
    @BeforeEach void setup() {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:rates_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(source);data=new FixtureComputeData(jdbc,new ObjectMapper());client=mock(ComputeExchangeRateClient.class);
        service=new ComputeExchangeRateService(data,client);
        org.token.service.compute.TokenTestSchema.create(jdbc,"system_setting");
        jdbc.execute("CREATE TABLE compute_exchange_rate(rate_type VARCHAR(8),currency VARCHAR(16),rate DECIMAL(36,18),provider VARCHAR(32),source_id VARCHAR(128),source_at DATETIME(6),refreshed_at DATETIME(6),PRIMARY KEY(rate_type,currency))");
        jdbc.execute("CREATE TABLE compute_exchange_refresh(provider VARCHAR(32) PRIMARY KEY,last_attempt_at DATETIME(6),last_success_at DATETIME(6),next_attempt_at DATETIME(6),last_status VARCHAR(16))");
        jdbc.update("INSERT INTO compute_exchange_refresh(provider,last_status) VALUES('FRANKFURTER','PENDING'),('CRYPTO','PENDING')");
        jdbc.execute("CREATE TABLE compute_currency_asset(asset_code VARCHAR(16),coingecko_id VARCHAR(128),asset_type VARCHAR(8),enabled INT)");
        jdbc.execute("CREATE TABLE compute_currency(currency VARCHAR(16),network VARCHAR(32),enabled INT)");
        jdbc.execute("CREATE TABLE compute_currency_network(network_code VARCHAR(32),enabled INT)");
        jdbc.update("INSERT INTO compute_currency_asset VALUES('BTC','bitcoin','CRYPTO',1),('ETH','ethereum','CRYPTO',0),('USDT','tether','CRYPTO',1)");
        jdbc.update("INSERT INTO compute_currency VALUES('BTC','BITCOIN',1),('ETH','ETHEREUM',1),('USDT','ETHEREUM',1)");
        jdbc.update("INSERT INTO compute_currency_network VALUES('BITCOIN',1),('ETHEREUM',1)");
    }
    @Test void officialFiatAndMerchantResponsesKeepDirectionAndRejectInvalidValues() throws Exception {
        var mapper=new ObjectMapper();
        var fiat=ComputeExchangeRateClient.parseFiat(mapper.readTree("[{\"date\":\"2026-09-11\",\"base\":\"USD\",\"quote\":\"EUR\",\"rate\":0.91},{\"date\":\"2026-09-11\",\"base\":\"EUR\",\"quote\":\"JPY\",\"rate\":170}]"));
        assertEquals(1,fiat.size());assertEquals(0,new BigDecimal("0.91").compareTo(fiat.get(0).rate()));
        Instant now=Instant.now();var channel=new ComputeMcagProperties.Channel();channel.setCurrency("BTC");channel.setNetworkId("BITCOIN_MAINNET");
        var row=new LinkedHashMap<String,Object>(Map.of("asset_id","BTC_NATIVE","symbol","BTC","network_id","BITCOIN_MAINNET","status","AVAILABLE","snapshot_id","fx_one","usdt_price","61000","observed_at",now.minusSeconds(30).toString(),"expires_at",now.plusSeconds(300).toString()));
        var response=Map.<String,Object>of("quote_currency","USDT","items",List.of(row));
        var assets=Map.of("BTC_NATIVE",channel);
        var crypto=ComputeExchangeRateClient.parseMerchantRates(response,assets,now);
        assertEquals(1,crypto.size());assertEquals("BTC",crypto.get(0).code());assertEquals("CRYPTO",crypto.get(0).source());assertEquals("fx_one",crypto.get(0).sourceId());
        row.put("expires_at",now.minusSeconds(1).toString());assertTrue(ComputeExchangeRateClient.parseMerchantRates(response,assets,now).isEmpty());
        row.put("network_id","OTHER_CHAIN");assertThrows(IllegalArgumentException.class,()->ComputeExchangeRateClient.parseMerchantRates(response,assets,now));
    }
    @Test void refreshPersistsSuccessAndCrossNodeLeaseAvoidsRepeatingRequests() throws Exception {
        Instant at=Instant.now().minusSeconds(60);
        when(client.fiat()).thenReturn(List.of(new ComputeExchangeRateClient.Quote("EUR",new BigDecimal("0.91"),"FRANKFURTER","EUR",at)));
        when(client.crypto(anyMap())).thenReturn(List.of(new ComputeExchangeRateClient.Quote("BTC",new BigDecimal("61000"),"COINGECKO","bitcoin",at)));
        assertEquals(Map.of("BTC","BTC"),service.enabledCrypto());
        service.refresh();new ComputeExchangeRateService(data,client).refresh();
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM compute_exchange_rate",Integer.class));
        verify(client,times(1)).fiat();verify(client,times(1)).crypto(Map.of("BTC","BTC"));
        var valuation=service.valuation(Map.of("assetUsdRates",Map.of("USDT","9","USDC","8"),"fiatPerUsd",Map.of("USD","7")));
        assertEquals("1",((Map<?,?>)valuation.get("assetUsdRates")).get("USDT"));
        assertEquals("1",((Map<?,?>)valuation.get("fiatPerUsd")).get("USD"));
    }
    @Test void fiatAutomaticRefreshWaitsTwelveHoursEvenWithLegacyShortDeadlineWhileCryptoKeepsItsCadence() throws Exception {
        Instant start=Instant.parse("2026-10-03T00:00:00Z");
        when(client.fiat()).thenReturn(List.of(new ComputeExchangeRateClient.Quote("EUR",new BigDecimal("0.91"),"FRANKFURTER","EUR",start)));
        when(client.crypto(anyMap())).thenReturn(List.of(new ComputeExchangeRateClient.Quote("BTC",new BigDecimal("61000"),"CRYPTO","btc",start)));
        serviceAt(start).refresh();
        assertEquals(LocalDateTime.ofInstant(start.plus(Duration.ofHours(12)),ZoneOffset.UTC),
                jdbc.queryForObject("SELECT next_attempt_at FROM compute_exchange_refresh WHERE provider='FRANKFURTER'",LocalDateTime.class));
        assertEquals(LocalDateTime.ofInstant(start.plus(Duration.ofMinutes(30)),ZoneOffset.UTC),
                jdbc.queryForObject("SELECT next_attempt_at FROM compute_exchange_refresh WHERE provider='CRYPTO'",LocalDateTime.class));
        // 模拟升级前留下的短间隔截止时间；新节点仍须依据持久上次尝试时间限频。
        jdbc.update("UPDATE compute_exchange_refresh SET next_attempt_at=? WHERE provider='FRANKFURTER'",LocalDateTime.ofInstant(start.plusSeconds(60),ZoneOffset.UTC));
        serviceAt(start.plus(Duration.ofMinutes(31))).refresh();
        verify(client,times(1)).fiat();verify(client,times(2)).crypto(Map.of("BTC","BTC"));
        serviceAt(start.plus(Duration.ofHours(12)).minusSeconds(1)).refresh();
        verify(client,times(1)).fiat();
        serviceAt(start.plus(Duration.ofHours(12))).refresh();
        verify(client,times(2)).fiat();
    }
    @Test void failedFiatAttemptWaitsTwelveHoursAndManualRefreshStillBypassesAutomaticPeriod() throws Exception {
        Instant start=Instant.parse("2026-10-03T00:00:00Z");
        service.persist("FIAT",new ComputeExchangeRateClient.Quote("EUR",new BigDecimal("0.91"),"FRANKFURTER","EUR",start.minusSeconds(3600)),LocalDateTime.ofInstant(start.minusSeconds(3600),ZoneOffset.UTC));
        when(client.fiat()).thenThrow(new IllegalStateException("模拟网络失败"));
        serviceAt(start).refresh();
        assertEquals(false,serviceAt(start.plusSeconds(29)).requestRefresh("FRANKFURTER").get("accepted"));
        assertEquals(true,serviceAt(start.plusSeconds(30)).requestRefresh("FRANKFURTER").get("accepted"));
        serviceAt(start.plus(Duration.ofHours(6))).refresh();
        verify(client,times(2)).fiat();
        assertEquals("FAILED",jdbc.queryForObject("SELECT last_status FROM compute_exchange_refresh WHERE provider='FRANKFURTER'",String.class));
        assertEquals(0,new BigDecimal("0.91").compareTo(jdbc.queryForObject("SELECT rate FROM compute_exchange_rate WHERE currency='EUR'",BigDecimal.class)));
        assertEquals(LocalDateTime.ofInstant(start.plusSeconds(30).plus(Duration.ofHours(12)),ZoneOffset.UTC),
                jdbc.queryForObject("SELECT next_attempt_at FROM compute_exchange_refresh WHERE provider='FRANKFURTER'",LocalDateTime.class));
    }
    @Test void manualRefreshRecoversExpiredRunningLeaseWithoutWaitingForFiatAutomaticPeriod() throws Exception {
        Instant start=Instant.parse("2026-10-03T00:00:00Z");
        jdbc.update("UPDATE compute_exchange_refresh SET last_attempt_at=?,next_attempt_at=?,last_status='RUNNING' WHERE provider='FRANKFURTER'",
                LocalDateTime.ofInstant(start,ZoneOffset.UTC),LocalDateTime.ofInstant(start.plus(Duration.ofHours(12)),ZoneOffset.UTC));
        assertEquals(false,serviceAt(start.plus(Duration.ofMinutes(30)).minusSeconds(1)).requestRefresh("FRANKFURTER").get("accepted"));
        assertEquals(true,serviceAt(start.plus(Duration.ofMinutes(30))).requestRefresh("FRANKFURTER").get("accepted"));
        verify(client,times(1)).fiat();
    }
    @Test void providerMetadataAndFiatFreshnessUseTwelveHoursWithoutChangingSavedOverrides() {
        String saved=data.json(Map.of("FRANKFURTER",Map.of("enabled",true,"baseUrl",ComputeExchangeProviderSettings.FIAT_URL,"refreshSeconds",60,"revision",4)));
        jdbc.update("INSERT INTO system_setting(group_name,setting_key,setting_value,updated_at) VALUES('compute','exchangeProviders',?,CURRENT_TIMESTAMP)",saved);
        var config=new ComputeExchangeProviderSettings(data,"").publicSettings();
        assertEquals(Map.of("FRANKFURTER",720L,"CRYPTO",30L),config.get("refreshMinutesByProvider"));
        assertEquals(saved,jdbc.queryForObject("SELECT setting_value FROM system_setting WHERE setting_key='exchangeProviders'",String.class));
        Instant start=Instant.parse("2026-10-03T00:00:00Z");
        service.persist("FIAT",new ComputeExchangeRateClient.Quote("EUR",new BigDecimal("0.91"),"FRANKFURTER","EUR",start),LocalDateTime.ofInstant(start,ZoneOffset.UTC));
        @SuppressWarnings("unchecked") var current=(List<Map<String,Object>>)serviceAt(start.plus(Duration.ofHours(11))).valuation(Map.of()).get("sources");
        assertEquals(false,current.get(0).get("stale"));
        @SuppressWarnings("unchecked") var stale=(List<Map<String,Object>>)serviceAt(start.plus(Duration.ofHours(12)).plusSeconds(1)).valuation(Map.of()).get("sources");
        assertEquals(true,stale.get(0).get("stale"));
    }
    private ComputeExchangeRateService serviceAt(Instant time) {
        return serviceAt(time,client);
    }
    private ComputeExchangeRateService serviceAt(Instant time,ComputeExchangeRateClient rateClient) {
        return new ComputeExchangeRateService(data,rateClient,Runnable::run,null,
                new org.token.repository.compute.ComputeExchangeRateRepository(data),Clock.fixed(time,ZoneOffset.UTC));
    }
    @Test void changedConfigurationRetriesWhenDueThenRestoresTwelveHourAutomaticInterval() throws Exception {
        Instant start=Instant.parse("2026-10-03T00:00:00Z");
        var settings=new ComputeExchangeProviderSettings(data,"");
        var tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        tx.executeWithoutResult(status->settings.save("FRANKFURTER",Map.of("revision",0,"enabled",true,"baseUrl",ComputeExchangeProviderSettings.FIAT_URL)));
        var realClient=spy(new ComputeExchangeRateClient(new ObjectMapper(),settings));
        service.persist("FIAT",new ComputeExchangeRateClient.Quote("EUR",new BigDecimal("0.91"),"FRANKFURTER","EUR",start.minusSeconds(60)),LocalDateTime.ofInstant(start.minusSeconds(60),ZoneOffset.UTC));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{
            var original=(ComputeExchangeProviderSettings.Provider)call.getArgument(0);
            if(calls.incrementAndGet()==1) {
                tx.executeWithoutResult(status->settings.save("FRANKFURTER",Map.of("revision",original.revision(),"enabled",true,"baseUrl",original.baseUrl())));
                return List.of(new ComputeExchangeRateClient.Quote("EUR",new BigDecimal("9"),"FRANKFURTER","EUR",start));
            }
            return List.of(new ComputeExchangeRateClient.Quote("EUR",new BigDecimal("0.92"),"FRANKFURTER","EUR",start));
        }).when(realClient).fiat(any(ComputeExchangeProviderSettings.Provider.class));

        serviceAt(start,realClient).refresh();
        assertEquals("CONFIG_CHANGED",jdbc.queryForObject("SELECT last_status FROM compute_exchange_refresh WHERE provider='FRANKFURTER'",String.class));
        assertEquals(LocalDateTime.ofInstant(start,ZoneOffset.UTC),jdbc.queryForObject("SELECT next_attempt_at FROM compute_exchange_refresh WHERE provider='FRANKFURTER'",LocalDateTime.class));
        assertEquals(0,new BigDecimal("0.91").compareTo(jdbc.queryForObject("SELECT rate FROM compute_exchange_rate WHERE currency='EUR'",BigDecimal.class)));
        assertNull(jdbc.queryForObject("SELECT last_success_at FROM compute_exchange_refresh WHERE provider='FRANKFURTER'",LocalDateTime.class));

        // 配置变更只豁免周期检查，不绕过持久的 next_attempt_at 截止约束。
        Instant next=start.plus(Duration.ofMinutes(30));
        jdbc.update("UPDATE compute_exchange_refresh SET next_attempt_at=? WHERE provider='FRANKFURTER'",LocalDateTime.ofInstant(next,ZoneOffset.UTC));
        serviceAt(next.minusSeconds(1),realClient).refresh();
        assertEquals(1,calls.get());
        serviceAt(next,realClient).refresh();
        assertEquals(2,calls.get());
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT last_status FROM compute_exchange_refresh WHERE provider='FRANKFURTER'",String.class));
        assertEquals(0,new BigDecimal("0.92").compareTo(jdbc.queryForObject("SELECT rate FROM compute_exchange_rate WHERE currency='EUR'",BigDecimal.class)));
        assertEquals(LocalDateTime.ofInstant(next.plus(Duration.ofHours(12)),ZoneOffset.UTC),jdbc.queryForObject("SELECT next_attempt_at FROM compute_exchange_refresh WHERE provider='FRANKFURTER'",LocalDateTime.class));
        serviceAt(next.plus(Duration.ofMinutes(30)),realClient).refresh();
        assertEquals(2,calls.get());
    }
    @Test void merchantCryptoUsesConfiguredAssetAndSignedGatewayWithoutCoinGeckoKey() throws Exception {
        var config=new ComputeMcagProperties();config.setEnabled(true);
        var channel=new ComputeMcagProperties.Channel();channel.setCurrency("BTC");channel.setAssetId("BTC_NATIVE");channel.setNetworkId("BITCOIN_MAINNET");
        config.setChannels(Map.of("btc",channel));
        @SuppressWarnings("unchecked") var provider=(org.springframework.beans.factory.ObjectProvider<ComputeMcagClient>)mock(org.springframework.beans.factory.ObjectProvider.class);
        var gateway=mock(ComputeMcagClient.class);when(provider.getIfAvailable()).thenReturn(gateway);when(provider.getObject()).thenReturn(gateway);
        Instant at=Instant.now();
        when(gateway.request("exchange-rates/query",Map.of("asset_ids",List.of("BTC_NATIVE")))).thenReturn(Map.of("quote_currency","USDT","items",List.of(Map.of("asset_id","BTC_NATIVE","symbol","BTC","network_id","BITCOIN_MAINNET","status","AVAILABLE","usdt_price","61000","snapshot_id","fx_btc","observed_at",at.minusSeconds(5).toString(),"expires_at",at.plusSeconds(500).toString()))));
        var inactive=new ComputeMcagProperties.Channel();inactive.setCurrency("BTC");inactive.setAssetId("BTC_REVOKED");inactive.setNetworkId("BITCOIN_MAINNET");inactive.setActive(false);
        var mappings=mock(ComputeMcagChannelService.class);when(mappings.listChannels()).thenReturn(List.of(channel,inactive));
        var live=new ComputeExchangeRateClient(new ObjectMapper(),new ComputeExchangeProviderSettings(data,""),provider,config,mappings);
        var quotes=live.crypto(Map.of("BTC","BTC"));
        assertEquals(1,quotes.size());assertEquals("CRYPTO",quotes.get(0).source());
        verify(gateway).request("exchange-rates/query",Map.of("asset_ids",List.of("BTC_NATIVE")));verifyNoMoreInteractions(gateway);
    }
    @Test void failedAndOlderUpdatesPreserveLastSuccessfulRate() throws Exception {
        Instant at=Instant.now();
        service.persist("ASSET",new ComputeExchangeRateClient.Quote("BTC",new BigDecimal("61000"),"COINGECKO","bitcoin",at),LocalDateTime.now(ZoneOffset.UTC));
        service.persist("ASSET",new ComputeExchangeRateClient.Quote("BTC",new BigDecimal("1"),"COINGECKO","bitcoin",at.minusSeconds(10)),LocalDateTime.now(ZoneOffset.UTC));
        when(client.fiat()).thenThrow(new IllegalStateException("模拟网络失败"));when(client.crypto(anyMap())).thenThrow(new IllegalStateException("模拟限流"));
        service.refresh();
        assertEquals(0,new BigDecimal("61000").compareTo(jdbc.queryForObject("SELECT rate FROM compute_exchange_rate WHERE currency='BTC'",BigDecimal.class)));
        assertEquals("FAILED",jdbc.queryForObject("SELECT last_status FROM compute_exchange_refresh WHERE provider='CRYPTO'",String.class));
        var cached=service.valuation(Map.of());
        assertTrue(((Map<?,?>)cached.get("assetUsdRates")).containsKey("BTC"));
    }
    @Test void fiatWalletUsesInverseUsdQuoteWithoutTreatingCryptoSymbolsAsFiat() {
        jdbc.update("INSERT INTO compute_currency_asset VALUES('BRL',NULL,'FIAT',1),('EUR','custom-coin','CRYPTO',1)");
        Instant at=Instant.now();
        service.persist("FIAT",new ComputeExchangeRateClient.Quote("BRL",new BigDecimal("5"),"FRANKFURTER","BRL",at),LocalDateTime.now(ZoneOffset.UTC));
        service.persist("FIAT",new ComputeExchangeRateClient.Quote("EUR",new BigDecimal("0.9"),"FRANKFURTER","EUR",at),LocalDateTime.now(ZoneOffset.UTC));
        var valuation=service.valuation(Map.of());
        var prices=(Map<?,?>)valuation.get("assetUsdRates");
        assertEquals(0,new BigDecimal("0.2").compareTo(new BigDecimal(prices.get("BRL").toString())));
        assertFalse(prices.containsKey("EUR"));
        assertEquals(0,new BigDecimal("5").compareTo(new BigDecimal(((Map<?,?>)valuation.get("fiatPerUsd")).get("BRL").toString())));
    }
    @Test void retiredKeyConfigurationIsNeverReturnedOrChangedByMerchantSettings() {
        jdbc.update("INSERT INTO system_setting(group_name,setting_key,setting_value,updated_at) VALUES('compute','exchangeProviders',?,CURRENT_TIMESTAMP)",data.json(Map.of("COINGECKO",Map.of("encryptedApiKey","enc:v2:stored-secret","revision",7))));
        var settings=new ComputeExchangeProviderSettings(data,"");
        var tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        var saved=tx.execute(status->settings.save("CRYPTO",Map.of("revision",0,"enabled",true)));
        var snapshot=data.json(saved);
        assertFalse(snapshot.contains("COINGECKO"));assertFalse(snapshot.contains("enc:v2:"));
        String persisted=jdbc.queryForObject("SELECT setting_value FROM system_setting WHERE setting_key='exchangeProviders'",String.class);
        assertTrue(persisted.contains("enc:v2:stored-secret"));assertEquals("",settings.provider("CRYPTO").apiKey());
        assertThrows(org.token.exception.BusinessException.class,()->settings.provider("COINGECKO"));
    }
    @Test void fiatHostCanChangeWithoutResubmittingUnrelatedCoinGeckoKey() {
        var settings=new ComputeExchangeProviderSettings(data,"rates.example.com");
        var tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        assertDoesNotThrow(()->tx.execute(status->settings.save("FRANKFURTER",Map.of("revision",0,"enabled",true,"baseUrl","https://rates.example.com/v2","plan","DEMO"))));
        assertEquals("https://rates.example.com/v2",settings.provider("FRANKFURTER").baseUrl());
        assertEquals("",settings.provider("FRANKFURTER").apiKey());
        assertEquals("",settings.provider("CRYPTO").apiKey());
        assertThrows(org.token.exception.BusinessException.class,()->tx.execute(status->settings.save("FRANKFURTER",Map.of("revision",1,"enabled",true,"baseUrl","https://unauthorized.example/api"))));
    }
    @Test void manualLeaseIsVisibleBeforeExecutionAndChangedRevisionCannotPublishOldResponse() throws Exception {
        var settings=new ComputeExchangeProviderSettings(data,"");
        var tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        tx.executeWithoutResult(status->settings.save("FRANKFURTER",Map.of("revision",0,"enabled",true,"baseUrl",ComputeExchangeProviderSettings.FIAT_URL,"plan","DEMO")));
        var realClient=spy(new ComputeExchangeRateClient(new ObjectMapper(),settings));
        Instant before=Instant.now().minusSeconds(60);
        service.persist("FIAT",new ComputeExchangeRateClient.Quote("EUR",new BigDecimal("0.91"),"FRANKFURTER","EUR",before),LocalDateTime.now(ZoneOffset.UTC));
        doAnswer(call->{
            var original=(ComputeExchangeProviderSettings.Provider)call.getArgument(0);
            tx.executeWithoutResult(status->settings.save("FRANKFURTER",Map.of("revision",original.revision(),"enabled",false,"baseUrl",original.baseUrl(),"plan","DEMO")));
            return List.of(new ComputeExchangeRateClient.Quote("EUR",new BigDecimal("9"),"FRANKFURTER","EUR",Instant.now()));
        }).when(realClient).fiat(any(ComputeExchangeProviderSettings.Provider.class));
        List<Runnable> queued=new ArrayList<>();
        var isolated=new ComputeExchangeRateService(data,realClient,queued::add);
        var accepted=isolated.requestRefresh("FRANKFURTER");
        assertEquals(true,accepted.get("accepted"));assertNotNull(accepted.get("attemptAt"));
        assertEquals("RUNNING",jdbc.queryForObject("SELECT last_status FROM compute_exchange_refresh WHERE provider='FRANKFURTER'",String.class));
        assertEquals(false,isolated.requestRefresh("FRANKFURTER").get("accepted"));
        queued.get(0).run();
        assertEquals("CONFIG_CHANGED",jdbc.queryForObject("SELECT last_status FROM compute_exchange_refresh WHERE provider='FRANKFURTER'",String.class));
        assertEquals(0,new BigDecimal("0.91").compareTo(jdbc.queryForObject("SELECT rate FROM compute_exchange_rate WHERE currency='EUR'",BigDecimal.class)));
        assertNull(jdbc.queryForObject("SELECT last_success_at FROM compute_exchange_refresh WHERE provider='FRANKFURTER'",java.sql.Timestamp.class));
        assertEquals(2L,settings.provider("FRANKFURTER").revision());
    }
}
