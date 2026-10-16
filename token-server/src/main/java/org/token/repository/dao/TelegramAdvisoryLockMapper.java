package org.token.repository.dao;

import org.apache.ibatis.annotations.*;

/** 必须由专用连接仓储使用，不能分开从连接池调用获取和释放。 */
public interface TelegramAdvisoryLockMapper {
    @Select("SELECT GET_LOCK(#{key},0)") @Options(timeout=3)
    Integer acquire(String key);
    @Select("SELECT RELEASE_LOCK(#{key})") @Options(timeout=3)
    Integer release(String key);
}
