package org.token.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.token.model.entity.AccountProfile;
import org.token.model.request.QueryParams;
import org.token.model.response.AccountView;

public interface AccountProfileService {
    boolean updateById(AccountProfile profile);


    IPage<AccountView> listAccounts(QueryParams params);

    AccountProfile findProfile(Integer uid, String username);

    void updateProfile(String username, Integer uid, AccountProfile profile);
}
