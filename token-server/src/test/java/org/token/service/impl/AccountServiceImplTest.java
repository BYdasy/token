package org.token.service.impl;

import org.token.repository.dao.AccountMapper;
import org.token.repository.dao.AccountTotpMapper;
import org.token.repository.dao.RoleMapper;
import org.token.repository.dao.SystemNotificationMapper;
import org.token.model.entity.Account;
import org.junit.jupiter.api.Test;
import org.token.exception.BusinessException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccountServiceImplTest {

    @Test
    void skipsUpdateWhenOnlyUidIsPresent() {
        AccountMapper accountMapper = mock(AccountMapper.class);
        AccountServiceImpl service = new AccountServiceImpl();
        ReflectionTestUtils.setField(service, "repository", new org.token.repository.system.AccountRepository(accountMapper));

        Account account = new Account();
        account.setUid(1);
        service.updateAccount(account);

        verifyNoInteractions(accountMapper);
    }

    @Test
    void defaultAdminCannotBeDeleted() {
        AccountMapper accountMapper = mock(AccountMapper.class);
        AccountServiceImpl service = new AccountServiceImpl();
        ReflectionTestUtils.setField(service, "repository", new org.token.repository.system.AccountRepository(accountMapper));
        Account admin = new Account();
        admin.setUid(1);
        admin.setUsername("admin");
        when(accountMapper.selectById(1)).thenReturn(admin);

        assertThrows(BusinessException.class, () -> service.deleteAccount(1));
    }
}
