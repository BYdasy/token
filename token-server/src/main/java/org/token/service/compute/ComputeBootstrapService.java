package org.token.service.compute;

import org.springframework.stereotype.Service;
import org.token.service.compute.ComputeAccountService;
import org.token.service.compute.ComputeCurrencyService;
import org.token.service.compute.ComputeLoginMethodOrder;

import lombok.RequiredArgsConstructor;
import org.token.config.EnabledLocaleResolver;
import org.token.service.SystemSettingService;
import org.token.service.TranslationService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ComputeBootstrapService {
    private final ComputeAccountService accounts;
    private final EnabledLocaleResolver locales;
    private final SystemSettingService systemSettings;
    private final TranslationService translations;
    private final ComputeCurrencyService currencies;
    private final org.token.service.compute.ComputePresentationService presentation;
    private final org.token.service.compute.ComputeContentService content;
    private final org.token.service.compute.ComputeUserFeaturesService features;

    private final org.token.repository.compute.ComputePublicQueryRepository repository;

    public Map<String, Object> bootstrap(String lang, String clientIp) {
        Map<String, String> settings = accounts.settings();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("siteName", systemSettings.get("user.site.name", "Tensor AI"));
        response.put("siteLogo", systemSettings.get("user.site.logo", ""));
        response.put("siteIcon", systemSettings.get("user.site.icon", ""));
        response.put("heroImage", systemSettings.get("user.site.hero-image", ""));
        Map<String, Object> display = presentation.publicSettings();
        response.put("presentation", display);
        response.put("guestEnabled", true);
        response.put("forgotPasswordEnabled", Boolean.parseBoolean(settings.getOrDefault("forgotPasswordEnabled", "true")));
        response.put("usernameCharacterLimitEnabled", org.token.service.compute.ComputeMemberUidAllocator.usernameLimit(settings));
        response.put("inviteCodeRequired", Boolean.parseBoolean(settings.getOrDefault("invitationEnabled", "true")) && Boolean.parseBoolean(settings.getOrDefault("inviteCodeRequired", "false")));
        response.put("userTimeZoneMode", "SYSTEM");
        response.put("systemTimeZone", systemSettings.resolveSystemTimeZone());
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String field : List.of("username", "email", "phone")) {
            fields.put(field, Map.of("enabled", Boolean.parseBoolean(settings.getOrDefault(field + "Enabled", "true")),
                    "required", Boolean.parseBoolean(settings.getOrDefault(field + "Required", "username".equals(field) ? "true" : "false"))));
        }
        response.put("loginFields", fields);
        response.put("loginMethodOrder", ComputeLoginMethodOrder.publicOrder(settings.get("loginMethodOrder")));
        response.put("defaultCurrency", currencies.defaultCurrency());
        response.put("withdrawalAddressDeleteEnabled", Boolean.parseBoolean(settings.getOrDefault("withdrawalAddressDeleteEnabled", "true")));
        response.put("languages", repository.enabledLanguages());
        String language = locales.resolve(lang == null || lang.isBlank() ? String.valueOf(display.get("defaultLanguage")) : lang);
        response.put("features", features.publicSettings(clientIp,language));
        response.put("translations", translations.getUserI18nMessages(language));
        response.put("language", language);
        Map<String, Object> legal = new LinkedHashMap<>();
        for (String key : List.of("TERMS", "PRIVACY")) {
            Map<String, Object> page = content.userList(null, Map.of("kind", "ARTICLE", "contentKey", key, "size", "1"), language);
            if (page.get("records") instanceof List<?> records && !records.isEmpty() && records.get(0) instanceof Map<?, ?> row)
                legal.put(key, Map.of("title", row.get("title"), "bodyHtml", row.get("bodyHtml")));
        }
        display.put("legal", legal);
        return response;
        }
}
