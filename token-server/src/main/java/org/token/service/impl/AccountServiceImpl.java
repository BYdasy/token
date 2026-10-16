package org.token.service.impl;

import org.token.model.entity.Account;
import org.token.model.entity.Role;
import org.token.exception.BusinessException;
import org.token.service.AccountService;
import org.token.util.SystemDataProtection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AccountServiceImpl implements AccountService {
    private final org.token.repository.system.AccountRepository repository;
    private final org.token.repository.system.RoleRepository roleRepository;

    @org.springframework.beans.factory.annotation.Autowired
    public AccountServiceImpl(org.token.repository.system.AccountRepository repository,
                              org.token.repository.system.RoleRepository roleRepository) {
        this.repository=repository;
        this.roleRepository=roleRepository;
    }

    public AccountServiceImpl() { this(null,null); }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createAccount(Account account) {
        if (account == null || !StringUtils.hasText(account.getUsername())) {
            throw new BusinessException("server.username.required");
        }
        if (!StringUtils.hasText(account.getPassword())) {
            throw new BusinessException("server.password.required");
        }
        account.setUsername(account.getUsername().trim());
        account.setState(account.getState() == null ? 1 : account.getState());
        if (!repository.save(account)) {
            throw new BusinessException("server.user.createFailed");
        }
        repository.createSecurity(account.getUid());
    }

    @Override
    @Transactional(readOnly = true)
    public Account findByUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        return repository.findByUsername(username.trim());
    }

    @Override
    @Transactional(readOnly = true)
    public Account findByUid(Integer uid) {
        return uid == null || uid <= 0 ? null : repository.getById(uid);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateAccount(Account account) {
        if (account == null || account.getUid() == null) {
            throw new BusinessException("server.user.uidRequired");
        }
        if (!hasMutableValues(account)) {
            return;
        }
        Account current = repository.getById(account.getUid());
        if (current != null
                && SystemDataProtection.isDefaultAdmin(current.getUid(), current.getUsername())) {
            if (Integer.valueOf(0).equals(account.getState())) {
                throw new BusinessException("server.account.defaultProtected");
            }
            if (account.getUsername() != null
                    && (!StringUtils.hasText(current.getUsername())
                    || !current.getUsername().equalsIgnoreCase(account.getUsername().trim()))) {
                throw new BusinessException("server.account.defaultProtected");
            }
            if (account.getRole() != null) {
                Role targetRole = roleRepository.getById(account.getRole());
                if (targetRole == null || !Integer.valueOf(1).equals(targetRole.getIsSuper())) {
                    throw new BusinessException("server.account.defaultProtected");
                }
            }
        }
        if (!repository.updateById(account)) {
            throw new BusinessException("server.user.updateFailed");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAccount(Integer uid) {
        if (uid == null || uid <= 0) {
            throw new BusinessException("server.user.uidRequired");
        }
        Account account = repository.getById(uid);
        if (account != null && SystemDataProtection.isDefaultAdmin(account.getUid(), account.getUsername())) {
            throw new BusinessException("server.account.defaultProtected");
        }
        // 账号删除与安全状态、通知归属清理处于同一事务。
        repository.deleteSecurityAndNotificationOwnership(uid);
        if (!repository.removeById(uid)) {
            throw new BusinessException("server.user.deleteFailed");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public int countByRoleId(Integer roleId) {
        if (roleId == null || roleId <= 0) {
            return 0;
        }
        return repository.countByRoleId(roleId);
    }

    private boolean hasMutableValues(Account account) {
        return account.getPassword() != null
                || account.getUsername() != null
                || account.getRole() != null
                || account.getState() != null
                || account.getAvatar() != null
                || account.getTag() != null
                || account.getLocale() != null
                || account.getTimeZone() != null;
    }
}
