package org.token.controller.publicapi;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.token.config.EnabledLocaleResolver;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.service.TranslationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录前与管理端启动阶段使用的公开多语言接口。
 */
@RestController
@RequestMapping("/api/i18n")
@RequiredArgsConstructor
public class I18nController {

    private final TranslationService translationService;

    private final ResultFactory resultFactory;

    private final EnabledLocaleResolver localeResolver;

    /**
     * 返回后台启用语言及 type=5 的 admin.* 管理端语言包。
     */
    @GetMapping("/admin")
    public Result admin(
            @RequestParam(required = false) String lang,
            HttpServletRequest request
    ) {
        String requested = lang == null ? localeResolver.resolve(request) : lang;
        return resultFactory.success(translationService.getAdminI18nBundle(requested));
    }
}
