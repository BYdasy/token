package org.token.repository.dao.compute;

import java.util.Map;

/** 翻译目标来自服务端资源白名单，表名和选择列不接受请求拼接。 */
public final class ComputeTranslationResourceSql {
    private ComputeTranslationResourceSql() { }

    private static String table(Object resource) {
        return switch(String.valueOf(resource)) {
            case "PRODUCT" -> "compute_product";
            case "LEVEL" -> "compute_level";
            case "FORM" -> "compute_translation_form";
            case "ACTIVITY" -> "compute_activity";
            case "COMPANY_FILE" -> "compute_company_file";
            case "CONTENT" -> "compute_content";
            default -> throw new IllegalArgumentException("未知翻译资源");
        };
    }

    public static String select(Map<String,Object> parameters) {
        String resource=String.valueOf(parameters.get("resource"));
        String columns=switch(resource) {
            case "CONTENT","FORM","COMPANY_FILE" -> "*";
            default -> "*,name AS title,COALESCE(description,'') AS body_html";
        };
        return "SELECT "+columns+" FROM "+table(resource)+" WHERE id=#{id}"
                +(Boolean.TRUE.equals(parameters.get("lock"))?" FOR UPDATE":"");
    }

    public static String update(Map<String,Object> parameters) {
        if("COMPANY_FILE".equals(parameters.get("resource")))throw new IllegalArgumentException("企业文件使用名称翻译字段");
        return "UPDATE "+table(parameters.get("resource"))+" SET translations_json=#{translations} WHERE id=#{id}";
    }
}
