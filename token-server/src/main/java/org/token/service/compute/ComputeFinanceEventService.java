package org.token.service.compute;

import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.token.repository.system.NotificationAutomationRepository;
import org.token.service.NotificationPushService;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/** 已提交新订单先串行生成事件序号，避免业务ID先分配后提交造成声音游标漏单。 */
@Service
@lombok.extern.slf4j.Slf4j
public class ComputeFinanceEventService {
    private final NotificationAutomationRepository repository;
    private final ComputeAgentScope scope;
    private final NotificationPushService push;
    private final TransactionTemplate transaction;
    private long retryAt;
    public ComputeFinanceEventService(NotificationAutomationRepository repository,ComputeAgentScope scope,NotificationPushService push,PlatformTransactionManager manager){
        this.repository=repository;this.scope=scope;this.push=push;transaction=new TransactionTemplate(manager);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Scheduled(fixedDelay=5000,initialDelay=5000)
    public void collect() {
        if(System.currentTimeMillis()<retryAt)return;
        try {
            int collected=Objects.requireNonNullElse(transaction.execute(status->{
                var config=repository.configuration(true);
                if(config==null||config.isEmpty())return 0;
                LocalDateTime since=LocalDateTime.ofInstant(Instant.parse(Objects.toString(config.get("createdAt"))),ZoneOffset.UTC);
                int count=0;
                for(boolean withdrawal:List.of(false,true))count+=repository.collectEvents(withdrawal,since,ComputeData.now());
                return count;
            }),0);
            if(collected>0)push.financeOrders();
        }catch(RuntimeException failure){retryAt=System.currentTimeMillis()+60_000;log.warn("新订单通知采集暂不可用，60秒后重试；不影响订单处理");}
    }
    public Map<String,Object> events(boolean withdrawal,Long after,String previousScope,int limit) {
        if(after!=null&&after<0||limit<1||limit>200)throw new BusinessException("server.compute.invalidInput");
        var allowed=scope.queryScope();
        String fingerprint=ComputeCredentials.digest(scope.currentAdminUid()+":"+allowed.global()+":"+allowed.roots()+":"+scope.relations().version());
        Instant started=null;
        if(previousScope!=null&&previousScope.startsWith(fingerprint+":")) {
            try{started=Instant.parse(previousScope.substring(fingerprint.length()+1));}catch(java.time.format.DateTimeParseException ignored){}
        }
        // 首次或换范围时记下静默基线；采集稍后完成也不能补响基线前已创建的旧单。
        if(after==null||started==null)return Map.of("records",List.of(),"cursor",repository.maximum(withdrawal,allowed),"hasMore",false,"scopeVersion",fingerprint+":"+Instant.now());
        String version=previousScope;
        var rows=repository.events(withdrawal,allowed,after,limit+1,LocalDateTime.ofInstant(started,ZoneOffset.UTC));
        boolean more=rows.size()>limit;
        if(more)rows=new ArrayList<>(rows.subList(0,limit));
        long cursor=after;
        for(var row:rows){cursor=((Number)row.get("id")).longValue();row.put("eventKey",(withdrawal?"withdrawal:":"deposit:")+row.remove("sourceId"));}
        return Map.of("records",rows,"cursor",cursor,"hasMore",more,"scopeVersion",version);
    }
}
