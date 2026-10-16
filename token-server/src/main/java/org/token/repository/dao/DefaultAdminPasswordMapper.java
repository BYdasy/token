package org.token.repository.dao;

import org.apache.ibatis.annotations.*;
import org.token.model.entity.Account;
import java.util.List;

/** 独立维护工具的固定 SQL；不启动应用或调度器。 */
public interface DefaultAdminPasswordMapper {
    @Select("SELECT DATABASE()") @Options(timeout=15)
    String database();
    @Select("SELECT uid,username FROM ad_master WHERE LOWER(username)=#{username} FOR UPDATE") @Options(timeout=15)
    List<Account> findForUpdate(String username);
    @Update("UPDATE ad_master SET password=#{password} WHERE uid=#{uid} AND username=#{username}") @Options(timeout=15)
    int update(@Param("password") String password,@Param("uid") int uid,@Param("username") String username);
}
