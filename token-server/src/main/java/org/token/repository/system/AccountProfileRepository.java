package org.token.repository.system;

import org.token.repository.dao.AccountProfileMapper;
import org.token.repository.dao.AccountMapper;
import org.token.model.entity.AccountProfile;
import org.token.model.request.QueryParams;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import org.token.model.entity.Account;
import org.token.model.entity.AccountTotp;
import org.token.model.entity.Role;
import org.token.model.request.FilterParams;
import org.token.model.response.AccountView;
import org.springframework.stereotype.Repository;
import java.util.*;

/** AccountProfile 的数据库访问；不持有业务事务或跨请求缓存。 */
@Repository
public class AccountProfileRepository extends EntityRepository<AccountProfile, AccountProfileMapper> {
    private final AccountMapper accounts;
    public AccountProfileRepository(AccountProfileMapper mapper, AccountMapper accounts) {
        super(mapper);
        this.accounts = accounts;
    }

    public AccountProfile findProfile(Integer uid,String username) {
        return mapper.selectOne(new LambdaQueryWrapper<AccountProfile>()
                .eq(uid!=null,AccountProfile::getUid,uid)
                .eq(username!=null,AccountProfile::getUsername,username).last("LIMIT 1"));
    }
    public boolean updateProfile(Integer uid,String username,AccountProfile profile) {
        return mapper.update(profile,new LambdaUpdateWrapper<AccountProfile>()
                .eq(uid!=null,AccountProfile::getUid,uid)
                .eq(username!=null,AccountProfile::getUsername,username))>0;
    }
    public Page<AccountView> findAccounts(long current,long size,QueryParams query) {
        MPJLambdaWrapper<Account> wrapper=new MPJLambdaWrapper<>();
        wrapper.select(Account::getUid,Account::getUsername,Account::getAvatar,Account::getTag,
                        Account::getLocale,Account::getTimeZone,Account::getState,Account::getRole)
                .selectAs(Account::getRemark,AccountView::getRemarks)
                .selectAs(Account::getProfileUpdatedAt,AccountView::getUpdateTime)
                .selectAs(Account::getLastLoginIp,AccountView::getLoginIp)
                .selectAs(Account::getCountry,AccountView::getCountryName)
                .selectAs(Role::getName,AccountView::getName)
                .selectAs(Role::getId,AccountView::getRoleId)
                .selectAs(AccountTotp::getEnabled,AccountView::getTotpBound)
                .selectAs(AccountTotp::getRequired,AccountView::getTotpRequired)
                .innerJoin(Role.class,Role::getId,Account::getRole)
                .leftJoin(AccountTotp.class,AccountTotp::getUid,Account::getUid);
        FilterParams filter=query==null?null:query.getFilterParams();
        if(filter!=null) {
            String username=trim(filter.getUserName());
            wrapper.eq(!username.isEmpty(),Account::getUsername,username)
                    .ge(!trim(filter.getStartDate()).isEmpty(),Account::getProfileUpdatedAt,filter.getStartDate())
                    .le(!trim(filter.getEndDate()).isEmpty(),Account::getProfileUpdatedAt,filter.getEndDate());
        }
        String prop=query==null||query.getSortColumn()==null?"":trim(query.getSortColumn().getProp());
        boolean asc=query==null||query.getSortColumn()==null||!"descending".equalsIgnoreCase(trim(query.getSortColumn().getOrder()));
        switch(prop) {
            case "uid" -> wrapper.orderBy(true,asc,Account::getUid);
            case "username" -> wrapper.orderBy(true,asc,Account::getUsername);
            case "roleId" -> wrapper.orderBy(true,asc,Role::getId);
            case "name" -> wrapper.orderBy(true,asc,Role::getName);
            case "countryName" -> wrapper.orderBy(true,asc,Account::getCountry);
            case "loginIp" -> wrapper.orderBy(true,asc,Account::getLastLoginIp);
            case "updateTime" -> wrapper.orderBy(true,asc,Account::getProfileUpdatedAt);
            default -> wrapper.orderByDesc(Account::getProfileUpdatedAt).orderByAsc(Account::getUid);
        }
        return accounts.selectJoinPage(new Page<>(current,size),AccountView.class,wrapper);
    }
    private static String trim(Object value) { return value==null?"":value.toString().trim(); }

}
