package org.token.repository.dao.compute;

import java.util.*;
import java.time.*;
import java.math.*;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** ComputeCompanyFileService 的参数化数据库语句。 */
@Mapper
public interface ComputeCompanyFileMapper {

    @Select("SELECT id,file_name,file_size AS size,resource_type,external_url,mime_type,homepage,names_json,version FROM compute_company_file WHERE enabled=1 ORDER BY sort_no,id")
    List<Map<String, Object>> userListCompanyFileRows(@Param("args") Object[] args);

    @Select("SELECT resource_type,names_json,external_url,mime_type,homepage FROM compute_company_file WHERE id=#{args[0]} AND version=#{args[1]} FOR UPDATE")
    List<Map<String, Object>> updateCompanyFileRows(@Param("args") Object[] args);

    @Update("UPDATE compute_company_file SET file_name=#{args[0]},external_url=#{args[1]},names_json=#{args[2]},enabled=#{args[3]},sort_no=#{args[4]},version=version+1,updated_by=#{args[5]},updated_at=#{args[6]},homepage=#{args[9]},mime_type=#{args[10]} WHERE id=#{args[7]} AND version=#{args[8]}")
    int updateCompanyFileWrite(@Param("args") Object[] args);

    @Select("SELECT id,file_name,file_size AS size,resource_type,external_url,mime_type,homepage,names_json,enabled,sort_no,version,created_at,updated_at FROM compute_company_file WHERE id=#{id}")
    List<Map<String, Object>> adminFile(@Param("id") long id);

    @Select("<script>SELECT file_name,stored_name,file_size FROM compute_company_file WHERE id=#{id} AND resource_type='PDF'<if test='!admin'> AND enabled=1</if></script>")
    List<Map<String, Object>> pdfFile(@Param("id") long id, @Param("admin") boolean admin);

    @Select("SELECT id,file_name,file_size AS size,resource_type,external_url,mime_type,homepage,names_json,version FROM compute_company_file WHERE enabled=1 AND homepage=1 AND resource_type IN ('VIDEO','VIDEO_LINK') ORDER BY sort_no,id LIMIT 24")
    List<Map<String, Object>> homepageVideos();

    @Select("<script>SELECT file_name,stored_name,file_size,mime_type FROM compute_company_file WHERE id=#{id} AND resource_type='VIDEO'<if test='!admin'> AND enabled=1</if><if test='homepage'> AND homepage=1</if></script>")
    List<Map<String, Object>> videoFile(@Param("id") long id, @Param("admin") boolean admin, @Param("homepage") boolean homepage);

    @Select("<script>SELECT COUNT(*) FROM compute_company_file WHERE 1=1<if test='keyword != null'> AND file_name LIKE #{keyword} ESCAPE '!'</if><if test='enabled != null'> AND enabled=#{enabled}</if></script>")
    Long countAdminFiles(@Param("keyword") String keyword, @Param("enabled") Integer enabled);

    @Select("<script>SELECT id,file_name,file_size AS size,resource_type,external_url,mime_type,homepage,names_json,enabled,sort_no,version,created_at,updated_at FROM compute_company_file WHERE 1=1<if test='keyword != null'> AND file_name LIKE #{keyword} ESCAPE '!'</if><if test='enabled != null'> AND enabled=#{enabled}</if> ORDER BY sort_no,id LIMIT #{limit} OFFSET #{offset}</script>")
    List<Map<String, Object>> adminFiles(@Param("keyword") String keyword, @Param("enabled") Integer enabled, @Param("limit") int limit, @Param("offset") long offset);
}
