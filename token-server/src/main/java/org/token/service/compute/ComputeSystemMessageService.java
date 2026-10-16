package org.token.service.compute;

import org.token.repository.compute.ComputeSystemMessageRepository;

import lombok.RequiredArgsConstructor;
import org.token.exception.BusinessException;
import org.token.model.entity.Translation;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeContentHtml;
import org.token.util.EnglishTranslationValidator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.token.repository.compute.ComputeData.*;

/** 用户系统消息复用通知消息模板6与内容收件表；业务提交和消息快照保持同一事务。 */
@Service
public class ComputeSystemMessageService {
    @org.springframework.beans.factory.annotation.Autowired
    public ComputeSystemMessageService(ComputeData data, ComputeSystemMessageRepository repository) {
        this.data = data;
        this.repository = repository;
    }

    public ComputeSystemMessageService(ComputeData data) {
        this(data, new ComputeSystemMessageRepository(data));
    }

    public static final List<String> EVENTS = List.of("WELCOME", "DEPOSIT_APPROVED", "WITHDRAWAL_COMPLETED");
    private final ComputeData data;
    private final ComputeSystemMessageRepository repository;

    public static final List<String> KEYS=List.of("user.systemMessage.welcome.title","user.systemMessage.welcome.body",
            "user.systemMessage.deposit_approved.title","user.systemMessage.deposit_approved.body",
            "user.systemMessage.withdrawal_completed.title","user.systemMessage.withdrawal_completed.body");
    public static boolean systemKey(String key){return KEYS.contains(key);}
    public static String eventForKey(String key){if(!systemKey(key))throw new BusinessException("server.compute.invalidInput");return key.split("\\.")[2].toUpperCase(Locale.ROOT);}
    public Map<String,Boolean> settings(){Map<String,Boolean> values=new LinkedHashMap<>();for(var row:repository.settingsSystemMessageTemplateRows())values.put(text(row.get("eventCode")),enabled(row.get("enabled")));return values;}

    /** 通用键值编辑保持固定键、格式及另一半标题/正文，只修改提交语言，英文整对校验。 */
    @Transactional
    public void updateTranslationKey(Translation request) {
        String key=text(request.getTkey()),event=eventForKey(key);
        int prop=key.endsWith(".body")?1:0;
        if(!Integer.valueOf(6).equals(request.getType())||!Integer.valueOf(prop).equals(request.getProp()))throw new BusinessException("server.v7.translation.systemTemplateProtected");
        repository.updateTranslationKeySystemMessageTemplateRow(event);
        Map<String,Object> all=translations(event);
        Map<String,String> updates=new LinkedHashMap<>(request.getTranslations()==null?Map.of():request.getTranslations());
        updates.put("zh",text(request.getTvalue()));
        Map<String,Object> patch=new LinkedHashMap<>();
        updates.forEach((language,value)->{String lang=text(language).toLowerCase(Locale.ROOT).replace('_','-');Map<String,Object> fields=languageFields(all,lang);fields.put(prop==1?"bodyHtml":"title",value);patch.put(lang,fields);});
        Map<String,Object> body=fields("translations",patch);
        if(request.getSystemMessageEnabled()!=null)body.put("enabled",request.getSystemMessageEnabled());
        save(event,body);
    }

    public List<Map<String,Object>> templates() {
        return repository.templatesSystemMessageTemplateRows().stream().map(row->{
            String event=text(row.get("eventCode"));
            row.put("translations", translations(event));
            row.put("placeholders", "WELCOME".equals(event)?List.of("username"):List.of("username","amount","currency","orderId"));
            return row;
        }).toList();
    }

    @Transactional
    public Map<String,Object> save(String event, Map<String,Object> request) {
        requireEvent(event);
        repository.updateTranslationKeySystemMessageTemplateRow(event);
        Map<String,Object> merged=new LinkedHashMap<>(translations(event));
        if(request.get("translations") instanceof Map<?,?> values)for(var entry:values.entrySet()) {
            String language=text(entry.getKey()).toLowerCase(Locale.ROOT).replace('_','-');
            if(!language.matches("[a-z]{2,3}(?:-[a-z0-9]{2,8})*") || !(entry.getValue() instanceof Map<?,?> fields))throw new BusinessException("server.compute.invalidInput");
            if(repository.saveTranslateLanguageCount(language)==0)
                throw new BusinessException("server.compute.content.languageDisabled");
            String title=text(fields.get("title")),body=text(fields.get("bodyHtml"));
            if(title.length()>200||body.length()>65535)throw new BusinessException("server.compute.content.tooLarge");
            if("en".equals(language)) {EnglishTranslationValidator.require(title,2);EnglishTranslationValidator.require(body,1);}
            Map<String,Object> fieldsMerged=languageFields(merged,language);
            if(!title.isBlank())fieldsMerged.put("title",title);
            if(!body.isBlank())fieldsMerged.put("bodyHtml",ComputeContentHtml.sanitize(body));
            if(!fieldsMerged.isEmpty())merged.put(language,fieldsMerged);
        }
        for(String language:List.of("zh","en")) {
            Map<String,Object> value=languageFields(merged,language);
            if("zh".equals(language) && (!EnglishTranslationValidator.hasContent(text(value.get("title")),2)
                    || !EnglishTranslationValidator.hasContent(text(value.get("bodyHtml")),1)))
                throw new BusinessException("server.v6.messages.sourceRequired");
            EnglishTranslationValidator.require(text(value.get("title")),2);
            EnglishTranslationValidator.require(text(value.get("bodyHtml")),1);
        }
        for(var entry:merged.entrySet()) {
            Map<String,Object> value=languageFields(merged,entry.getKey());
            upsert(key(event,"title"),entry.getKey(),text(value.get("title")),0);
            upsert(key(event,"body"),entry.getKey(),text(value.get("bodyHtml")),1);
        }
        if(request.containsKey("enabled")) repository.saveSystemMessageTemplateWrite(enabled(request.get("enabled")), now(), event);
        return fields("eventCode",event,"translations",merged);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void welcome(long memberId) { deliver("WELCOME",memberId,memberId,Map.of()); }

    @Transactional(propagation=Propagation.MANDATORY)
    public void depositApproved(long depositId,long memberId,BigDecimal amount,String currency) {
        String orderNo=text(repository.depositApprovedDepositRow(depositId).get("orderNo"));
        deliver("DEPOSIT_APPROVED",depositId,memberId,Map.of("amount",amount.toPlainString(),"currency",currency,"orderId",orderNo));
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void withdrawalCompleted(long withdrawalId,long memberId,BigDecimal amount,String currency) {
        String orderNo=text(repository.withdrawalCompletedWithdrawalRow(withdrawalId).get("orderNo"));
        deliver("WITHDRAWAL_COMPLETED",withdrawalId,memberId,Map.of("amount",amount.toPlainString(),"currency",currency,"orderId",orderNo));
    }

    private void deliver(String event,long sourceId,long memberId,Map<String,String> variables) {
        List<Map<String,Object>> configs=repository.deliverSystemMessageTemplateRows(event);
        if(configs.isEmpty()||!enabled(configs.get(0).get("enabled")))return;
        Map<String,Object> saved=translations(event),english=languageFields(saved,"en");
        EnglishTranslationValidator.require(text(english.get("title")),2);
        EnglishTranslationValidator.require(text(english.get("bodyHtml")),1);
        try { repository.deliverSystemMessageDeliveryWrite(event, sourceId, memberId, now()); }
        catch(DuplicateKeyException duplicate) { return; }
        Map<String,String> values=new LinkedHashMap<>(variables);
        values.put("username",text(repository.deliverMemberRow(memberId).get("username")));
        Map<String,Object> localized=new LinkedHashMap<>();
        saved.forEach((language,raw)->{
            Map<String,Object> value=languageFields(saved,language);
            if(!EnglishTranslationValidator.hasContent(text(value.get("title")),0)||!EnglishTranslationValidator.hasContent(text(value.get("bodyHtml")),1))return;
            localized.put(language,fields("title",render(text(value.get("title")),values,false),"bodyHtml",ComputeContentHtml.sanitize(render(text(value.get("bodyHtml")),values,true))));
        });
        Map<String,Object> source=languageFields(localized,"zh");
        if(source.isEmpty())source=languageFields(localized,"en");
        long contentId=data.insert("compute_content",fields("kind","MESSAGE","title",source.get("title"),"body_html",source.get("bodyHtml"),
                "translations_json",data.json(localized),"enabled",1,"target_type","USER","member_ids",data.json(List.of(memberId)),
                "created_by",0,"status","PUBLISHED","recipient_count",1,"created_at",now(),"updated_at",now()));
        repository.deliverContentRecipientWrite(contentId, memberId, now());
        repository.deliverSystemMessageDeliveryWrite2(contentId, event, sourceId);
    }

    private Map<String,Object> translations(String event) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(var row:repository.translationsTranslatesRows(key(event,"title"), key(event,"body"))) {
            String language=text(row.get("lang")).toLowerCase(Locale.ROOT).replace('_','-');
            @SuppressWarnings("unchecked") Map<String,Object> value=(Map<String,Object>)result.computeIfAbsent(language,ignored->new LinkedHashMap<>());
            value.put(text(row.get("tkey")).endsWith(".title")?"title":"bodyHtml",row.get("tvalue"));
        }
        return result;
    }

    private void upsert(String key,String language,String value,int prop) {
        List<Map<String,Object>> rows=repository.upsertTranslatesRows(key, language);
        if(rows.isEmpty())repository.upsertTranslatesWrite(key, language, value, prop, "系统用户通知模板", now());
        else repository.upsertTranslatesWrite2(value, prop, now(), rows.get(0).get("id"));
    }
    private static String key(String event,String field){requireEvent(event);return "user.systemMessage."+event.toLowerCase(Locale.ROOT)+"."+field;}
    private static void requireEvent(String event){if(!EVENTS.contains(event))throw new BusinessException("server.compute.invalidInput");}
    private static Map<String,Object> languageFields(Map<String,Object> all,String lang){Map<String,Object> result=new LinkedHashMap<>();if(all.get(lang) instanceof Map<?,?> value)value.forEach((key,item)->result.put(text(key),item));return result;}
    private static String render(String template,Map<String,String> values,boolean html){String result=template;for(var value:values.entrySet())result=result.replace("{"+value.getKey()+"}",html?HtmlUtils.htmlEscape(value.getValue()):value.getValue());return result;}
    private static LocalDateTime now(){return LocalDateTime.now(ZoneOffset.UTC);}
}
