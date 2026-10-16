package org.token.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.token.model.entity.AccountProfile;
import org.token.model.request.QueryParams;
import org.token.model.response.AccountView;
import org.token.exception.BusinessException;
import org.token.service.AccountProfileService;
import org.token.tools.media.SundryUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AccountProfileServiceImpl
        implements AccountProfileService {
    private final org.token.repository.system.AccountProfileRepository repository;

    @org.springframework.beans.factory.annotation.Autowired
    public AccountProfileServiceImpl(org.token.repository.system.AccountProfileRepository repository) {
        this.repository=repository;
    }

    public AccountProfileServiceImpl() { this(null); }

    @Override
    @Transactional(readOnly = true)
    public AccountProfile findProfile(Integer uid, String username) {
        boolean hasUid = uid != null && uid > 0;
        boolean hasUsername = StringUtils.hasText(username);
        if (!hasUid && !hasUsername) {
            return null;
        }
        return repository.findProfile(hasUid ? uid : null, hasUsername ? trim(username) : null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProfile(String username, Integer uid, AccountProfile profile) {
        if (profile == null) {
            throw new BusinessException("server.user.detailRequired");
        }
        boolean hasUid = uid != null && uid > 0;
        boolean hasUsername = StringUtils.hasText(username);
        if (!hasUid && !hasUsername) {
            throw new BusinessException("server.user.updateConditionRequired");
        }

        profile.setUpdateTime(SundryUtils.getTime());
        if (!repository.updateProfile(hasUid ? uid : null, hasUsername ? trim(username) : null, profile)) {
            throw new BusinessException("server.user.updateFailed");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public IPage<AccountView> listAccounts(QueryParams params) {
        long current = params != null && params.getPage() != null && params.getPage().getCurrent() != null
                ? Math.max(1, params.getPage().getCurrent()) : 1;
        long size = params != null && params.getPage() != null && params.getPage().getSize() != null
                ? Math.min(200, Math.max(1, params.getPage().getSize())) : 20;
        return repository.findAccounts(current,size,params);
    }

    private String trim(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
    @Override
    public boolean updateById(AccountProfile profile) { return repository.updateById(profile); }
}
