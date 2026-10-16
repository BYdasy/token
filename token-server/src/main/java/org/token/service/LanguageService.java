package org.token.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.token.model.entity.Language;
import org.token.model.request.QueryParams;

import java.util.List;
import java.util.Map;

/**
 * 系统多语言配置服务。
 */
public interface LanguageService {

    List<Language> listLanguages(Boolean enabledOnly);

    IPage<Language> pageLanguages(QueryParams params);

    Language updateLanguage(Language request);

    Map<String, Object> overview(Boolean enabledOnly);

    List<String> enabledLanguageCodes();
}
