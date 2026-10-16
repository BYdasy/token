package org.token.service.compute;

import org.token.repository.support.FixtureComputeData;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.token.exception.BusinessException;
import org.token.config.compute.ComputeMcagProperties;
import org.token.repository.compute.ComputeData;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.token.repository.compute.ComputeData.fields;

/** 地址安全仅使用隔离H2及模拟查询，不连接真实Crypto或执行真实提款。 */
class ComputeWithdrawalAddressGuardTest {
    private static final String ADDRESS="0xAb1234567890123456789012345678901234567890";
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ComputeWithdrawalAddressGuard guard;
    private FixtureComputeData data;
    private ComputeMcagProperties config;
    private final Map<String,Object> eth=fields("assetType","CRYPTO","chainType","EVM","currency","USDT","network","ETH");
    private ComputeCurrencyService.AddressData recipient(String address){return new ComputeCurrencyService.AddressData(address,"",Map.of());}

    @BeforeEach void setup() {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:address_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        org.token.service.compute.TokenTestSchema.create(jdbc,"system_setting");
        jdbc.execute("CREATE TABLE compute_currency_network(network_code VARCHAR(32) PRIMARY KEY,chain_type VARCHAR(32))");
        jdbc.execute("CREATE TABLE compute_address_guard(guard_key VARCHAR(64) PRIMARY KEY,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE compute_address(id BIGINT AUTO_INCREMENT PRIMARY KEY,member_id BIGINT,network VARCHAR(32),address VARCHAR(200),memo VARCHAR(120),address_key VARCHAR(64),enabled INT DEFAULT 1,removed INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE compute_mcag_address(address VARCHAR(200),network VARCHAR(32))");
        jdbc.execute("CREATE TABLE compute_withdrawal(member_id BIGINT,address VARCHAR(200),network VARCHAR(32),status VARCHAR(24))");
        jdbc.update("INSERT INTO compute_currency_network VALUES('ETH','EVM'),('BSC','EVM'),('TRON','TRON')");
        data=new FixtureComputeData(jdbc,new ObjectMapper());config=new ComputeMcagProperties();
        guard=new ComputeWithdrawalAddressGuard(data,config,null,new ComputeMcagChannelService(data,config));
    }

    @Test void evmAddressBelongsToOneUserAcrossNetworksAndCase() {
        jdbc.update("INSERT INTO compute_address(member_id,network,address,memo) VALUES(1,'BSC',?,'')",ADDRESS);
        assertDoesNotThrow(()->tx.executeWithoutResult(status->guard.assertAllowed(1,eth,recipient(ADDRESS.toLowerCase(Locale.ROOT)))));
        var error=assertThrows(BusinessException.class,()->tx.executeWithoutResult(status->guard.assertAllowed(2,eth,recipient(ADDRESS.toLowerCase(Locale.ROOT)))));
        assertEquals("server.compute.v15.addressAlreadyBound",error.getMessage());
        assertEquals(ComputeWithdrawalAddressGuard.key("EVM","ETH",ADDRESS,""),ComputeWithdrawalAddressGuard.key("EVM","BSC",ADDRESS.toLowerCase(Locale.ROOT),"伪造备注"));
    }

    @Test void uniquenessToggleDoesNotPermitPlatformDepositAddress() {
        jdbc.update("INSERT INTO system_setting(group_name,setting_key,setting_value) VALUES('compute','withdrawalAddressUnique','false')");
        jdbc.update("INSERT INTO compute_address(member_id,network,address,memo) VALUES(1,'BSC',?,'')",ADDRESS);
        assertDoesNotThrow(()->tx.executeWithoutResult(status->guard.assertAllowed(2,eth,recipient(ADDRESS))));
        jdbc.update("INSERT INTO compute_mcag_address VALUES(?,'BSC')",ADDRESS.toLowerCase(Locale.ROOT));
        var error=assertThrows(BusinessException.class,()->tx.executeWithoutResult(status->guard.assertAllowed(2,eth,recipient(ADDRESS))));
        assertEquals("server.compute.v15.platformDepositAddress",error.getMessage());
    }

    @Test void remotePlatformAddressAndUnknownResponseFailClosed() {
        config.setEnabled(true);var mappings=mock(ComputeMcagChannelService.class);
        var mapping=new ComputeMcagProperties.Channel();mapping.setNetworkId("ETHEREUM_MAINNET");
        when(mappings.requireChannel("USDT","ETH")).thenReturn(mapping);
        @SuppressWarnings("unchecked") ObjectProvider<ComputeMcagClient> provider=mock(ObjectProvider.class);
        var client=mock(ComputeMcagClient.class);when(provider.getIfAvailable()).thenReturn(client);
        guard=new ComputeWithdrawalAddressGuard(data,config,provider,mappings);
        var request=Map.<String,Object>of("network_id","ETHEREUM_MAINNET","address",ADDRESS);
        var check=new ComputeWithdrawalAddressGuard.Check(1,eth,recipient(ADDRESS));
        mapping.setActive(false);
        assertEquals("server.compute.unsupportedCurrency",assertThrows(BusinessException.class,()->guard.verifyRemote(check)).getMessage());
        verifyNoInteractions(client);
        mapping.setActive(true);
        when(client.request("customer-addresses/query",request)).thenReturn(Map.of("registered",true));
        assertEquals("server.compute.v15.platformDepositAddress",assertThrows(BusinessException.class,()->guard.verifyRemote(check)).getMessage());
        when(client.request("customer-addresses/query",request)).thenReturn(Map.of());
        assertEquals("server.compute.payment.invalidResponse",assertThrows(BusinessException.class,()->guard.verifyRemote(check)).getMessage());
        verify(client,times(2)).request("customer-addresses/query",request);
        when(client.request("customer-addresses/query",request)).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return Map.of("registered",false);
        });
        var proof=guard.verifyRemote(check);
        assertDoesNotThrow(()->tx.executeWithoutResult(s->guard.assertAllowed(1,eth,recipient(ADDRESS),proof)));
        assertThrows(BusinessException.class,()->tx.executeWithoutResult(s->guard.assertAllowed(2,eth,recipient(ADDRESS),proof)));
        assertThrows(BusinessException.class,()->tx.executeWithoutResult(s->guard.assertAllowed(1,eth,recipient(ADDRESS))));
        mapping.setMappingVersion(2);
        assertEquals("server.compute.recipientChanged",assertThrows(BusinessException.class,()->tx.executeWithoutResult(s->guard.assertAllowed(1,eth,recipient(ADDRESS),proof))).getMessage());
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->guard.verifyRemote(check)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_address_guard",Integer.class));
    }
    @Test void concurrentDifferentUsersCannotBothClaimTheSameAddress() throws Exception {
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures=new ArrayList<>();
            for(long member:List.of(1L,2L))futures.add(pool.submit(()->{
                start.await();try {tx.executeWithoutResult(status->{String key=guard.assertAllowed(member,eth,recipient(ADDRESS));
                    jdbc.update("INSERT INTO compute_address(member_id,network,address,memo,address_key) VALUES(?,'ETH',?,'',?)",member,ADDRESS,key);});return true;
                }catch(BusinessException rejected){assertEquals("server.compute.v15.addressAlreadyBound",rejected.getMessage());return false;}
            }));
            start.countDown();int accepted=0;for(var future:futures)if(future.get(10,TimeUnit.SECONDS))accepted++;
            assertEquals(1,accepted);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_address",Integer.class));
        } finally {pool.shutdownNow();}
    }

    @Test void pendingWithdrawalKeepsAddressOwnership() {
        jdbc.update("INSERT INTO compute_withdrawal VALUES(1,?,'BSC','PROCESSING')",ADDRESS);
        assertEquals("server.compute.v15.addressInUse",assertThrows(BusinessException.class,()->guard.assertRemovable(fields("memberId",1,"network","ETH","address",ADDRESS.toLowerCase(Locale.ROOT)))).getMessage());
        jdbc.update("UPDATE compute_withdrawal SET status='APPROVED'");
        assertDoesNotThrow(()->guard.assertRemovable(fields("memberId",1,"network","ETH","address",ADDRESS)));
    }

    @ParameterizedTest
    @ValueSource(strings={"PENDING","PROCESSING"})
    void disabledAddressWithUnfinishedWithdrawalCannotTransferAcrossEvmNetworks(String status) {
        jdbc.update("INSERT INTO system_setting(group_name,setting_key,setting_value) VALUES('compute','withdrawalAddressUnique','false')");
        jdbc.update("INSERT INTO compute_address(member_id,network,address,memo,enabled) VALUES(1,'BSC',?,'',0)",ADDRESS);
        jdbc.update("INSERT INTO compute_withdrawal VALUES(1,?,'BSC',?)",ADDRESS,status);
        // 大小写、EVM 网络以及关闭普通重复绑定限制，都不能让未完成提款的目的地转给他人。
        assertThrows(BusinessException.class,()->tx.executeWithoutResult(s->guard.assertAllowed(2,eth,recipient(ADDRESS.toLowerCase(Locale.ROOT)))));
        assertDoesNotThrow(()->tx.executeWithoutResult(s->guard.assertAllowed(1,eth,recipient(ADDRESS.toLowerCase(Locale.ROOT)))));
        jdbc.update("UPDATE compute_withdrawal SET status='APPROVED'");
        assertDoesNotThrow(()->tx.executeWithoutResult(s->guard.assertAllowed(2,eth,recipient(ADDRESS.toLowerCase(Locale.ROOT)))));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM compute_address",Integer.class));
        assertEquals("APPROVED",jdbc.queryForObject("SELECT status FROM compute_withdrawal",String.class));
    }
}
