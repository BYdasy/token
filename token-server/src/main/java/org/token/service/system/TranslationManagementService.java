package org.token.service.system;

import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.token.custom.LoggingAspect;
import org.token.model.entity.Language;
import org.token.model.entity.Translation;
import org.token.model.request.translation.TemplateCompletionRequest;
import org.token.model.request.QueryParams;
import org.token.model.response.OperationOutcome;
import org.token.exception.BusinessException;
import org.token.service.LanguageService;
import org.token.service.TranslationCompletionService;
import org.token.service.TranslationCompletionTaskService;
import org.token.service.TranslationService;
import org.token.model.security.JwtUserPrincipal;
import org.token.tools.media.SundryUtils;
import org.token.util.EnglishTranslationValidator;
import org.token.util.SystemDataProtection;
import org.token.tools.translation.TranslationUtils;
import org.token.service.compute.ComputeAgentScope;
import org.token.service.compute.ComputeSystemMessageService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 翻译、模板和语言配置管理接口。
 */
@Service

@RequiredArgsConstructor
public class TranslationManagementService {

    private static final Set<Integer> NON_DELETABLE_PACKAGE_TYPES = Set.of(5, 7);

    private final TranslationService translationService;
    private final org.token.repository.system.TranslationRepository repository;

    private final LanguageService languageService;

    private final TranslationCompletionService translationCompletionService;

    private final TranslationCompletionTaskService translationCompletionTaskService;

    private final TranslationUtils translationUtils;
    private final ComputeSystemMessageService systemMessages;
    private final ComputeAgentScope computeScope;

    /**
     * 模板列表。
     */

    public OperationOutcome getTem() {
        List<Translation> templates = translationService.getTranslation(0);
        templates.forEach(this::attachTemplateMetadata);
        return OperationOutcome.success(templates);
    }

    /**
     * 模板新增。
     */

    public OperationOutcome addTem( Translation translates) {
        Translation record = requireTranslate(translates);
        record.setLang("zh");
        record.setType(0);
        record.setProp(defaultInt(record.getProp(), 0));
        record.setIsDel(defaultInt(record.getIsDel(), 0));
        record.setActionTime(now());
        translationService.save(record);
        attachTemplateMetadata(record);

        LoggingAspect.setLogDetails("模板新增: " + record.getTkey(), 1);
        return OperationOutcome.success(record);
    }

    /**
     * 模板修改。
     */

    public OperationOutcome updateTem( Translation translates) {
        Translation record = requireTranslate(translates);
        Translation template = requireTemplate(record.getId());
        requireCustomTemplate(template);
        record.setType(null);
        record.setLang(null);
        record.setActionTime(now());
        translationService.updateById(record);
        Translation updated = requireTemplate(record.getId());
        attachTemplateMetadata(updated);

        LoggingAspect.setLogDetails("模板修改: " + record.getTkey(), 1);
        return OperationOutcome.success(updated);
    }

    /**
     * 模板删除。
     */

    @Transactional(rollbackFor = Exception.class)
    public OperationOutcome deleteTem( JsonNode body) {
        Integer id = resolveId(body);
        if (id == null) {
            LoggingAspect.setLogDetails("模板删除失败，ID 为空", 0);
            return OperationOutcome.failure(-1, "server.common.id.required");
        }
        Translation template = requireTemplate(id);
        requireCustomTemplate(template);
        repository.deleteByType(id);
        translationService.removeById(id);

        LoggingAspect.setLogDetails("模板删除 ID: " + id, 1);
        return OperationOutcome.success();
    }

    /**
     * 翻译 key 列表。
     */

    public OperationOutcome getTranslation( QueryParams params) {
        requireTranslationAuthority(queryTemplateType(params), "6060201", "6060301", "6060401");
        IPage<Translation> page = translationService.getTranslationPage(params);
        return OperationOutcome.success(page);
    }

    public OperationOutcome languagePage( QueryParams params) {
        return OperationOutcome.success(languageService.pageLanguages(params));
    }

    public OperationOutcome languageOptions( Map<String, Object> body) {
        Boolean enabledOnly = body == null ? Boolean.TRUE : Boolean.TRUE.equals(body.get("enabledOnly"));
        return OperationOutcome.success(languageService.overview(enabledOnly));
    }

    public OperationOutcome updateLanguage( Language request) {
        Language language = languageService.updateLanguage(request);
        LoggingAspect.setLogDetails("更新多语言配置: " + language.getLang(), 1);
        return OperationOutcome.success(language);
    }

    /**
     * 翻译删除，按 key 删除所有语言。
     */

    @Transactional(rollbackFor = Exception.class)
    public OperationOutcome removeTranslation( Translation translates) {
        String tkey = normalizeKey(translates == null ? "" : translates.getTkey());
        requireOrdinaryTranslationKey(tkey);
        Integer type = requireDeletableTranslationType(translates == null ? null : translates.getType());
        translationService.deleteTranslation(tkey, type);

        LoggingAspect.setLogDetails("翻译删除: " + tkey + "，模板: " + type, 1);
        return OperationOutcome.success();
    }

    /**
     * 获取同一个 key 下的多语言翻译。
     */

    public OperationOutcome getByKeyBatch( Translation translates) {
        String key = normalizeKey(translates == null ? "" : translates.getTkey());
        Integer type = translates == null ? null : translates.getType();
        requireTranslationAuthority(type, "6060201", "6060301", "6060401");
        requireTemplate(type);
        if(ComputeSystemMessageService.systemKey(key)){computeScope.requireGlobalScope();if(!Integer.valueOf(6).equals(type))throw new BusinessException("server.v7.translation.systemTemplateProtected");}
        List<Translation> batch = translationService.getTranslationBatch(key, type);

        Map<String, String> result = new LinkedHashMap<>();
        for (Translation item : batch) {
            result.put(item.getLang(), item.getTvalue());
        }
        return OperationOutcome.success(result);
    }

    /**
     * 把当前文本翻译到多个语言，只返回结果，不入库。
     */

    public OperationOutcome translateToAllLanguages( Translation translates) throws InterruptedException {
        requireTranslationAuthority(translates == null ? null : translates.getType(),
                "6060205", "6060303", "6060403");
        requireManageableTranslationType(translates == null ? null : translates.getType());
        List<String> targetLanguages = resolveTargetLanguages(translates == null ? null : translates.getLanguages(), false);
        Map<String, String> map = translationUtils.translateToMultipleLanguagesParallel(
                translates == null ? "" : translates.getTvalue(),
                targetLanguages,
                resolveFormat(translates == null ? null : translates.getProp())
        );
        int prop = translates == null || translates.getProp() == null ? 0 : translates.getProp();
        // 翻译供应商漏项或返回空富文本不能伪装成成功，之后再误报用户未填写英文。
        if (targetLanguages.stream().anyMatch(language -> !EnglishTranslationValidator.hasContent(map.get(language), prop))) {
            throw new BusinessException("server.translate.failed");
        }
        return OperationOutcome.success(map);
    }

    /**
     * 统计指定模板相对于当前启用语言的完整度。
     */

    public OperationOutcome templateCompletionOverview( TemplateCompletionRequest request) {
        Integer type = request == null ? null : request.type();
        return OperationOutcome.success(translationCompletionService.overview(type));
    }

    /**
     * 只为指定模板新增缺失语言，不覆盖任何已有翻译。
     *
     * 此入口受模板管理权限控制，只补缺失项且不覆盖已有翻译。
     */

    public OperationOutcome fillMissingTemplateLanguages(
             TemplateCompletionRequest request,
             JwtUserPrincipal principal
    ) {
        Integer type = request == null ? null : request.type();
        var task = translationCompletionTaskService.submit(
                type, principal == null ? null : principal.getUid());
        LoggingAspect.setLogDetails(
                "模板翻译补齐任务已提交: " + task.templateName()
                        + "，任务ID: " + task.taskId(),
                1
        );
        return OperationOutcome.success(task);
    }

    /** 查询当前账号提交的模板补齐任务。 */

    public OperationOutcome templateCompletionTask(
             Long taskId,
             JwtUserPrincipal principal
    ) {
        return OperationOutcome.success(translationCompletionTaskService.getTask(
                taskId, principal == null ? null : principal.getUid()));
    }

    @Transactional(rollbackFor = Exception.class)
    public OperationOutcome translateAndAddBatch( Translation translates) throws InterruptedException {
        Translation source = requireTranslate(translates);
        requireTranslationAuthority(source.getType(), "6060202", "6060302", "6060402");
        String tkey = normalizeKey(source.getTkey());
        Integer type = requireManageableTranslationType(source.getType());
        Integer prop = source.getProp() == null ? 0 : source.getProp();
        requireOrdinaryTranslationKey(tkey);

        if (translationService.isExist(tkey, "zh", type)) {
            LoggingAspect.setLogDetails("批量翻译新增失败，key 已存在: " + tkey, 0);
            return OperationOutcome.failure(-1, "server.translate.exist");
        }

        LocalDateTime time = now();
        List<Translation> saveList = buildAutoTranslateSaveList(source, tkey, type, prop, time);
        translationService.saveBatch(saveList);
        translationService.translationsChanged(type);
        LoggingAspect.setLogDetails("批量翻译新增: " + tkey + "，语言数量: " + saveList.size(), 1);
        return OperationOutcome.success(saveList);
    }

    /**
     * 前台语言包批量修改，不存在的语言会自动新增。
     */

    @Transactional(rollbackFor = Exception.class)
    public OperationOutcome updateTranslationBatch( Translation translates) {
        Translation source = requireTranslate(translates);
        requireTranslationAuthority(source.getType(), "6060203", "6060303", "6060403");
        String tkey = normalizeKey(source.getTkey());
        Integer type = requireManageableTranslationType(source.getType());
        Integer prop = source.getProp() == null ? 0 : source.getProp();
        if(ComputeSystemMessageService.systemKey(tkey)) {
            computeScope.requireGlobalScope();source.setTkey(tkey);source.setType(type);source.setProp(prop);
            systemMessages.updateTranslationKey(source);
            LoggingAspect.setLogDetails("通知消息模板修改: "+tkey,1);return OperationOutcome.success(source);
        }

        List<Translation> existingBatch = translationService.getTranslationBatch(tkey, type);
        if (existingBatch == null || existingBatch.isEmpty()) {
            LoggingAspect.setLogDetails("批量翻译修改失败，key 不存在: " + tkey, 0);
            return OperationOutcome.failure(-1, "server.translate.key.notFound");
        }

        LocalDateTime time = now();
        Map<String, Translation> existingByLang = existingBatch.stream()
                .collect(Collectors.toMap(
                        Translation::getLang,
                        item -> item,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<String, String> requestedTranslations = safeTranslations(source.getTranslations());
        String english = requestedTranslations.containsKey("en") ? requestedTranslations.get("en")
                : existingBatch.stream()
                        .filter(item -> "en".equals(normalizeLanguageOrDefault(item.getLang(), "zh")))
                        .map(Translation::getTvalue).filter(value -> EnglishTranslationValidator.hasContent(value, prop))
                        .findFirst().orElse(null);
        EnglishTranslationValidator.require(english, prop);

        for (Map.Entry<String, String> entry : requestedTranslations.entrySet()) {
            String lang = normalizeLanguageOrDefault(entry.getKey(), "en");
            Translation item = Optional.ofNullable(existingByLang.get(lang))
                    .orElseGet(() -> buildTranslation(source, lang, entry.getValue(), time));

            item.setTkey(tkey);
            item.setLang(lang);
            item.setTvalue(entry.getValue());
            item.setType(type);
            item.setProp(prop);
            item.setActionTime(time);
            item.setIsDel(defaultInt(item.getIsDel(), 0));
            upsertTranslation(item);
        }

        source.setTkey(tkey);
        source.setLang("zh");
        source.setType(type);
        source.setProp(prop);
        source.setActionTime(time);
        source.setTranslations(null);
        upsertTranslation(source);
        translationService.translationsChanged(type);

        LoggingAspect.setLogDetails("批量翻译修改: " + tkey, 1);
        return OperationOutcome.success(source);
    }

    private List<Translation> buildAutoTranslateSaveList(
            Translation source,
            String tkey,
            Integer type,
            Integer prop,
            LocalDateTime time
    ) throws InterruptedException {
        List<Translation> saveList = new ArrayList<>();
        List<String> requestedLanguages = source.getLanguages();
        Map<String, String> manualTranslations = safeTranslations(source.getTranslations());

        source.setTkey(tkey);
        source.setLang("zh");
        source.setType(type);
        source.setProp(prop);
        source.setIsDel(defaultInt(source.getIsDel(), 0));
        source.setActionTime(time);
        source.setTranslations(null);
        saveList.add(source);

        Set<String> requiredTargets = new LinkedHashSet<>(resolveTargetLanguages(requestedLanguages, false));
        requiredTargets.add("en");
        List<String> targetLanguages = List.copyOf(requiredTargets);
        List<String> needLanguages = targetLanguages.stream()
                .filter(lang -> !"zh".equals(lang))
                .filter(lang -> !EnglishTranslationValidator.hasContent(manualTranslations.get(lang), prop))
                .toList();

        Map<String, String> autoTranslations = needLanguages.isEmpty()
                ? Map.of()
                : translationUtils.translateToMultipleLanguagesParallel(
                        source.getTvalue(),
                        needLanguages,
                        resolveFormat(prop)
                );

        for (String lang : targetLanguages) {
            if ("zh".equals(lang)) {
                continue;
            }
            String value = manualTranslations.get(lang);
            if (!EnglishTranslationValidator.hasContent(value, prop)) {
                value = autoTranslations.get(lang);
            }
            if (!EnglishTranslationValidator.hasContent(value, prop)) {
                continue;
            }
            Translation item = buildTranslation(source, lang, value, time);
            item.setType(type);
            item.setProp(prop);
            item.setIsDel(source.getIsDel());
            saveList.add(item);
        }

        EnglishTranslationValidator.require(saveList.stream().filter(item -> "en".equals(item.getLang()))
                .map(Translation::getTvalue).filter(value -> EnglishTranslationValidator.hasContent(value, prop))
                .findFirst().orElse(null), prop);
        return saveList;
    }

    private List<String> resolveTargetLanguages(List<String> requestedLanguages, boolean includeZh) {
        List<String> languages = requestedLanguages == null || requestedLanguages.isEmpty()
                ? languageService.enabledLanguageCodes()
                : requestedLanguages;
        if (languages == null || languages.isEmpty()) {
            languages = List.of("zh", "en");
        }

        return languages.stream()
                .filter(StringUtils::hasText)
                .map(lang -> normalizeLanguageOrDefault(lang, "zh"))
                .filter(lang -> includeZh || !"zh".equals(lang))
                .distinct()
                .toList();
    }

    private void upsertTranslation(Translation translates) {
        Translation record = requireTranslate(translates);
        record.setTkey(normalizeKey(record.getTkey()));
        record.setLang(normalizeLanguageOrDefault(record.getLang(), "zh"));
        if (record.getType() == null) {
            throw new BusinessException("server.translate.template.required");
        }
        record.setActionTime(record.getActionTime() == null ? now() : record.getActionTime());

        Translation exists = repository.findByKeyLanguageType(record.getTkey(), record.getLang(), record.getType());

        if (exists == null) {
            translationService.save(record);
            return;
        }

        record.setId(exists.getId());
        translationService.updateById(record);
    }

    private Translation buildTranslation(Translation source, String lang, String value, LocalDateTime time) {
        Translation item = new Translation();
        item.setTkey(normalizeKey(source.getTkey()));
        item.setLang(normalizeLanguageOrDefault(lang, "en"));
        item.setTvalue(value);
        item.setType(source.getType());
        item.setProp(source.getProp());
        item.setTitle(source.getTitle());
        item.setIsDel(source.getIsDel());
        item.setActionTime(time);
        return item;
    }

    private void attachTemplateMetadata(Translation template) {
        if (template == null) {
            return;
        }
        Integer id = template.getId();
        boolean systemTemplate = SystemDataProtection.isSystemTranslationTemplate(id);
        boolean protectedPackage = id != null && NON_DELETABLE_PACKAGE_TYPES.contains(id);
        template.setEditable(!systemTemplate);
        template.setTranslationEditable(!protectedPackage);
        template.setProtectedPackage(protectedPackage);
        template.setKind(systemTemplate ? "SYSTEM" : "CUSTOM");
        template.setCompletionAllowed(Boolean.TRUE);
    }

    private Translation requireTemplate(Integer type) {
        if (type == null || type <= 0) {
            throw new BusinessException("server.translate.template.required");
        }
        Translation template = translationService.getById(type);
        if (template == null || !Integer.valueOf(0).equals(template.getType())) {
            throw new BusinessException("server.translate.template.notFound");
        }
        return template;
    }

    private void requireCustomTemplate(Translation template) {
        if (SystemDataProtection.isSystemTranslationTemplate(template.getId())) {
            throw new BusinessException("server.translate.template.system.protected");
        }
    }

    private Integer requireManageableTranslationType(Integer type) {
        Translation template = requireTemplate(type);
        return template.getId();
    }

    private Integer requireDeletableTranslationType(Integer type) {
        Integer resolvedType = requireManageableTranslationType(type);
        if (NON_DELETABLE_PACKAGE_TYPES.contains(resolvedType)) {
            throw new BusinessException("server.translate.package.deleteForbidden");
        }
        return resolvedType;
    }

    /**
     * 共享翻译接口按模板类型校验对应页面的权限码。
     *
     * type=5 是管理端语言包，type=7 是前端语言包，其余模板归键值翻译。
     * 这里直接检查权限码，不使用自定义 SpEL Bean，便于从代码中看清授权规则。
     */
    private void requireTranslationAuthority(
            Integer type,
            String keyValuePermission,
            String adminPackagePermission,
            String frontendPackagePermission
    ) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            // 纯单元测试未建立安全上下文；HTTP 入口仍先经过原有方法权限校验。
            return;
        }

        Set<String> authorities = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        if (authorities.contains("super")) {
            return;
        }

        String actionPermission;
        if (Integer.valueOf(5).equals(type)) {
            actionPermission = adminPackagePermission;
        } else if (Integer.valueOf(7).equals(type)) {
            actionPermission = frontendPackagePermission;
        } else {
            actionPermission = keyValuePermission;
        }
        if (!authorities.contains(actionPermission)) {
            throw new AccessDeniedException("缺少翻译权限码：" + actionPermission);
        }
    }

    private Integer queryTemplateType(QueryParams params) {
        String rawType = params == null || params.getFilterParams() == null
                ? null
                : params.getFilterParams().getType();
        if (!StringUtils.hasText(rawType)) {
            return null;
        }
        try {
            return Integer.valueOf(rawType.trim());
        } catch (NumberFormatException exception) {
            throw new BusinessException("server.translate.template.invalid");
        }
    }

    private Translation requireTranslate(Translation translates) {
        if (translates == null) {
            throw new BusinessException("server.translate.required");
        }
        if (!StringUtils.hasText(translates.getTkey())) {
            throw new BusinessException("server.translate.key.required");
        }
        return translates;
    }

    private String normalizeKey(String key) {
        String value = key == null ? "" : key.trim();
        if (!StringUtils.hasText(value)) {
            throw new BusinessException("server.translate.key.required");
        }
        return value;
    }

    private String normalizeLanguageOrDefault(String lang, String defaultLang) {
        String value = StringUtils.hasText(lang) ? lang.trim() : defaultLang;
        value = value.replace('_', '-');
        if (!value.matches("^[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8})*$")) {
            throw new BusinessException("server.language.lang.invalid");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private String resolveFormat(Integer prop) {
        if (prop != null && prop == 2) {
            return "telegram";
        }
        return prop != null && prop == 1 ? "html" : "text";
    }

    private Integer resolveId(JsonNode body) {
        if (body == null || body.isNull()) {
            return null;
        }
        if (body.isInt() || body.isLong()) {
            return body.asInt();
        }
        JsonNode idNode = body.get("id");
        return idNode == null || idNode.isNull() ? null : idNode.asInt();
    }

    private int defaultInt(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private Map<String, String> safeTranslations(Map<String, String> translations) {
        if (translations == null || translations.isEmpty()) {
            return Map.of();
        }

        Map<String, String> result = new LinkedHashMap<>();
        translations.forEach((lang, value) -> {
            if (StringUtils.hasText(lang)) {
                result.put(normalizeLanguageOrDefault(lang, "zh"), value);
            }
        });
        return result;
    }

    private void requireOrdinaryTranslationKey(String key) {
        if (org.token.service.compute.ComputeSystemMessageService.systemKey(key))
            throw new BusinessException("server.v7.translation.systemTemplateProtected");
    }

    private LocalDateTime now() {
        return SundryUtils.getTime();
    }

}
