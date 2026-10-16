package org.token.custom;

import org.token.model.entity.OperationLog;
import org.springframework.beans.BeanWrapperImpl;

import java.util.*;
import java.util.regex.Pattern;

/** 日志只收录固定白名单摘要；正文、凭据和文件内容永不序列化。 */
public final class OperationLogSanitizer {
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)((?:password|passwd|pwd|payPassword|oldPassword|newPassword|token|accessToken|refreshToken|secret|clientSecret|apiKey|privateKey|otpCode|totpCode|authorization|密码|密钥|验证码)[\\\"']?\\s*[:=：]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,，;；}]+)");
    private static final Pattern JWT = Pattern.compile("(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
    private static final Pattern PRIVATE_KEY = Pattern.compile("(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----");
    private static final List<String> TARGETS = List.of("id","uid","username","memberId","adminUid","roleId","groupId","productId","levelId","configId",
            "channelId","ruleId","taskId","operationId","orderNo","reference","resource","kind","type","event","provider","currency","network",
            "walletType","fromWallet","toWallet","amount","enabled","approved","status","mode","period","count","recipientCount","updated","version","tokenIncomePercent");
    private OperationLogSanitizer() { }

    public static String text(Object value,int limit) {
        if(value==null)return "";
        String result=String.valueOf(value);
        result=PRIVATE_KEY.matcher(result).replaceAll("[私钥已隐藏]");
        result=JWT.matcher(result).replaceAll("[凭据已隐藏]");
        result=result.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+","Bearer [已隐藏]");
        result=CREDENTIAL.matcher(result).replaceAll("$1[已隐藏]");
        result=result.replaceAll("(?i)(https?://[^\\s?#]+)[?#][^\\s]*","$1[参数已隐藏]");
        result=result.replaceAll("[\\p{Cntrl}\\u2028\\u2029]"," ").replaceAll("<[^>]*>","").trim();
        return result.length()<=limit?result:result.substring(0,Math.max(0,limit-1))+"…";
    }

    /** 不遍历任意Bean/嵌套对象，只读取明确列出的标量元数据。 */
    public static String summary(Object value) {
        if(value==null)return "";
        List<String> items=new ArrayList<>();
        for(String key:TARGETS) {
            Object field;
            try {
                if(value instanceof Map<?,?> map)field=map.get(key);
                else if(value.getClass().isRecord()) {
                    var component=Arrays.stream(value.getClass().getRecordComponents()).filter(item->item.getName().equals(key)).findFirst();
                    field=component.isEmpty()?null:component.get().getAccessor().invoke(value);
                } else if(value.getClass().getName().startsWith("org.token.")) {
                    var bean=new BeanWrapperImpl(value);field=bean.isReadableProperty(key)?bean.getPropertyValue(key):null;
                } else continue;
            } catch(ReflectiveOperationException|RuntimeException ignored) {continue;}
            if("tokenIncomePercent".equals(key) && !(field instanceof Number))continue;
            if(!(field instanceof Number || field instanceof Boolean || field instanceof CharSequence || field instanceof Enum<?>))continue;
            String scalar=text(field,120);
            if(!scalar.isEmpty() && scalar.matches("[\\p{L}\\p{N}_.,:/+@{}()\\[\\] -]+"))items.add(key+"="+scalar);
        }
        return text(String.join("，",items),1000);
    }

    /** 写入和读取都做同样的截断/脱敏，旧记录也不能通过接口再次泄漏凭据。 */
    public static void sanitize(OperationLog row) {
        row.setUsername(text(row.getUsername(),50)); row.setType(text(row.getType(),100));
        row.setLoginIp(text(row.getLoginIp(),100)); row.setClientos(text(row.getClientos(),50));
        row.setDomain(text(row.getDomain(),100)); row.setAddress(text(row.getAddress(),100));
        row.setDeviceId(text(row.getDeviceId(),255)); row.setRemarks(text(row.getRemarks(),4000));
    }
}
