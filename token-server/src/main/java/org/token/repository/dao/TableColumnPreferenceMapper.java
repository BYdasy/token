package org.token.repository.dao;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TableColumnPreferenceMapper {
    @Select("SELECT columns_json FROM compute_admin_table_columns WHERE admin_uid=#{uid} AND page_key=#{pageKey}")
    List<String> read(@Param("uid") int uid,@Param("pageKey") String pageKey);
    @Insert("INSERT INTO compute_admin_table_columns(admin_uid,page_key,columns_json,updated_at) VALUES(#{uid},#{pageKey},#{json},#{now}) ON DUPLICATE KEY UPDATE columns_json=VALUES(columns_json),updated_at=VALUES(updated_at)")
    int save(@Param("uid") int uid,@Param("pageKey") String pageKey,@Param("json") String json,@Param("now") LocalDateTime now);
}
