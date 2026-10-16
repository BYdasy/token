package org.token.service.compute;

import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.repository.compute.ComputeFinanceSnapshotRepository;
import org.token.repository.dao.compute.ComputeDepositAccountingMapper;
import org.token.repository.dao.compute.ComputeUsdAccountingMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.token.repository.compute.ComputeData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 核查接入放宽原币精度后，固定 USD 快照、原币财务快照和旧输入规则仍互不混用。 */
class ComputeDepositPrecisionAccountingTest {
    private static final String NATIVE = "11.696130139510566704";

    private Map<String,Object> prepared() {
        return fields("id",1,"memberId",580,"orderNo","DP_PRECISION","status","PENDING","recordSource","MCAG",
                "currency","USDT","amount",NATIVE,"accountingVersion",2,"accountingCurrency","USD",
                "accountingRate","1","accountingAmount","11.69613013","accountingStatus","PENDING","accountingQuoteJson","{}");
    }

    @Test void storedUsdQuoteValidatesFullNativeValueWithoutRequiringEightNativeDecimals() {
        var data=mock(ComputeData.class);
        var deposits=mock(ComputeDepositAccountingMapper.class);
        var rates=mock(ComputeUsdAccountingMapper.class);
        when(data.mapper(ComputeDepositAccountingMapper.class)).thenReturn(deposits);
        when(data.mapper(ComputeUsdAccountingMapper.class)).thenReturn(rates);
        var record=prepared();
        var accounting=new ComputeDepositAccounting(data);
        assertSame(record,accounting.prepare(record).orElseThrow());
        assertEquals(NATIVE,record.get("amount"));
        verifyNoInteractions(deposits,rates);
        // 已固定金额差一最小记账单位也拒绝；不能借原币高精度重算已经确认的快照。
        record.put("accountingAmount","11.69613014");
        assertThrows(BusinessException.class,()->accounting.prepare(record));
    }

    @Test void immutableFinanceSnapshotReceivesExactNativeGrossAndNetWithFixedUsd() {
        var data=mock(ComputeData.class);
        var repository=mock(ComputeFinanceSnapshotRepository.class);
        when(repository.captureFinanceSnapshotRows("DEPOSIT",1L)).thenReturn(List.of(),List.of(Map.of("businessId",1L)));
        when(data.jsonMap((Object) "{}")).thenReturn(Map.of("provider","FIXED"));
        var rates=mock(ComputeExchangeRateService.class);
        var service=new ComputeFinanceSnapshotService(data,rates,repository);
        var record=prepared();
        var now=LocalDateTime.of(2026,10,7,0,25,7);
        service.capture("DEPOSIT",record,now);
        var values=ArgumentCaptor.forClass(Object[].class);
        verify(repository).captureFinanceSnapshotWrite(values.capture());
        Object[] args=values.getValue();
        assertEquals(new BigDecimal(NATIVE),args[5]);
        assertEquals(BigDecimal.ZERO,args[6]);
        assertEquals(new BigDecimal(NATIVE),args[7]);
        assertEquals(new BigDecimal("11.69613013"),args[9]);
        assertEquals(BigDecimal.ZERO,args[10]);
        assertEquals(new BigDecimal("11.69613013"),args[11]);
        record.put("accountingAmount","999");
        service.capture("DEPOSIT",record,now.plusSeconds(1));
        verify(repository,times(1)).captureFinanceSnapshotWrite(any(Object[].class));
        verifyNoInteractions(rates);
    }

    @Test void apiUsesDecimalTextWhileManualAndWithdrawalInputStillRejectMoreThanEightPlaces() {
        var response=ComputeData.normalizeRow(Map.of("amount",new BigDecimal(NATIVE),"accounting_amount",new BigDecimal("11.69613013")));
        assertEquals(NATIVE,response.get("amount"));
        assertEquals("11.69613013",response.get("accountingAmount"));
        assertThrows(BusinessException.class,()->amount(NATIVE));
        assertEquals(new BigDecimal("11.69613013"),ComputeUsdAccounting.usdAmount(new BigDecimal(NATIVE),BigDecimal.ONE));
        assertEquals(new BigDecimal("11.69613014"),ComputeUsdAccounting.withdrawalUsd(new BigDecimal(NATIVE),BigDecimal.ONE));
    }

    @Test void highRateAssetConvertsBeforeUsdRoundingInsteadOfLosingSmallNativeValue() {
        BigDecimal nativeAmount=new BigDecimal("0.000000009999999999"),rate=new BigDecimal("60000");
        assertEquals(new BigDecimal("0.00059999"),ComputeUsdAccounting.usdAmount(nativeAmount,rate));
        assertEquals("0.000000009999999999",nativeAmount.toPlainString());
    }
}
