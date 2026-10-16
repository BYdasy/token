package org.token.service;

import org.token.model.entity.Account;

public interface AccountService {

    void createAccount(Account account);

    Account findByUsername(String username);

    Account findByUid(Integer uid);

    void updateAccount(Account account);

    void deleteAccount(Integer uid);

    int countByRoleId(Integer roleId);
}
