package org.token.service.impl;

import org.token.model.entity.NotificationTemplate;
import org.token.repository.system.NotificationTemplateRepository;
import org.token.repository.system.TranslationRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;
import java.util.*;
import java.util.regex.Pattern;

/** 只渲染已启用模板；变量作为文本转义，不把变量当作新的模板再次展开。 */
@Service
public class NotificationTemplateRenderer {
    private static final Pattern VARIABLES=Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]*)}}");
    private final NotificationTemplateRepository templates;
    private final TranslationRepository translations;
    public NotificationTemplateRenderer(NotificationTemplateRepository templates,TranslationRepository translations){this.templates=templates;this.translations=translations;}
    public record Rendered(String title,String content,String channelType) { }
    public record Preview(Rendered rendered, List<String> variables) { }
    public List<String> variables(NotificationTemplate template){
        return variables(template.getTitleTemplate(), template.getContentTemplate());
    }
    private List<String> variables(String title, String content) {
        var found=new LinkedHashSet<String>();
        for(String source:List.of(Objects.toString(title,""),Objects.toString(content,""))){
            var matcher=VARIABLES.matcher(source);while(matcher.find())found.add(matcher.group(1));
        }
        return List.copyOf(found);
    }
    public Rendered render(String code,String language,Map<String,?> variables) {
        Preview preview = preview(code, language, variables);
        return preview == null ? null : preview.rendered();
    }
    public Preview preview(String code,String language,Map<String,?> variables) {
        return preview(enabled(code), language, variables);
    }
    public Preview preview(NotificationTemplate template,String language,Map<String,?> variables) {
        if(template==null || !Integer.valueOf(1).equals(template.getEnabled()))return null;
        int type=switch(template.getChannelType()){case "EMAIL"->2;case "INTERNAL"->3;default->6;};
        String normalized=language==null?"en":language.trim().replace('_','-').toLowerCase(Locale.ROOT);
        var candidates=new LinkedHashSet<String>();
        if(normalized.matches("[a-z]{2,3}(?:-[a-z0-9]{2,8})*")){candidates.add(normalized);candidates.add(normalized.split("-")[0]);}
        candidates.add("en");
        String prefix="notification.template."+template.getCode().toLowerCase(Locale.ROOT);
        String title=localized(prefix+".title",type,candidates),content=localized(prefix+".content",type,candidates);
        // 迁移前已有原文仍可使用；数据库模板正文是明确配置，不根据当前行情或业务状态猜填。
        if(title==null)title=template.getTitleTemplate();
        if(content==null)content=template.getContentTemplate();
        boolean html=!"DINGTALK".equals(template.getChannelType());
        return new Preview(new Rendered(substitute(title,variables,false),substitute(content,variables,html),template.getChannelType()), variables(title, content));
    }
    public NotificationTemplate enabled(String code) {
        if(code==null||code.isBlank())return null;
        NotificationTemplate record=templates.findEnabled(code.trim());
        if(record!=null)return record;
        if(code.matches("T[1-9][0-9]{0,18}")) {
            try {record=templates.getById(Long.parseLong(code.substring(1)));}
            catch(NumberFormatException ignored){return null;}
            if(record!=null&&Integer.valueOf(1).equals(record.getEnabled()))return record;
        }
        return null;
    }
    private String localized(String key,int type,Set<String> languages) {
        var rows=translations.findLanguageCandidates(key,type,List.copyOf(languages));
        for(String lang:languages)for(var row:rows)if(lang.equals(row.getLang())&&StringUtils.hasText(row.getTvalue()))return row.getTvalue();
        return null;
    }
    static String substitute(String source,Map<String,?> variables,boolean html) {
        var matcher=VARIABLES.matcher(Objects.toString(source,""));
        StringBuilder result=new StringBuilder();
        while(matcher.find()) {
            String value=Objects.toString(variables==null?null:variables.get(matcher.group(1)),"");
            matcher.appendReplacement(result,java.util.regex.Matcher.quoteReplacement(html?HtmlUtils.htmlEscape(value):value));
        }
        matcher.appendTail(result);return result.toString();
    }
}
