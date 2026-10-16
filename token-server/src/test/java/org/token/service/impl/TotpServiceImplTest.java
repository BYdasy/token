package org.token.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import org.token.model.entity.AccountTotp;
import org.token.service.auth.AdminLoginSessionService;

class TotpServiceImplTest {

    @Test
    void storageFailureCannotDowngradeTwoFactorToDisabled() {
        var repository=mock(org.token.repository.system.AccountTotpRepository.class);
        var service=new TotpServiceImpl("Token","unit-test-encryption-key",null,repository);
        when(repository.getById(5)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"));
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->service.status(5));
    }

    @Test
    void missingSecurityRecordCannotDowngradeTwoFactorToDisabled() {
        var repository=mock(org.token.repository.system.AccountTotpRepository.class);
        var service=new TotpServiceImpl("Token","unit-test-encryption-key",null,repository);
        assertThrows(IllegalStateException.class,()->service.status(5));
    }

    @Test
    void repeatedConfirmationCannotClearTheConsumedCounter() {
        var repository=mock(org.token.repository.system.AccountTotpRepository.class);
        var service=new TotpServiceImpl("Token","unit-test-encryption-key",null,repository);
        var record=new AccountTotp();record.setUid(5);record.setEnabled(1);record.setSecretCiphertext("encrypted");
        record.setLastUsedCounter(123L);
        when(repository.getById(5)).thenReturn(record);
        assertThrows(org.token.exception.BusinessException.class,()->service.confirm(5,"123456"));
        assertEquals(123L,record.getLastUsedCounter());
        verify(repository,never()).updateById(any());
    }

    @Test
    void resettingEvenAbsentBindingRevokesExistingAndLegacySessions() {
        var sessions = mock(AdminLoginSessionService.class);
        TotpServiceImpl service = new TotpServiceImpl("EXP", "unit-test-encryption-key", sessions);
        var repository=mock(org.token.repository.system.AccountTotpRepository.class);
        ReflectionTestUtils.setField(service,"repository",repository);
        doReturn(null).when(repository).getById(5);
        service.reset(5);
        verify(sessions).revokeAll(5);
    }

    @Test
    void requirementChangeRevokesSessionsButSameValueDoesNot() {
        var sessions = mock(AdminLoginSessionService.class);
        TotpServiceImpl service = new TotpServiceImpl("EXP", "unit-test-encryption-key", sessions);
        var repository=mock(org.token.repository.system.AccountTotpRepository.class);
        ReflectionTestUtils.setField(service,"repository",repository);
        AccountTotp record = new AccountTotp(); record.setUid(5); record.setRequired(0);
        doReturn(record).when(repository).getById(5);
        doReturn(true).when(repository).saveOrUpdate(record);
        service.setRequired(5, true); verify(sessions).revokeAll(5);
        clearInvocations(sessions);
        service.setRequired(5, true); verifyNoInteractions(sessions);
    }

    @Test
    void confirmingNewSecretRevokesSessionsAuthenticatedBeforeBinding() {
        var sessions = mock(AdminLoginSessionService.class);
        TotpServiceImpl service = new TotpServiceImpl("EXP", "unit-test-encryption-key", sessions);
        var repository=mock(org.token.repository.system.AccountTotpRepository.class);
        ReflectionTestUtils.setField(service,"repository",repository);
        byte[] rawSecret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        String secret = ReflectionTestUtils.invokeMethod(service, "base32Encode", rawSecret);
        AccountTotp record = new AccountTotp(); record.setUid(5); record.setEnabled(0);
        record.setSecretCiphertext(ReflectionTestUtils.invokeMethod(service, "encrypt", secret));
        doReturn(record).when(repository).getById(5);
        doReturn(true).when(repository).updateById(record);
        String code = ReflectionTestUtils.invokeMethod(service, "generateCode", rawSecret, System.currentTimeMillis() / 30000L);
        service.confirm(5, code);
        verify(sessions).revokeAll(5);
        assertEquals(1, record.getEnabled());
    }

    @Test
    void followsRfc6238Sha1SixDigitCode() {
        TotpServiceImpl service = new TotpServiceImpl("EXP", "unit-test-encryption-key");
        String code = ReflectionTestUtils.invokeMethod(
                service,
                "generateCode",
                "12345678901234567890".getBytes(StandardCharsets.US_ASCII),
                1L
        );
        assertEquals("287082", code);
    }
}
