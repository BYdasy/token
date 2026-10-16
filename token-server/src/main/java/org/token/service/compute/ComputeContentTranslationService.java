package org.token.service.compute;

import org.token.repository.compute.ComputeContentTranslationRepository;

import lombok.extern.slf4j.Slf4j;
import org.token.config.EnabledLocaleResolver;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.token.service.compute.ComputeContentHtml;
import org.token.service.compute.ComputeBusinessTranslations;
import org.token.tools.translation.TranslationUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

import static org.token.repository.compute.ComputeData.*;

/** 内容补译复用现有供应商与有界线程池，数据库租约合并多实例请求并阻止旧版本写回。 */
@Service
@Slf4j
public class ComputeContentTranslationService {
    private static final String PREFIX = "server.compute.content.translation.";
    private final ComputeData data;
    private final ComputeContentTranslationRepository repository;
    private final ComputeAgentScope scope;
    private final EnabledLocaleResolver locales;
    private final TranslationUtils translator;
    private final TaskExecutor executor;
    private final TransactionTemplate tx;

    @Value("${google.translate.timeout-seconds:30}")
    private int timeoutSeconds = 30;

    public ComputeContentTranslationService(JdbcTemplate jdbc, ComputeData data, ComputeAgentScope scope,
            EnabledLocaleResolver locales, TranslationUtils translator,
            @Qualifier("translationCompletionTaskExecutor") TaskExecutor executor,
            PlatformTransactionManager manager) {
        this(data,scope,locales,translator,executor,manager,new ComputeContentTranslationRepository(data));
    }

    @Autowired
    public ComputeContentTranslationService(ComputeData data, ComputeAgentScope scope,
            EnabledLocaleResolver locales, TranslationUtils translator,
            @Qualifier("translationCompletionTaskExecutor") TaskExecutor executor,
            PlatformTransactionManager manager,ComputeContentTranslationRepository repository) {
        this.data = data;
        this.scope = scope;
        this.locales = locales;
        this.translator = translator;
        this.executor = executor;
        this.tx = new TransactionTemplate(manager);

        this.repository = repository;
    }

    public Map<String, Object> submit(long contentId, Object requestedLanguages, int operator) {
        return submitResource("CONTENT",contentId,requestedLanguages,operator);
    }

    /** 显式翻译使用当前表单源文刷新所选语言；只写隔离快照，确认保存后才修改业务内容。 */
    public Map<String,Object> submitForm(String resource,Map<String,Object> request,int operator) {
        String type=resourceType(resource);
        if("FORM".equals(type))throw new BusinessException("server.compute.invalidInput");
        if(!"CONTENT".equals(type))scope.requireGlobalScope();
        Long sourceId=request.get("sourceId")==null?null:number(request.get("sourceId"));
        Map<String,Object> sourceRow=sourceId==null?Map.of():resourceRow(type,sourceId,false);
        String contentKind="CONTENT".equals(type)?text(sourceId==null?request.get("kind"):sourceRow.get("kind")):"ACTIVITY".equals(type)?text(sourceId==null?request.get("type"):sourceRow.get("type")):"";
        if(!contentKind.isEmpty()&&!("ACTIVITY".equals(type)?Set.of("WHEEL","CHEST","RED_PACKET"):Set.of("MESSAGE","TASK","ANNOUNCEMENT","ARTICLE")).contains(contentKind))throw new BusinessException("server.compute.content.invalid");
        String title=text(request.get("title"));
        if(title.isEmpty()||title.length()>200)throw new BusinessException(PREFIX+"sourceRequired");
        String body="COMPANY_FILE".equals(type)?"":ComputeContentHtml.sanitize(text(request.get("bodyHtml")));
        String source=locales.normalize(text(request.getOrDefault("sourceLanguage","zh")));
        if(!enabledLanguages().contains(source))throw new BusinessException(PREFIX+"languageDisabled");
        Map<String,Object> all=ComputeBusinessTranslations.merge(Map.of(),request.get("translations"),null,locales);
        if("ACTIVITY".equals(type)&&request.get("translations") instanceof Map<?,?> values) {
            for(var entry:values.entrySet())if(entry.getValue() instanceof Map<?,?> item) {
                String language=locales.normalize(text(entry.getKey()));
                if(all.get(language) instanceof Map<?,?> stored) {
                    String description=text(item.get("description"));
                    if(description.length()>10000)throw new BusinessException("server.compute.content.tooLarge");
                    ((Map<String,Object>)stored).put("description",description);
                }
            }
        }
        all.put(source,fields("title",title,"bodyHtml",body));
        if("ACTIVITY".equals(type)) {
            String description=text(request.get("description"));
            if(description.length()>10000)throw new BusinessException("server.compute.content.tooLarge");
            ((Map<String,Object>)all.get(source)).put("description",description);
        }
        List<String> targets=targetLanguages(request.get("targetLanguages"));
        // 本次目标语必须重新翻译；历史英文不能充当已修改中文的源文，也不能让任务直接判定完成。
        // 非目标语和源语言仍保留，普通内容的按需补译继续执行只填空字段的规则。
        String hash=hash(data.json(fields("mode","REFRESH_FORM_V4","sourceId",sourceId,"contentKind",contentKind,"title",title,"bodyHtml",body,
                "sourceLanguage",source,"translations",new TreeMap<>(all),"targetLanguages",new TreeSet<>(targets))));
        for(String language:targets)if(!source.equals(language))all.remove(language);
        Long taskId=tx.execute(status->{
            List<Map<String,Object>> found=repository.submitFormTranslationFormRows(operator, type, hash);
            if(found.isEmpty()) {
                try {data.insert("compute_translation_form",fields("requested_by",operator,"resource_type",type,"request_hash",hash,"source_business_id",sourceId,
                        "content_kind",contentKind.isEmpty()?null:contentKind,
                        "title",title,"body_html",body,"source_language",source,"translations_json",data.json(all),"content_version",1,"created_at",now(),"updated_at",now()));}
                catch(DuplicateKeyException duplicate) { /* 相同表单并发只保留一个快照。 */ }
                found=repository.submitFormTranslationFormRows(operator, type, hash);
            }
            return enqueue("FORM",found.get(0),targets,operator,true);
        });
        dispatchPending();
        return formTask(type,Objects.requireNonNull(taskId),operator);
    }

    public Map<String,Object> formTask(String resource,long taskId,int operator) {
        return formTask(resource,taskId,operator,null);
    }
    /** 分类用于当前请求的叶子权限复核；后台翻译线程不进行管理员接口授权。 */
    public String formContentKind(long taskId,int operator) {
        var forms=repository.formContentKindContentTranslationTaskRows(taskId, operator);
        if(forms.isEmpty())throw new BusinessException(404,PREFIX+"taskNotFound");
        var form=forms.get(0);
        if(form.get("sourceBusinessId")!=null)return text(resourceRow("CONTENT",number(form.get("sourceBusinessId")),false).get("kind"));
        return text(form.get("contentKind"));
    }
    public String formActivityType(long taskId,int operator) {
        var forms=repository.formActivityTypeContentTranslationTaskRows(taskId, operator);
        if(forms.isEmpty())throw new BusinessException(404,PREFIX+"taskNotFound");
        var form=forms.get(0);
        return form.get("sourceBusinessId")==null?text(form.get("contentKind")):text(resourceRow("ACTIVITY",number(form.get("sourceBusinessId")),false).get("type"));
    }
    public Map<String,Object> formTask(String resource,long taskId,int operator,Long expectedSourceId) {
        String type=resourceType(resource);
        if(!"CONTENT".equals(type))scope.requireGlobalScope();
        List<Map<String,Object>> jobs=repository.formTaskContentTranslationTaskRows(taskId, type, operator);
        if(jobs.isEmpty())throw new BusinessException(404,PREFIX+"taskNotFound");
        Map<String,Object> job=jobs.get(0),form=unscopedRow("FORM",number(job.get("contentId")),false);
        Long sourceId=form.get("sourceBusinessId")==null?null:number(form.get("sourceBusinessId"));
        if(expectedSourceId!=null&&!expectedSourceId.equals(sourceId))throw new BusinessException(404,PREFIX+"taskNotFound");
        if(sourceId!=null)resourceRow(type,sourceId,false);
        Map<String,Object> result=taskView(job);result.put("translations",translationMap(form.get("translationsJson")));
        result.put("formResource",type);result.put("sourceHash",form.get("requestHash"));return result;
    }

    public Map<String,Object> submitSavedForm(String resource,long id,Object languages,int operator) {
        String type=resourceType(resource);Map<String,Object> row=resourceRow(type,id,false);
        return submitForm(type,fields("sourceId",id,"title",row.get("title"),"bodyHtml",row.get("bodyHtml"),"sourceLanguage",row.get("sourceLanguage"),
                "translations",translationMap(row.get("translationsJson")),"targetLanguages",languages),operator);
    }

    private List<String> targetLanguages(Object requested) {
        Set<String> enabled=enabledLanguages();
        if(requested==null)return new ArrayList<>(enabled);
        if(!(requested instanceof Collection<?> values)||values.isEmpty()||values.size()>200)throw new BusinessException(PREFIX+"languageDisabled");
        List<String> result=values.stream().map(value->locales.normalize(text(value))).distinct().toList();
        if(result.stream().anyMatch(language->!enabled.contains(language)))throw new BusinessException(PREFIX+"languageDisabled");
        return result;
    }
    private static String hash(String source) {
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("当前运行环境不支持 SHA-256",error);}
    }

    public Map<String,Object> submitCatalog(String resource,long id,Object languages,int operator) {
        scope.requireGlobalScope();return submitResource(resourceType(resource),id,languages,operator);
    }

    private Map<String,Object> submitResource(String resource,long contentId,Object requestedLanguages,int operator) {
        Set<String> enabled = enabledLanguages();
        List<String> targets;
        if (requestedLanguages == null) targets = new ArrayList<>(enabled);
        else if (requestedLanguages instanceof Collection<?> values && values.size() <= 200) {
            targets = values.stream().map(v -> locales.normalize(text(v))).distinct().toList();
            if (targets.stream().anyMatch(l -> l.isEmpty() || !enabled.contains(l)))
                throw new BusinessException(PREFIX + "languageDisabled");
        } else throw new BusinessException(PREFIX + "languageDisabled");

        Long taskId = tx.execute(status -> {
            Map<String, Object> content = resourceRow(resource,contentId,true);
            return enqueue(resource,content,targets,operator,true);
        });
        dispatchPending();
        return resourceTask(resource,contentId,Objects.requireNonNull(taskId));
    }

    public Map<String, Object> task(long contentId, long taskId) {
        return resourceTask("CONTENT",contentId,taskId);
    }

    public Map<String,Object> catalogTask(String resource,long id,long taskId) {
        scope.requireGlobalScope();return resourceTask(resourceType(resource),id,taskId);
    }

    private Map<String,Object> resourceTask(String resource,long contentId,long taskId) {
        resourceRow(resource,contentId,false);
        List<Map<String, Object>> rows = repository.resourceTaskContentTranslationTaskRows(taskId, contentId, resource);
        if (rows.isEmpty()) throw new BusinessException(404, PREFIX + "taskNotFound");
        return taskView(rows.get(0));
    }

    /** 已通过可见性检查的内容，精确/基础语言缺失时只回退真实英文。 */
    public void applyUserTranslation(Map<String, Object> row, Map<String, Object> translations, String requested) {
        applyResourceTranslation("CONTENT",row,translations,requested);
    }

    public void applyCatalogTranslation(String resource,Map<String,Object> row,String requested) {
        row.put("title",row.get("name"));row.put("bodyHtml",text(row.get("description")));
        Map<String,Object> translations=translationMap(row.remove("translationsJson"));
        seedSource(row,translations);
        applyResourceTranslation(resourceType(resource),row,translations,requested);
        row.put("name",row.remove("title"));row.put("description",row.remove("bodyHtml"));row.put("descriptionHtml",row.get("description"));
    }

    /** 公司资源沿用同一持久翻译队列；名称缺译时不把中文文件名当成英语展示。 */
    public void applyCompanyFileTranslation(Map<String,Object> row,String requested) {
        Map<String,Object> content=companyTranslationRow(new LinkedHashMap<>(row));
        applyResourceTranslation("COMPANY_FILE",content,translationMap(content.get("translationsJson")),requested);
        String title=text(content.get("title"));
        row.put("fileName",title.isEmpty()?"Company document":title);
    }

    private void applyResourceTranslation(String resource,Map<String,Object> row,Map<String,Object> translations,String requested) {
        seedSource(row,translations);
        String language=locales.normalize(requested==null?"en":requested);
        if(!enabledLanguages().contains(language))language="en";
        String requestedLanguage=language;
        String body=text(row.get("bodyHtml"));
        for(String candidate:ComputeBusinessTranslations.candidates(language))if(!"en".equals(candidate)&&complete(translations,candidate,body)){
            applyFields(row,translations.get(candidate));row.put("translationLanguage",candidate);row.put("translationStatus","READY");return;
        }
        if("en".equals(language)&&complete(translations,"en",body)) {
            applyFields(row,translations.get("en"));row.put("translationLanguage","en");row.put("translationStatus","READY");return;
        }
        if(!complete(translations,"en",body)) {
            if("COMPANY_FILE".equals(resource)) {
                // 旧公司资源可能尚无英语，首次仅补英文；随后缺译语言以真实英文补齐。
                tx.executeWithoutResult(status->{
                    Map<String,Object> current=unscopedRow(resource,number(row.get("id")),true);
                    if(number(current.get("contentVersion"))==number(row.get("contentVersion")))
                        enqueue(resource,current,List.of("en"),null,false);
                });
                pending(row,"PENDING");row.put("translationLanguage","en");return;
            }
            pending(row,"FAILED");row.put("translationLanguage","en");row.put("translationErrorKey",PREFIX+"englishRequired");return;
        }
        long contentId = number(row.get("id"));
        long version = number(row.get("contentVersion"));
        Long taskId = tx.execute(status -> {
            Map<String,Object> content=unscopedRow(resource,contentId,true);
            if (number(content.get("contentVersion")) != version) return null;
            return enqueue(resource,content,List.of(requestedLanguage),null,false);
        });
        if (taskId == null) {
            pending(row, "PENDING");
            return;
        }
        Map<String, Object> item = repository.applyResourceTranslationContentTranslationItemRow(taskId, requestedLanguage);
        row.put("translationTaskId", taskId);
        String state = text(item.get("status"));
        row.put("translationPendingStatus",state);
        if("FAILED".equals(state))row.put("translationRetryAfter",item.get("retryAfter"));
        if ("SUCCEEDED".equals(state)) {
            Map<String,Object> current=unscopedRow(resource,contentId,false);
            Map<String, Object> latest = translationMap(current.get("translationsJson"));
            if (number(current.get("contentVersion")) == version && complete(latest, language, text(row.get("bodyHtml")))) {
                applyFields(row, latest.get(language));
                row.put("translationStatus", "READY");row.put("translationLanguage",language);
                return;
            }
        }
        Map<String,Object> latest=unscopedRow(resource,contentId,false);
        Map<String,Object> fallback=translationMap(latest.get("translationsJson"));seedSource(latest,fallback);
        if(number(latest.get("contentVersion"))==version&&complete(fallback,"en",body)){
            applyFields(row,fallback.get("en"));row.put("translationLanguage","en");row.put("translationStatus","READY");row.put("translationFallback",true);return;
        }
        row.put("translationLanguage","en");
        if ("FAILED".equals(state)) {
            pending(row,"FAILED");
            row.put("translationStatus", "FAILED");
            row.put("translationRetryAfter", item.get("retryAfter"));
        } else pending(row, "RUNNING".equals(state) ? "RUNNING" : "PENDING");
    }

    private static void pending(Map<String, Object> row, String state) {
        row.put("translationStatus", state);
        row.put("title", "");
        row.put("bodyHtml", "");
    }

    /** 必须与中文保存处于同一事务；执行中的旧请求即使稍后返回也不能写回。 */
    public void supersede(long contentId, long currentVersion) {
        supersedeResource("CONTENT",contentId,currentVersion);
    }
    public void supersedeCatalog(String resource,long id,long version){supersedeResource(resourceType(resource),id,version);}
    private void supersedeResource(String resource,long contentId,long currentVersion) {
        repository.supersedeResourceContentTranslationItemWrite(PREFIX + "superseded", now(), contentId, currentVersion, resource);
        repository.supersedeResourceContentTranslationTaskWrite(now(), contentId, currentVersion, resource);
    }

    public Map<String, Object> translationMap(Object raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        if(raw instanceof byte[] bytes)raw=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        if (raw == null || text(raw).isEmpty()) return result;
        Map<String, Object> source = raw instanceof Map<?, ?> map ? castMap(map) : data.jsonMap(text(raw));
        source.forEach((key, value) -> {
            String language = locales.normalize(key);
            if (!language.isEmpty() && value instanceof Map<?, ?> fields) {
                Map<String, Object> old = result.get(language) instanceof Map<?, ?> existing ? castMap(existing) : new LinkedHashMap<>();
                for (String field : List.of("title", "bodyHtml", "description"))
                    if (text(old.get(field)).isEmpty() && !text(fields.get(field)).isEmpty()) old.put(field, text(fields.get(field)));
                result.put(language, old);
            }
        });
        return result;
    }

    private Set<String> enabledLanguages() {
        Set<String> result = new LinkedHashSet<>();
        locales.enabledLanguages().forEach(option -> result.add(locales.normalize(option.value())));
        result.remove("");
        return result;
    }

    private Map<String, Object> scopedContent(long id, boolean lock) {
        List<Map<String, Object>> rows=repository.scopedContentRows(id,lock,scope.queryScope());
        if (rows.isEmpty()) throw new BusinessException(404, "server.compute.content.notFound");
        return rows.get(0);
    }

    private static String resourceType(String resource) {
        return switch(resource){case "products","PRODUCT"->"PRODUCT";case "levels","LEVEL"->"LEVEL";case "CONTENT"->"CONTENT";case "FORM"->"FORM";case "ACTIVITY"->"ACTIVITY";case "COMPANY_FILE"->"COMPANY_FILE";default->throw new BusinessException("server.compute.notFound");};
    }
    private Map<String,Object> resourceRow(String resource,long id,boolean lock){
        if("CONTENT".equals(resource))return scopedContent(id,lock);
        scope.requireGlobalScope();return unscopedRow(resource,id,lock);
    }
    private Map<String,Object> unscopedRow(String resource,long id,boolean lock){
        Map<String,Object> row=repository.resourceRow(resourceType(resource),id,lock);
        return "COMPANY_FILE".equals(resource)?companyTranslationRow(row):row;
    }
    private Map<String,Object> companyTranslationRow(Map<String,Object> row) {
        Map<String,Object> names=row.get("namesJson")==null?Map.of():data.jsonMap(row.get("namesJson"));
        Map<String,Object> translations=new LinkedHashMap<>();
        names.forEach((language,title)->{if(title instanceof String value&&!value.isBlank())translations.put(language,fields("title",value,"bodyHtml",""));});
        row.put("title",text(names.get("zh")).isEmpty()?row.get("fileName"):names.get("zh"));
        row.put("bodyHtml","");row.put("sourceLanguage","zh");row.put("translationsJson",translations);
        row.put("contentVersion",row.get("version"));return row;
    }
    public static void seedSource(Map<String,Object> row,Map<String,Object> translations){
        String language=text(row.getOrDefault("sourceLanguage","zh"));
        if(language.isEmpty())language="zh";
        String title=text(row.get("title")),body=text(row.get("bodyHtml"));
        if(!title.isEmpty())translations.putIfAbsent(language,fields("title",title,"bodyHtml",body));
    }
    /** 调用者持有内容行锁且正在保存事务中，仅入队；定时器在提交后处理。 */
    public Map<String,Object> ensureEnglish(String resource,long id,Integer operator){
        String type=resourceType(resource);Map<String,Object> row=unscopedRow(type,id,true);
        Map<String,Object> translations=translationMap(row.get("translationsJson"));seedSource(row,translations);
        if(complete(translations,"en",text(row.get("bodyHtml"))))return null;
        long task=enqueue(type,row,List.of("en"),operator,false);
        return taskView(repository.ensureEnglishContentTranslationTaskRow(task));
    }

    private long enqueue(String resource,Map<String, Object> content, List<String> targets, Integer operator, boolean explicit) {
        long id = number(content.get("id")), version = number(content.get("contentVersion"));
        String title = text(content.get("title")), body = text(content.get("bodyHtml"));
        if (title.isEmpty()) throw new BusinessException(PREFIX + "sourceRequired");
        Map<String, Object> translations = translationMap(content.get("translationsJson"));seedSource(content,translations);
        if(!"FORM".equals(resource)&&complete(translations,"en",body)){Map<String,Object> english=fieldsFor(translations,"en");title=text(english.get("title"));body=text(english.get("bodyHtml"));}
        List<Map<String, Object>> found = repository.enqueueContentTranslationTaskRows(id, version, resource);
        long taskId = found.isEmpty() ? data.insert("compute_content_translation_task", fields(
                "content_id", id, "content_version", version,"resource_type",resource,"status", "PENDING", "requested_by", operator,
                "source_title", title, "source_body", body, "created_at", now(), "updated_at", now())) : number(found.get(0).get("id"));
        for (String language : targets) {
            List<Map<String, Object>> items = repository.enqueueContentTranslationItemRows(taskId, language);
            boolean ready = complete(translations, language, body);
            if (items.isEmpty()) {
                data.insert("compute_content_translation_item", fields("task_id", taskId, "language", language,
                        "status", ready ? "SUCCEEDED" : "PENDING", "created_at", now(), "updated_at", now()));
            } else {
                Map<String, Object> item = items.get(0);
                String state = text(item.get("status"));
                if (ready) {
                    repository.enqueueContentTranslationItemWrite(now(), item.get("id"));
                } else if ("SUCCEEDED".equals(state) || "SUPERSEDED".equals(state)
                        || ("FAILED".equals(state) && (explicit || expired(item.get("retryAfter"))))) {
                    repository.enqueueContentTranslationItemWrite2(now(), item.get("id"));
                }
            }
        }
        refreshTask(taskId);
        return taskId;
    }

    /** QUEUED 是持久化内部状态，对外仍显示 PENDING；租约和随机令牌防止跨节点重复提交。 */
    @Scheduled(fixedDelay = 2000, initialDelay = 2000)
    public void dispatchPending() {
        try {
            recoverLeases();
            List<Map<String, Object>> items = repository.dispatchPendingContentTranslationItemRows();
            for (Map<String, Object> item : items) {
                long id = number(item.get("id"));
                String token = UUID.randomUUID().toString();
                if (repository.dispatchPendingContentTranslationItemWrite(token, now().plusSeconds(90), now(), id) != 1) continue;
                try { executor.execute(() -> translateItem(id, token)); }
                catch (RuntimeException exception) { fail(id, token, PREFIX + "queueFull"); }
            }
        } catch (DataAccessException exception) {
            // SQL 未执行时不创建运行时迁移；相关内容接口会明确失败。
            log.debug("内容翻译任务暂不可调度：{}", exception.getClass().getSimpleName());
        }
    }

    private void recoverLeases() {
        List<Map<String, Object>> expired = repository.recoverLeasesContentTranslationItemRows(now());
        for (Map<String, Object> item : expired) fail(number(item.get("id")), text(item.get("runToken")), PREFIX + "interrupted");
    }

    void translateItem(long id, String token) {
        if (repository.translateItemContentTranslationItemWrite(now().plusSeconds(Math.max(1, timeoutSeconds) * 2L + 60), now(), id, token, now()) != 1) return;
        Map<String, Object> job = repository.translateItemContentTranslationItemRow(id);
        refreshTask(number(job.get("taskId")));
        try {
            String language = text(job.get("language"));
            if (!enabledLanguages().contains(language)) throw new BusinessException(PREFIX + "languageDisabled");
            Map<String,Object> current=unscopedRow(text(job.get("resourceType")),number(job.get("contentId")),false);
            if (number(current.get("contentVersion")) != number(job.get("contentVersion"))) {
                finish(job, token, Map.of());
                return;
            }
            Map<String,Object> all=translationMap(current.get("translationsJson"));seedSource(current,all);
            String sourceTitle=text(job.get("sourceTitle")),sourceBody=text(job.get("sourceBody"));
            if(!"FORM".equals(job.get("resourceType"))) {
                if(complete(all,"en",text(current.get("bodyHtml")))) {
                    Map<String,Object> english=fieldsFor(all,"en");sourceTitle=text(english.get("title"));sourceBody=text(english.get("bodyHtml"));
                } else if(!("COMPANY_FILE".equals(job.get("resourceType"))&&"en".equals(language)))throw new BusinessException(PREFIX+"englishRequired");
            }
            Map<String, Object> existing = fieldsFor(all, language);
            Map<String, Object> translated = new LinkedHashMap<>();
            if (text(existing.get("title")).isEmpty()) translated.put("title", translatedText(sourceTitle, language, "text"));
            if (text(existing.get("bodyHtml")).isEmpty() && !sourceBody.isEmpty())
                translated.put("bodyHtml", ComputeContentHtml.sanitize(translatedText(sourceBody, language, "html")));
            if("FORM".equals(job.get("resourceType"))&&"ACTIVITY".equals(current.get("resourceType"))) {
                String description=text(fieldsFor(all,text(current.get("sourceLanguage"))).get("description"));
                if(!description.isEmpty()&&text(existing.get("description")).isEmpty()) {
                    Map<String,String> response=translator.translateToMultipleLanguagesParallel(description,List.of(language),"text");
                    String value=response.get(language);if(text(value).isEmpty())value=response.get(language.split("-",2)[0]);
                    if(text(value).isEmpty())throw new BusinessException(PREFIX+"empty");
                    if(value.length()>10000)throw new BusinessException("server.compute.content.tooLarge");
                    translated.put("description",text(value));
                }
            }
            finish(job, token, translated);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            String key = exception instanceof BusinessException business && text(business.getMessage()).startsWith("server.")
                    ? business.getMessage() : PREFIX + "failed";
            fail(id, token, key);
            log.warn("内容翻译未完成，任务 {}，语言 {}，错误 {}", job.get("taskId"), job.get("language"), key);
        }
    }

    private String translatedText(String source, String language, String format) throws InterruptedException {
        // 该入口会显式抛出供应商失败或超时，不能使用会吞错返回原文的单文本便捷方法。
        Map<String, String> result = translator.translateToMultipleLanguagesParallel(source, List.of(language), format);
        String value = result.get(language);
        if (text(value).isEmpty()) value = result.get(language.split("-", 2)[0]);
        if (text(value).isEmpty()) throw new BusinessException(PREFIX + "empty");
        if ("text".equals(format) && text(value).length() > 200)
            throw new BusinessException("server.compute.content.tooLarge");
        return text(value);
    }

    private void finish(Map<String, Object> job, String token, Map<String, Object> values) {
        tx.executeWithoutResult(status -> {
            String resource=text(job.get("resourceType"));
            Map<String,Object> content=unscopedRow(resource,number(job.get("contentId")),true);
            Map<String, Object> item = repository.finishContentTranslationItemRow(job.get("id"));
            if (!"RUNNING".equals(item.get("status")) || !token.equals(item.get("runToken"))) return;
            if (enabled(content.get("deleted")) || number(content.get("contentVersion")) != number(job.get("contentVersion"))) {
                repository.finishContentTranslationItemWrite(PREFIX + "superseded", now(), item.get("id"));
            } else {
                String language = text(item.get("language"));
                if (!enabledLanguages().contains(language)) throw new BusinessException(PREFIX + "languageDisabled");
                Map<String, Object> all = translationMap(content.get("translationsJson"));
                seedSource(content,all);
                Map<String, Object> latest = fieldsFor(all, language);
                for (String field : List.of("title", "bodyHtml", "description"))
                    if (text(latest.get(field)).isEmpty() && !text(values.get(field)).isEmpty()) latest.put(field, values.get(field));
                all.put(language, latest);
                if (!complete(all, language, text(content.get("bodyHtml")))) throw new BusinessException(PREFIX + "empty");
                if("COMPANY_FILE".equals(resource)) {
                    Map<String,Object> names=new LinkedHashMap<>();
                    all.forEach((key,value)->{String title=text(fieldsFor(all,key).get("title"));if(!title.isEmpty())names.put(key,title);});
                    repository.finishCompanyFileWrite(data.json(names), content.get("id"));
                } else repository.saveResourceTranslations(resourceType(resource),number(content.get("id")),data.json(all));
                repository.enqueueContentTranslationItemWrite(now(), item.get("id"));
            }
            refreshTask(number(job.get("taskId")));
        });
    }

    private void fail(long id, String token, String key) {
        List<Map<String, Object>> rows = repository.failContentTranslationItemRows(id);
        if (rows.isEmpty()) return;
        Map<String, Object> item = rows.get(0);
        long attempts = number(item.get("attempts"));
        long seconds = Math.min(3600, 300L << Math.min(4, Math.max(0, attempts - 1)));
        repository.failContentTranslationItemWrite(now().plusSeconds(seconds), key, now(), id, token);
        refreshTask(number(item.get("taskId")));
    }

    private void refreshTask(long taskId) {
        Map<String, Object> task = repository.ensureEnglishContentTranslationTaskRow(taskId);
        if ("SUPERSEDED".equals(task.get("status"))) return;
        Map<String, Object> view = taskView(task);
        repository.refreshTaskContentTranslationTaskWrite(view.get("status"), now(), taskId);
    }

    private Map<String, Object> taskView(Map<String, Object> task) {
        List<Map<String, Object>> items = repository.taskViewContentTranslationItemRows(task.get("id"));
        int success = 0, failed = 0, superseded = 0;
        boolean running = false, pending = false;
        String errorKey = null;
        for (Map<String, Object> item : items) {
            String state = text(item.get("status"));
            if ("QUEUED".equals(state)) { state = "PENDING"; item.put("status", state); }
            switch (state) {
                case "SUCCEEDED" -> success++;
                case "FAILED" -> { failed++; if (errorKey == null) errorKey = text(item.get("errorKey")); }
                case "SUPERSEDED" -> superseded++;
                case "RUNNING" -> running = true;
                default -> pending = true;
            }
        }
        String state = "SUPERSEDED".equals(task.get("status")) || (!items.isEmpty() && superseded == items.size()) ? "SUPERSEDED"
                : running ? "RUNNING" : pending ? "PENDING" : failed == 0 ? "SUCCEEDED" : success == 0 ? "FAILED" : "PARTIAL";
        int finished = success + failed + superseded;
        return fields("taskId", task.get("id"),"resourceType",task.get("resourceType"), "contentId", task.get("contentId"), "contentVersion", task.get("contentVersion"),
                "status", state, "progressPercent", items.isEmpty() ? 100 : finished * 100 / items.size(),
                "totalLanguages", items.size(), "finishedLanguages", finished, "succeededLanguages", success,
                "failedLanguages", failed, "errorKey", errorKey, "createdAt", task.get("createdAt"), "updatedAt", task.get("updatedAt"), "items", items);
    }

    private static boolean expired(Object value) {
        return value == null || !Instant.parse(text(value)).isAfter(Instant.now());
    }

    private static boolean complete(Map<String, Object> translations, String language, String sourceBody) {
        Map<String, Object> value = fieldsFor(translations, language);
        return !text(value.get("title")).isEmpty() && (sourceBody.isEmpty() || !text(value.get("bodyHtml")).isEmpty());
    }

    private static void applyFields(Map<String, Object> row, Object value) {
        if (value instanceof Map<?, ?> fields) {
            row.put("title", text(fields.get("title")));
            row.put("bodyHtml", text(fields.get("bodyHtml")));
        }
    }

    private static Map<String, Object> fieldsFor(Map<String, Object> map, String language) {
        return map.get(language) instanceof Map<?, ?> values ? castMap(values) : new LinkedHashMap<>();
    }

    private static Map<String, Object> castMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(text(key), value));
        return result;
    }
}
