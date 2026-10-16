package org.token.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.token.model.entity.Translation;
import org.token.model.request.QueryParams;
import org.token.model.response.AdminI18nBundle;

import java.util.List;
import java.util.Map;

/**
 * 翻译服务接口
 */
public interface TranslationService {
    Translation getById(java.io.Serializable id);
    boolean save(Translation record);
    boolean updateById(Translation record);
    boolean removeById(java.io.Serializable id);
    boolean saveBatch(java.util.Collection<Translation> records);
    boolean updateBatchById(java.util.Collection<Translation> records);


    /**
     * 获取 Java 服务端消息；目标语言缺失时回退英语，不在读取时写库。
     */
    String getTranslate(String key, String lang);

    /**
     * 获取管理端使用的动态语言包。
     */
    AdminI18nBundle getAdminI18nBundle(String lang);

    /** 用户端公开词包，精确语言、基础语言及英语回退。 */
    Map<String, String> getUserI18nMessages(String lang);

    /** 译文写入与版本更新同事务；只影响1/5/7正文缓存。 */
    void translationsChanged(Integer type);

    /**
     * 根据 key 和 type 查询一组翻译。
     */
    List<Translation> getTranslationBatch(String key, Integer type);

    /**
     * 根据 type 查询中文翻译列表。
     */
    List<Translation> getTranslation(Integer type);

    /**
     * 分页查询翻译。
     */
    IPage<Translation> getTranslationPage(QueryParams queryParams);

    /**
     * 判断某个 key + lang 是否存在。
     */
    boolean isExist(String key, String lang, Integer type);

    /**
     * 删除翻译。
     *
     * 按模板类型删除同一 key 的全部语言记录。
     */
    void deleteTranslation(String key, Integer type);

}
