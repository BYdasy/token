package org.token.service.compute;

import org.token.config.EnabledLocaleResolver;
import org.token.exception.BusinessException;
import java.util.*;
import static org.token.repository.compute.ComputeData.text;

/** 业务标题与 HTML 的合并规则；英文是必备回退，空白字段不会覆盖已有人工译文。 */
public final class ComputeBusinessTranslations {
    private ComputeBusinessTranslations() {}
    public static Map<String,Object> fields(Map<String,Object> all,String language) {
        Map<String,Object> result=new LinkedHashMap<>();
        if(all.get(language) instanceof Map<?,?> value)value.forEach((key,item)->result.put(text(key),item));
        return result;
    }
    public static boolean complete(Map<String,Object> all,String language,String sourceBody) {
        Map<String,Object> value=fields(all,language);
        return !text(value.get("title")).isEmpty() && (text(sourceBody).isEmpty() || !text(value.get("bodyHtml")).isEmpty());
    }
    public static Map<String,Object> merge(Map<String,Object> existing,Object input,Object clear,EnabledLocaleResolver locales) {
        Map<String,Object> result=new LinkedHashMap<>(existing);
        Set<String> enabled=new HashSet<>();
        locales.enabledLanguages().forEach(option->enabled.add(locales.normalize(option.value())));
        if(clear instanceof Collection<?> languages)for(Object value:languages){
            String language=locales.normalize(text(value));
            if("en".equals(language))throw new BusinessException("server.compute.content.translation.englishRequired");
            if(language.isEmpty())throw new BusinessException("server.compute.content.invalid");
            result.remove(language);
        }
        if(input instanceof Map<?,?> values)for(var entry:values.entrySet()) {
            String language=locales.normalize(text(entry.getKey()));
            if(!(entry.getValue() instanceof Map<?,?> value))throw new BusinessException("server.compute.content.languageDisabled");
            if(!enabled.contains(language)){
                if(Objects.equals(existing.get(language),entry.getValue()))continue;
                throw new BusinessException("server.compute.content.languageDisabled");
            }
            Map<String,Object> merged=fields(result,language);
            String title=text(value.get("title"));
            if(title.length()>200)throw new BusinessException("server.compute.content.tooLarge");
            if(!title.isEmpty())merged.put("title",title);
            if(!text(value.get("bodyHtml")).isEmpty())merged.put("bodyHtml",ComputeContentHtml.sanitize(text(value.get("bodyHtml"))));
            result.put(language,merged);
        }
        return result;
    }
    public static List<String> candidates(String language) {
        LinkedHashSet<String> result=new LinkedHashSet<>();
        if(!text(language).isEmpty()){result.add(language);result.add(language.split("-",2)[0]);}
        result.add("en");return new ArrayList<>(result);
    }
}
