package org.token.service.impl;

import org.token.config.compute.ComputeMcagProperties;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeAgentScopeRepository;
import org.token.repository.compute.ComputeData;
import org.token.repository.system.NotificationAutomationRepository;
import org.token.repository.system.NotificationTemplateRepository;
import org.token.service.NotificationService;
import org.token.service.compute.ComputeAgentScope;
import org.token.service.compute.ComputeMcagClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

/** 自动提醒仅消费已提交事实；外发不参与资金事务，持久领取防止多实例重复。 */
@Service
@lombok.extern.slf4j.Slf4j
public class NotificationAutomationService {
    private static final Map<String,String> TEMPLATES=Map.of("largeDeposit","LARGE_DEP","largeWithdrawal","LARGE_WD","withdrawalOverdue","WD_OVERDUE","energyLow","ENERGY_LOW");
    private static final Map<String,String> TG_TEMPLATES=Map.of("largeDeposit","L_DEP_TG","largeWithdrawal","L_WD_TG","withdrawalOverdue","WD_LATE_TG","energyLow","ENERGY_TG");
    private static final List<String> EVENTS=List.of("largeDeposit","largeWithdrawal","withdrawalOverdue","energyLow");
    private final NotificationAutomationRepository repository;
    private final ComputeData data;
    private final ComputeAgentScope scope;
    private final ComputeAgentScopeRepository scopes;
    private final NotificationTemplateRepository templates;
    private final NotificationTemplateRenderer renderer;
    private final NotificationService notifications;
    private final TelegramNotificationService telegram;
    private final ObjectProvider<ComputeMcagClient> clients;
    private final ComputeMcagProperties gateway;
    private final TransactionTemplate transaction;
    private long retryAt;
    public NotificationAutomationService(NotificationAutomationRepository repository,ComputeData data,ComputeAgentScope scope,
            ComputeAgentScopeRepository scopes,NotificationTemplateRepository templates,NotificationTemplateRenderer renderer,
            NotificationService notifications,TelegramNotificationService telegram,ObjectProvider<ComputeMcagClient> clients,
            ComputeMcagProperties gateway,PlatformTransactionManager manager) {
        this.repository=repository;this.data=data;this.scope=scope;this.scopes=scopes;this.templates=templates;this.renderer=renderer;
        this.notifications=notifications;this.telegram=telegram;this.clients=clients;this.gateway=gateway;
        transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Map<String,Object> settings() {
        if(!scope.isGlobalScope())return Map.of("global",false,"rules",Map.of(),"templates",List.of());
        var row=repository.configuration(false);
        Map<String,Object> saved=data.jsonMap(row.get("configJson")),rules=new LinkedHashMap<>();
        for(String event:EVENTS){var rule=rule(saved,event);rule.remove("enabledAt");rules.put(event,rule);}
        return ComputeData.fields("global",true,"version",row.get("revision"),"rules",rules,"templates",templates.findAllOrdered().stream()
                .filter(t->Set.of("INTERNAL","TELEGRAM").contains(t.getChannelType()))
                .map(t->ComputeData.fields("code",t.getCode(),"shortCode","T"+t.getId(),"name",t.getName(),"channelType",t.getChannelType(),"enabled",t.getEnabled())).toList());
    }
    public Map<String,Object> save(Map<String,Object> input) {
        scope.requireGlobalScope();
        long version=integer(input.get("version"),0,Long.MAX_VALUE);
        if(!(input.get("rules") instanceof Map<?,?> submitted))throw invalid();
        transaction.executeWithoutResult(status->{
            var row=repository.configuration(true);if(ComputeData.number(row.get("revision"))!=version)throw new BusinessException("server.notification.automation.changed");
            var old=data.jsonMap(row.get("configJson"));Map<String,Object> result=new LinkedHashMap<>();
            for(String event:EVENTS) {
                if(!(submitted.get(event) instanceof Map<?,?> raw))throw invalid();
                var previous=rule(old,event);var normalized=normalize(event,raw);
                if(Boolean.TRUE.equals(normalized.get("enabled"))) {
                    normalized.put("enabledAt",Boolean.TRUE.equals(previous.get("enabled"))&&previous.get("enabledAt")!=null?previous.get("enabledAt"):Instant.now().toString());
                    if(Boolean.TRUE.equals(normalized.get("popup")))requireTemplate(ComputeData.text(normalized.get("templateCode")),"INTERNAL");
                    if(normalized.get("channelId")!=null){requireTemplate(ComputeData.text(normalized.get("telegramTemplateCode")),"TELEGRAM");telegram.validateTargets((Long)normalized.get("channelId"),(String)normalized.get("targetMode"),ids(normalized.get("bindingIds")));}
                    if(!Boolean.TRUE.equals(normalized.get("popup"))&&normalized.get("channelId")==null)throw invalid();
                }
                result.put(event,normalized);
            }
            String document=data.json(ComputeData.fields("configuration",result,"collectionStartedAt",row.get("createdAt")));
            if(repository.save(document,scope.currentAdminUid(),ComputeData.now(),version)!=1)throw new BusinessException("server.notification.automation.changed");
        });
        return settings();
    }
    private void requireTemplate(String code,String channel){var template=renderer.enabled(code);if(template==null||!channel.equals(template.getChannelType()))throw new BusinessException("server.notification.template.notFound");}
    private Map<String,Object> rule(Map<String,Object> saved,String event) {
        Map<String,Object> result=defaults(event);
        if(saved.get(event) instanceof Map<?,?> raw)raw.forEach((key,value)->result.put(String.valueOf(key),value));
        return result;
    }
    static Map<String,Object> defaults(String event) {
        Map<String,Object> result=ComputeData.fields("enabled",false,"templateCode",TEMPLATES.get(event),"telegramTemplateCode",TG_TEMPLATES.get(event),"channelId",null,
                "targetMode","ALL","bindingIds",List.of(),"popup",true,"severity","WARNING");
        if("withdrawalOverdue".equals(event))result.put("minutes",30);
        else result.put("threshold","10000");
        if("energyLow".equals(event)){result.put("threshold","100");result.put("currency","TRX");}
        return result;
    }
    static Map<String,Object> normalize(String event,Map<?,?> raw) {
        if(!(raw.get("enabled") instanceof Boolean)||!(raw.get("popup") instanceof Boolean))throw invalid();
        var result=defaults(event);result.put("enabled",raw.get("enabled"));result.put("popup",raw.get("popup"));
        result.put("severity",NotificationServiceImpl.severity(ComputeData.text(raw.get("severity"))));
        for(String name:List.of("templateCode","telegramTemplateCode")){String value=ComputeData.text(raw.get(name));if(value.isBlank()||value.length()>100)throw invalid();result.put(name,value);}
        result.put("channelId",raw.get("channelId")==null||ComputeData.text(raw.get("channelId")).isBlank()?null:integer(raw.get("channelId"),1,Long.MAX_VALUE));
        String mode=ComputeData.text(raw.get("targetMode"));if(!Set.of("ALL","SELECTED").contains(mode))throw invalid();result.put("targetMode",mode);result.put("bindingIds",ids(raw.get("bindingIds")));
        if("withdrawalOverdue".equals(event))result.put("minutes",integer(raw.get("minutes"),1,525600));
        else {BigDecimal threshold=decimal(raw.get("threshold"));if(threshold.signum()<0||threshold.precision()>28||threshold.scale()>8)throw invalid();result.put("threshold",threshold.toPlainString());}
        if("energyLow".equals(event)){String currency=ComputeData.text(raw.get("currency"));if(!Set.of("TRX","USDT").contains(currency))throw invalid();result.put("currency",currency);}
        return result;
    }
    @Scheduled(fixedDelay=60000,initialDelay=15000)
    public void scan() {
        if(System.currentTimeMillis()<retryAt)return;
        try {
            var config=repository.configuration(false);long version=ComputeData.number(config.get("revision"));
            var saved=data.jsonMap(config.get("configJson"));
            for(String event:EVENTS) {
                var rule=rule(saved,event);if(!Boolean.TRUE.equals(rule.get("enabled"))||rule.get("enabledAt")==null)continue;
                try {
                    if("energyLow".equals(event))scanEnergy(rule,version);
                    else {
                        LocalDateTime since=utc(rule.get("enabledAt")),before=ComputeData.now().minusMinutes("withdrawalOverdue".equals(event)?integer(rule.get("minutes"),1,525600):0);
                        for(var source:repository.candidates(event,since,before,"withdrawalOverdue".equals(event)?BigDecimal.ZERO:decimal(rule.get("threshold")))) {
                            try {dispatch(event,ComputeData.text(source.get("id")),rule,source,version);}
                            catch(RuntimeException failure){log.warn("自动通知单个事件本轮失败：{}；保留未完成目标并继续其他事件；{}",event,failureDetails(failure));}
                        }
                    }
                }catch(RuntimeException failure){log.warn("自动通知规则本轮失败：{}；不影响业务订单；{}",event,failureDetails(failure));}
            }
        }catch(RuntimeException failure){retryAt=System.currentTimeMillis()+60_000;log.warn("自动通知配置暂不可用，60秒后重试；{}",failureDetails(failure));}
    }
    private void scanEnergy(Map<String,Object> rule,long version) {
        if(!gateway.isEnabled())return;var client=clients.getIfAvailable();if(client==null)return;
        Map<String,Object> account=client.request("energy-account/query",Map.of());
        // 未配置、陈旧或未知余额不能被当作0；只使用平台已保存的成功快照，不触发供应商刷新。
        if(account==null||!"READY".equals(account.get("status"))||!Boolean.TRUE.equals(account.get("configured"))||!Boolean.TRUE.equals(account.get("enabled")))return;
        if(!Set.of("GLOBAL","INDEPENDENT").contains(ComputeData.text(account.get("mode")))||ComputeData.text(account.get("provider_id")).isBlank())return;
        String currency=ComputeData.text(rule.get("currency"));Object raw=account.get("TRX".equals(currency)?"balance_trx":"balance_usdt");
        if(!(raw instanceof String text)||!text.matches("[0-9]{1,30}(?:\\.[0-9]{1,18})?"))return;
        BigDecimal balance=new BigDecimal(text),threshold=decimal(rule.get("threshold"));
        String source=transaction.execute(status->{
            if(ComputeData.number(repository.configuration(true).get("revision"))!=version)return null;
            String active=repository.energyState();
            if(balance.compareTo(threshold)>=0){repository.energyState(null,ComputeData.now());return null;}
            // 同供应商可切换绑定账号；只保存公开身份字段摘要，不把地址写入告警去重表。
            String identity=data.json(List.of(ComputeData.text(account.get("provider_id")),ComputeData.text(account.get("mode")),
                    ComputeData.text(account.get("name")),ComputeData.text(account.get("recharge_address")),currency));
            String prefix=org.token.service.compute.ComputeCredentials.digest(identity)+":";
            if(active==null||!active.startsWith(prefix)){active=prefix+UUID.randomUUID();repository.energyState(active,ComputeData.now());}
            return active;
        });
        if(source!=null)dispatch("energyLow",source,rule,ComputeData.fields("providerName",account.get("name"),"balance",balance.toPlainString(),"currency",currency,"threshold",threshold.toPlainString()),version);
    }
    private void dispatch(String event,String sourceKey,Map<String,Object> rule,Map<String,Object> source,long version) {
        if(repository.delivered(event,sourceKey,"EVENT"))return;
        Map<String,Object> variables=new LinkedHashMap<>(source);
        variables.putIfAbsent("currency","Token");variables.put("minutes",rule.get("minutes"));
        if(Boolean.TRUE.equals(rule.get("popup")))for(var candidate:repository.administrators()) {
            int uid=((Number)candidate.get("uid")).intValue();String target="ADMIN:"+uid;
            try {
                transaction.executeWithoutResult(status->{
                    if(!currentEvent(event,sourceKey,rule,version))return;
                    // 当前账号权限及绑定范围逐接收人复核，不复用调度开始时的授权快照。
                    var administrator=repository.administrator(uid);
                    if(!authorized(administrator,event,source.get("memberId")))return;
                    var rendered=renderer.render(ComputeData.text(rule.get("templateCode")),ComputeData.text(administrator.get("locale")),variables);
                    if(rendered==null||!"INTERNAL".equals(rendered.channelType())||repository.delivered(event,sourceKey,target))return;
                    repository.claim(event,sourceKey,target,ComputeData.now());
                    notifications.sendPopup(uid,rendered.title(),rendered.content(),ComputeData.text(rule.get("severity")));
                    repository.complete(event,sourceKey,target,"SENT",ComputeData.now());
                });
            }catch(org.springframework.dao.DuplicateKeyException duplicate){/* 另一实例已领取。 */}
        }
        if(rule.get("channelId")!=null) {
            long channel=integer(rule.get("channelId"),1,Long.MAX_VALUE);
            List<org.token.model.entity.NotificationTelegramBinding> targets;
            try{targets=telegram.validateTargets(channel,ComputeData.text(rule.get("targetMode")),ids(rule.get("bindingIds")));}
            catch(BusinessException unavailable) {
                // 只跳过已知的本地停用/缺失/选项失效；数据库异常与未知外部结果不得冒充成功。
                if(!Set.of("server.notification.channel.notFound","server.notification.channel.typeInvalid","server.notification.channel.disabled",
                        "server.notification.recipient.required","server.notification.telegram.targetsInvalid").contains(unavailable.getMessage()))throw unavailable;
                String skipped="TG_CHANNEL:"+channel;
                transaction.executeWithoutResult(status->{
                    if(!currentEvent(event,sourceKey,rule,version)||repository.delivered(event,sourceKey,skipped))return;
                    repository.claim(event,sourceKey,skipped,ComputeData.now());repository.complete(event,sourceKey,skipped,"SKIPPED",ComputeData.now());
                });
                targets=List.of();
            }
            for(var binding:targets) {
                String target="TG:"+binding.getId();
                var rendered=renderer.render(ComputeData.text(rule.get("telegramTemplateCode")),"zh",variables);
                if(rendered==null||!"TELEGRAM".equals(rendered.channelType()))continue;
                boolean claimed=Boolean.TRUE.equals(transaction.execute(status->{
                    if(!currentEvent(event,sourceKey,rule,version))return false;
                    if(repository.delivered(event,sourceKey,target))return false;
                    try {repository.claim(event,sourceKey,target,ComputeData.now());return true;}
                    catch(org.springframework.dao.DuplicateKeyException duplicate){return false;}
                }));
                if(!claimed)continue;
                String state="SENT";
                try{telegram.sendBound(channel,"SELECTED",List.of(binding.getId()),rendered.title(),rendered.content(),"zh");}
                catch(RuntimeException failure){state="FAILED";log.warn("自动通知外部投递失败：{}；已记录结果，不自动重发结果不明的消息；{}",event,failureDetails(failure));}
                String result=state;transaction.executeWithoutResult(status->repository.complete(event,sourceKey,target,result,ComputeData.now()));
            }
        }
        try{transaction.executeWithoutResult(status->{if(!currentEvent(event,sourceKey,rule,version))return;repository.claim(event,sourceKey,"EVENT",ComputeData.now());repository.complete(event,sourceKey,"EVENT","SENT",ComputeData.now());});}
        catch(org.springframework.dao.DuplicateKeyException duplicate){/* 已处理。 */}
    }
    private boolean currentEvent(String event,String source,Map<String,Object> rule,long version) {
        if(ComputeData.number(repository.configuration(true).get("revision"))!=version)return false;
        if("energyLow".equals(event))return source.equals(repository.energyState());
        return repository.eligible(event,Long.parseLong(source),utc(rule.get("enabledAt")),ComputeData.now().minusMinutes("withdrawalOverdue".equals(event)?integer(rule.get("minutes"),1,525600):0),
                "withdrawalOverdue".equals(event)?BigDecimal.ZERO:decimal(rule.get("threshold")));
    }
    boolean authorized(Map<String,Object> admin,String event,Object memberId) {
        if(admin==null||admin.isEmpty())return false;
        boolean superRole=ComputeData.enabled(admin.get("isSuper"));
        Set<String> codes=new HashSet<>(Arrays.stream(ComputeData.text(admin.get("permissions")).split(",")).map(String::trim).toList());
        Set<String> required="energyLow".equals(event)?Set.of("80203"):
                "largeDeposit".equals(event)?Set.of("30100","30101"):Set.of("30200","30201");
        if(!superRole&&Collections.disjoint(codes,required))return false;
        if(superRole)return true;
        int uid=((Number)admin.get("uid")).intValue();List<Long> roots=scopes.roots(uid);
        boolean global=roots.isEmpty()&&!scopes.configured(uid);
        if("energyLow".equals(event))return global;
        if(memberId==null)return false;
        return scopes.contains(ComputeData.number(memberId),global,true,scopes.activeRoots(roots));
    }
    static long integer(Object value,long minimum,long maximum){try{long result=new BigDecimal(ComputeData.text(value)).longValueExact();if(result>=minimum&&result<=maximum)return result;}catch(NumberFormatException|ArithmeticException ignored){}throw invalid();}
    static BigDecimal decimal(Object value){try{return new BigDecimal(ComputeData.text(value));}catch(NumberFormatException invalid){throw invalid();}}
    static List<Long> ids(Object value){if(!(value instanceof List<?> list)||list.size()>500)throw invalid();return list.stream().map(id->integer(id,1,Long.MAX_VALUE)).distinct().toList();}
    private static LocalDateTime utc(Object value){return LocalDateTime.ofInstant(Instant.parse(ComputeData.text(value)),ZoneOffset.UTC);}
    /** MyBatis/HTTP exception messages may contain SQL parameters or channel credentials. */
    static String failureDetails(Throwable failure) {
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable root=failure;String businessKey=null;
        for(Throwable cause=failure;cause!=null&&seen.size()<16&&seen.add(cause);cause=cause.getCause()) {
            root=cause;
            if(cause instanceof SQLException sql) {
                String state=sql.getSQLState();
                if(state==null||!state.matches("[A-Z0-9]{5}"))state="-";
                return "分类=DATABASE；SQLState="+state+"；数据库码="+sql.getErrorCode()+"；异常="+sql.getClass().getSimpleName();
            }
            if(cause instanceof BusinessException&&cause.getMessage()!=null&&cause.getMessage().matches("server\\.[A-Za-z0-9_.]{1,160}"))businessKey=cause.getMessage();
        }
        return "分类="+(businessKey==null?"RUNTIME":"BUSINESS")+"；异常="+root.getClass().getSimpleName()+(businessKey==null?"":"；业务键="+businessKey);
    }
    private static BusinessException invalid(){return new BusinessException("server.notification.automation.invalid");}
}
