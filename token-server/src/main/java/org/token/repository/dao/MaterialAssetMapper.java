package org.token.repository.dao;

import org.apache.ibatis.annotations.*;
import org.token.model.entity.MaterialAsset;
import java.util.List;
import java.util.Map;

@Mapper
public interface MaterialAssetMapper {
    String COLUMNS = "id,name,media_type AS mediaType,mime_type AS mimeType,file_name AS fileName,file_url AS fileUrl,file_size AS fileSize,width,height,duration_seconds AS durationSeconds,category,tags,remark,created_by AS createdBy,is_del AS isDel,created_at AS createdAt,updated_at AS updatedAt";
    String FILTERS = " WHERE is_del=0"
            + "<if test='filters.name != null'> AND name LIKE CONCAT('%',#{filters.name},'%')</if>"
            + "<if test='filters.tags != null'> AND tags LIKE CONCAT('%',#{filters.tags},'%')</if>"
            + "<if test='filters.fileName != null'> AND file_name LIKE CONCAT('%',#{filters.fileName},'%')</if>"
            + "<if test='filters.mediaType != null'> AND media_type=#{filters.mediaType}</if>"
            + "<if test='filters.category != null'> AND category=#{filters.category}</if>"
            + "<if test='filters.createdFrom != null'> AND created_at &gt;= #{filters.createdFrom}</if>"
            + "<if test='filters.createdTo != null'> AND created_at &lt;= #{filters.createdTo}</if>";

    @Select("<script>SELECT " + COLUMNS + " FROM material_asset" + FILTERS + " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<MaterialAsset> findPage(@Param("filters") Map<String, Object> filters, @Param("limit") long limit, @Param("offset") long offset);

    @Select("<script>SELECT COUNT(*) FROM material_asset" + FILTERS + "</script>")
    long count(@Param("filters") Map<String, Object> filters);

    @Select("<script>SELECT category FROM material_asset WHERE is_del=0 AND category IS NOT NULL AND category!=''<if test='imagesOnly'> AND media_type='IMAGE'</if> GROUP BY category ORDER BY category ASC</script>")
    List<String> categories(@Param("imagesOnly") boolean imagesOnly);

    @Select("SELECT " + COLUMNS + " FROM material_asset WHERE id=#{id} AND is_del=0")
    MaterialAsset selectById(@Param("id") Long id);

    @Insert("INSERT INTO material_asset(name,media_type,mime_type,file_name,file_url,file_size,width,height,duration_seconds,category,tags,remark,created_by,is_del,created_at,updated_at) VALUES(#{name},#{mediaType},#{mimeType},#{fileName},#{fileUrl},#{fileSize},#{width},#{height},#{durationSeconds},#{category},#{tags},#{remark},#{createdBy},#{isDel},#{createdAt},#{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(MaterialAsset asset);

    @Update("<script>UPDATE material_asset <set><if test='name != null'>name=#{name},</if><if test='category != null'>category=#{category},</if><if test='tags != null'>tags=#{tags},</if><if test='remark != null'>remark=#{remark},</if>updated_at=#{updatedAt}</set> WHERE id=#{id} AND is_del=0</script>")
    int updateById(MaterialAsset asset);

    @Update("UPDATE material_asset SET is_del=1 WHERE id=#{id} AND is_del=0")
    int deleteById(@Param("id") Long id);
}
