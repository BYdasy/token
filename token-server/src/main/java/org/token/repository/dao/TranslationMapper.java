package org.token.repository.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.*;
import java.util.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.token.model.request.QueryParams;
import org.token.repository.dao.provider.SystemPageSql;
import org.apache.ibatis.annotations.Param;
import org.token.model.entity.Translation;

/**
 * 翻译表 Mapper
 *
 * MyBatis-Plus 的 BaseMapper 已经提供：
 * insert / delete / update / selectById / selectList / selectPage 等基础方法。
 */
@Mapper
public interface TranslationMapper extends BaseMapper<Translation> {

    /**
     * 只在同模板、同 key、同语言尚不存在时插入。
     *
     * 旧表没有唯一约束，模板补齐还会在应用层按 type 加锁；这里再做一次
     * NOT EXISTS 检查，让重复执行具有幂等性并缩小并发重复窗口。
     */
    @Insert("""
            INSERT INTO translates
                (tkey, lang, tvalue, type, prop, action_time, title, is_del)
            SELECT
                #{item.tkey}, #{item.lang}, #{item.tvalue}, #{item.type},
                #{item.prop}, #{item.actionTime}, #{item.title}, #{item.isDel}
            WHERE NOT EXISTS (
                SELECT 1
                FROM translates existing
                WHERE existing.tkey = #{item.tkey}
                  AND existing.lang = #{item.lang}
                  AND existing.type = #{item.type}
            )
            """)
    int insertIfMissing(@Param("item") Translation item);

    @Select("SELECT COUNT(*) FROM translates WHERE tkey=#{key} AND lang=#{lang} AND type=#{type}")
    long countByKeyLanguageType(@Param("key") String key,@Param("lang") String lang,@Param("type") Integer type);
    @Select("SELECT * FROM translates WHERE tkey=#{key} AND lang=#{lang} AND type=#{type} LIMIT 1")
    Translation findByKeyLanguageType(@Param("key") String key,@Param("lang") String lang,@Param("type") Integer type);
    @Select("SELECT * FROM translates WHERE tkey=#{key} AND lang=#{lang} AND type=#{type} ORDER BY id DESC LIMIT 1")
    Translation findLatestByKeyLanguageType(@Param("key") String key,@Param("lang") String lang,@Param("type") Integer type);
    @Delete("DELETE FROM translates WHERE type=#{type}")
    int deleteByType(Integer type);
    @Delete("DELETE FROM translates WHERE type=#{type} AND tkey=#{key}")
    int deleteKey(@Param("key") String key,@Param("type") Integer type);
    @Select("SELECT * FROM translates WHERE type=#{type} AND tkey=#{key} ORDER BY lang ASC")
    List<Translation> findBatch(@Param("key") String key,@Param("type") Integer type);
    @Select("""
        <script>SELECT * FROM translates WHERE tkey=#{key} AND type=#{type} AND lang IN
        <foreach collection="languages" item="lang" open="(" close=")" separator=",">#{lang}</foreach> ORDER BY id DESC</script>
        """)
    List<Translation> findLanguageCandidates(@Param("key") String key,@Param("type") int type,@Param("languages") List<String> languages);
    @Select("SELECT * FROM translates WHERE type=#{type} AND lang=#{lang} AND tkey LIKE CONCAT(#{prefix},'%') ORDER BY id ASC")
    List<Translation> findBundle(@Param("type") int type,@Param("lang") String lang,@Param("prefix") String prefix);
    @Select("SELECT * FROM translates WHERE type=#{type} AND lang='zh' ORDER BY action_time DESC,id DESC")
    List<Translation> findChinese(Integer type);
    @SelectProvider(type=SystemPageSql.class,method="translations")
    Page<Translation> findPage(Page<Translation> page,@Param("query") QueryParams query,@Param("selectedType") Integer selectedType,
            @Param("global") boolean global,@Param("systemKeys") List<String> systemKeys);
    @Select("SELECT * FROM translates WHERE type=#{type} ORDER BY tkey ASC,lang ASC,id ASC")
    List<Translation> findByType(Integer type);


    @Select("SELECT * FROM translates WHERE type=#{type} ORDER BY id DESC")
    List<Translation> findByTypeLatestFirst(Integer type);


    @Select("""
        <script>SELECT * FROM translates WHERE type IN <foreach collection="types" item="type" open="(" close=")" separator=",">#{type}</foreach>
        AND tkey IN <foreach collection="keys" item="key" open="(" close=")" separator=",">#{key}</foreach>
        <if test="languages != null and !languages.isEmpty()">AND lang IN <foreach collection="languages" item="lang" open="(" close=")" separator=",">#{lang}</foreach></if></script>
        """)
    List<Translation> findTemplateLocalizations(@Param("types") Collection<Integer> types,@Param("keys") Collection<String> keys,@Param("languages") Collection<String> languages);
    @Delete("""
        <script>DELETE FROM translates WHERE type IN <foreach collection="types" item="type" open="(" close=")" separator=",">#{type}</foreach>
        AND tkey IN <foreach collection="keys" item="key" open="(" close=")" separator=",">#{key}</foreach>
        <if test="languages != null and !languages.isEmpty()">AND lang IN <foreach collection="languages" item="lang" open="(" close=")" separator=",">#{lang}</foreach></if></script>
        """)
    int deleteTemplateLocalizations(@Param("types") Collection<Integer> types,@Param("keys") Collection<String> keys,@Param("languages") Collection<String> languages);

}
