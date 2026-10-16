package org.token.repository.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.token.model.request.QueryParams;
import org.token.repository.dao.provider.SystemPageSql;
import org.token.model.entity.AccountTotp;

@Mapper
public interface AccountTotpMapper extends BaseMapper<AccountTotp> {

    @Select("SELECT uid FROM ad_master WHERE uid=#{uid} FOR UPDATE")
    Integer lockAccount(Integer uid);

    @Insert("INSERT INTO admin_security(admin_uid,updated_at) VALUES(#{uid},#{now}) ON DUPLICATE KEY UPDATE admin_uid=VALUES(admin_uid)")
    int ensureSecurity(@Param("uid") Integer uid,@Param("now") java.time.LocalDateTime now);

    @Update("UPDATE admin_security SET totp_secret_ciphertext=#{ciphertext},totp_enabled=0,totp_last_used_counter=NULL,totp_confirmed_at=NULL,updated_at=#{now} WHERE admin_uid=#{uid}")
    int resetSecret(@Param("uid") Integer uid,@Param("ciphertext") String ciphertext,@Param("now") java.time.LocalDateTime now);
    @Update("UPDATE admin_security SET totp_last_used_counter=#{counter},updated_at=#{now} WHERE admin_uid=#{uid} AND totp_enabled=1 AND (totp_last_used_counter IS NULL OR totp_last_used_counter < #{counter})")
    int unusedCounter(@Param("uid") Integer uid,@Param("counter") long counter,@Param("now") java.time.LocalDateTime now);

}
