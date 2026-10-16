package org.token.service.compute;

import org.token.repository.compute.ComputeData;
import org.token.repository.compute.ComputeBusinessRepository;

import org.token.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ComputeBusinessSafetyTest {
    private ComputeBusinessService business(ComputeData data,ComputeBusinessRepository repository) {
        return new ComputeBusinessService(data,mock(ComputeCurrencyService.class),null,null,null,null,null,null,null,null,repository);
    }
    @Test
    void rejectsNegativeZeroOverpreciseAndOverflowAmounts() {
        for (String value : List.of("0", "-1", "0.000000001", "1000000000000000000", "NaN"))
            assertThrows(BusinessException.class, () -> ComputeData.amount(value));
        assertEquals("0.00000001", ComputeData.amount("0.00000001").toPlainString());
    }

    @Test
    void depositCannotInjectMoneyIntoEarningsWallet() {
        ComputeData data = mock(ComputeData.class);
        ComputeBusinessRepository repository=mock(ComputeBusinessRepository.class);
        when(repository.lockMemberMemberRow(42L)).thenReturn(Map.of("id",42L,"level",1));
        when(data.json(any())).thenReturn("{}");
        when(repository.requestResultRequestRows(42L,"deposit-0001")).thenReturn(List.of());
        when(repository.activeCurrencyCount("USDT","TRC20")).thenReturn(1);
        ComputeBusinessService service = business(data,repository);
        BusinessException exception = assertThrows(BusinessException.class, () -> service.deposit(42L,
                Map.of("requestId", "deposit-0001", "walletType", "EARNINGS", "currency", "USDT", "network", "TRC20", "amount", "100")));
        assertEquals("server.compute.depositWalletInvalid", exception.getMessage());
        verify(data, never()).insert(eq("compute_deposit"), anyMap());
    }

    @Test
    void repeatReviewFailsBeforeBalanceMutation() {
        ComputeData data = mock(ComputeData.class);
        ComputeBusinessRepository repository=mock(ComputeBusinessRepository.class);
        when(repository.reviewWithdrawalWithdrawalRow(7L)).thenReturn(Map.of("memberId",42L));
        when(repository.reviewWithdrawalMemberRow(42L)).thenReturn(Map.of("id",42L));
        when(repository.reviewWithdrawalWithdrawalRow2(7L)).thenReturn(Map.of("status","APPROVED"));
        BusinessException exception = assertThrows(BusinessException.class, () -> business(data,repository).reviewWithdrawal(7L, true, "", 1));
        assertEquals("server.compute.alreadyReviewed", exception.getMessage());
        verify(repository).reviewWithdrawalWithdrawalRow(7L);
        verify(repository).reviewWithdrawalMemberRow(42L);
        verify(repository).reviewWithdrawalWithdrawalRow2(7L);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(data);
    }
}
