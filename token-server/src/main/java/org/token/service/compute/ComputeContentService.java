package org.token.service.compute;

import org.token.repository.compute.ComputeContentRepository;

import org.token.service.compute.ComputeContentHtml;
import org.token.service.compute.ComputeBusinessTranslations;
import org.token.service.compute.ComputeTaskModes;
import org.token.service.compute.ComputeDisplayNames;

import lombok.extern.slf4j.Slf4j;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.token.config.EnabledLocaleResolver;
import org.token.exception.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import static org.token.repository.compute.ComputeData.*;

/** 内容启停复用定向异步投递和用户阅读状态；历史发布接口保留兼容。 */
@Service
@Slf4j
public class ComputeContentService {
    private final ComputeData data;
    private final ComputeContentRepository repository;
    private final ComputeAgentScope scope;
    private final EnabledLocaleResolver locales;
    private final TaskExecutor executor;
    private final TransactionTemplate tx;
    private final ComputeContentTranslationService translation;
    private static final Set<String> KINDS=Set.of("MESSAGE","TASK","ANNOUNCEMENT","ARTICLE");
    private static final Set<String> PUBLIC_KINDS=Set.of("ANNOUNCEMENT","ARTICLE");
    private static final Set<String> CONTENT_KEYS=Set.of("GENERAL","TERMS","PRIVACY","ABOUT","FAQ");
    private static final Set<String> FIXED_CONTENT_KEYS=Set.of("TERMS","PRIVACY","ABOUT");

    public ComputeContentService(JdbcTemplate jdbc,ComputeData data,ComputeAgentScope scope,EnabledLocaleResolver locales,
            @Qualifier("computeContentExecutor") TaskExecutor executor,PlatformTransactionManager manager) {
        this(jdbc,data,scope,locales,executor,manager,null);
    }

    public ComputeContentService(JdbcTemplate jdbc,ComputeData data,ComputeAgentScope scope,EnabledLocaleResolver locales,
            @Qualifier("computeContentExecutor") TaskExecutor executor,PlatformTransactionManager manager,
            ComputeContentTranslationService translation) {
        this(data,scope,locales,executor,manager,translation,new ComputeContentRepository(data));
    }

    @Autowired
    public ComputeContentService(ComputeData data,ComputeAgentScope scope,EnabledLocaleResolver locales,
            @Qualifier("computeContentExecutor") TaskExecutor executor,PlatformTransactionManager manager,
            ComputeContentTranslationService translation,ComputeContentRepository repository) {
        this.data=data;this.scope=scope;this.locales=locales;this.executor=executor;this.tx=new TransactionTemplate(manager);this.translation=translation;
        this.tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.repository=repository;
    }

    public Map<String,Object> adminList(Map<String,String> query) {
        String requestedKind=kind(query.get("kind"));
        String requestedKey=null;
        if(!text(query.get("contentKey")).isEmpty()){
            if(!"ARTICLE".equals(requestedKind))throw new BusinessException("server.compute.content.invalid");
            requestedKey=contentKey(query.get("contentKey"));
        }
        String keyword=text(query.get("keyword")).isEmpty()?null:limited(query.get("keyword"),200);
        return presentPage(repository.adminPage(scope.queryScope(),query,requestedKind,requestedKey,keyword,
                time(query.get("startTime")),time(query.get("endTime"))),null,true);
    }

    public Map<String,Object> adminDetail(long id) { var row=decode(adminRow(id,false),null,true);ComputeDisplayNames.administrators(data,List.of(row),Map.of("createdBy","createdByUsername"));return row; }

    private Map<String,Object> adminRow(long id,boolean lock) {
        List<Map<String,Object>> rows=repository.adminRows(id,lock,scope.queryScope());
        if(rows.isEmpty())throw new BusinessException(404,"server.compute.content.notFound");return rows.get(0);
    }

    public Map<String,Object> save(Map<String,Object> request,int operator) {
        ContentChange change=tx.execute(status->{
            scope.relations().lock();
            String kind=kind(request.get("kind"));if(PUBLIC_KINDS.contains(kind))scope.requireGlobalScope();
            long id=request.get("id")==null?0:number(request.get("id"));Map<String,Object> previous=id==0?null:adminRow(id,true);
            String contentKey="ARTICLE".equals(kind)?contentKey(request.getOrDefault("contentKey",previous==null?"GENERAL":previous.get("contentKey"))):"GENERAL";
            boolean fixed="ARTICLE".equals(kind)&&FIXED_CONTENT_KEYS.contains(contentKey);
            boolean wasFixed=previous!=null&&"ARTICLE".equals(previous.get("kind"))&&FIXED_CONTENT_KEYS.contains(text(previous.get("contentKey")));
            // 固定文章只能编辑安装增量建立的原记录；不能新增、转换用途或通过并发保存复制。
            if ((fixed&&(previous==null||!wasFixed)) || (wasFixed&&!contentKey.equals(previous.get("contentKey"))))
                throw new BusinessException("server.compute.v8.fixedContentProtected");
            if(previous!=null&&(!kind.equals(previous.get("kind"))||("PUBLISHING".equals(previous.get("status"))&&enabled(previous.get("enabled")))))throw new BusinessException("server.compute.content.stateInvalid");
            String sourceLanguage=locales.normalize(text(request.getOrDefault("sourceLanguage",previous==null?"zh":previous.getOrDefault("sourceLanguage","zh"))));
            if(!Set.of("zh","en").contains(sourceLanguage))throw new BusinessException("server.compute.content.invalid");
            String title=limited(request.get("title"),200);if(title.isBlank())throw new BusinessException("server.compute.content.titleRequired");
            String body=ComputeContentHtml.sanitize(text(request.get("bodyHtml")));
            String target=PUBLIC_KINDS.contains(kind)?"ALL":text(request.getOrDefault("targetType","ALL"));
            if(!Set.of("ALL","USER","GROUP","AGENT").contains(target))throw new BusinessException("server.compute.content.targetInvalid");
            if("TASK".equals(kind)&&"ALL".equals(target))throw new BusinessException("server.compute.v6.taskTargetRequired");
            List<Long> members=request.containsKey("memberUsernames")?resolveUsernames(request.get("memberUsernames")):memberIds(request.get("memberIds"));Long groupId=optionalId(request.get("groupId")),agentId=optionalId(request.get("targetMemberId"));
            if("AGENT".equals(target)&&request.containsKey("targetUsername")) {
                List<Long> agents=resolveUsernames(List.of(text(request.get("targetUsername"))));
                agentId=agents.isEmpty()?null:agents.get(0);
            }
            Long owner=previous==null?scope.currentRootMemberId():optionalId(previous.get("ownerMemberId"));
            boolean mapOwner=request.containsKey("ownerMemberId")&&!Objects.equals(owner,optionalId(request.get("ownerMemberId")));
            if(mapOwner&&previous!=null){if(!"DRAFT".equals(previous.get("status")))throw new BusinessException("server.compute.content.audienceLocked");scope.requireGlobalScope();}
            if(request.containsKey("ownerMemberId"))owner=optionalId(request.get("ownerMemberId"));
            if(owner==null)scope.requireGlobalScope();else scope.assertActiveMember(owner);
            if("USER".equals(target)) {
                if(members.isEmpty())throw new BusinessException("server.compute.content.targetInvalid");
                if(repository.activeMemberCount(members,scope.queryScope())!=members.size())throw ComputeAgentScope.denied();
            } else members=List.of();
            if("GROUP".equals(target)) {
                if(groupId==null)throw new BusinessException("server.compute.content.targetInvalid");
                if(groupId!=0){
                if(repository.activeGroupCount(groupId,scope.queryScope())!=1)throw new BusinessException("server.compute.content.targetInvalid");
                }
            } else groupId=null;
            if("AGENT".equals(target)){if(agentId==null)throw new BusinessException("server.compute.content.targetInvalid");scope.assertActiveMember(agentId);}else agentId=null;
            validateAudienceOwner(owner,target,members,groupId,agentId);
            String membersJson=data.json(members);
            if(previous!=null&&!"DRAFT".equals(previous.get("status"))&&(!target.equals(previous.get("targetType"))
                    ||!memberIds(previous.get("memberIds")).equals(members)||!Objects.equals(optionalId(previous.get("groupId")),groupId)||!Objects.equals(optionalId(previous.get("targetMemberId")),agentId)))
                throw new BusinessException("server.compute.content.audienceLocked");
            LocalDateTime start=time(request.get("startsAt")),end=time(request.get("endsAt"));
            if(start!=null&&end!=null&&!end.isAfter(start))throw new BusinessException("server.compute.content.timeInvalid");
            String taskType=text(request.getOrDefault("taskType","GENERAL"));if(!Set.of("GENERAL","INVESTMENT","WITHDRAWAL").contains(taskType))throw new BusinessException("server.compute.content.invalid");
            Map<String,Object> oldTranslations=previous==null?new LinkedHashMap<>():translationMap(previous.get("translationsJson"));
            Map<String,Object> translated=ComputeBusinessTranslations.merge(oldTranslations,request.get("translations"),request.get("clearTranslationLanguages"),locales);
            translated.put(sourceLanguage,Map.of("title",title,"bodyHtml",body));
            long contentVersion=previous==null?1:number(previous.get("contentVersion"));
            boolean sourceChanged=previous!=null&&(!title.equals(text(previous.get("title")))||!body.equals(text(previous.get("bodyHtml")))
                    ||!Objects.equals(ComputeBusinessTranslations.fields(oldTranslations,"en"),ComputeBusinessTranslations.fields(translated,"en")));
            if(sourceChanged)contentVersion++;
            boolean englishReady=ComputeBusinessTranslations.complete(translated,"en",body);
            if(!englishReady)throw new BusinessException("server.compute.content.translation.englishRequired");
            String jumpMode=text(request.getOrDefault("jumpMode",previous==null?"NEW_WINDOW":previous.getOrDefault("jumpMode","NEW_WINDOW")));
            if(!Set.of("NEW_WINDOW","INTERNAL").contains(jumpMode))throw new BusinessException("server.compute.content.urlInvalid");
            String jumpUrl="INTERNAL".equals(jumpMode)?ComputeContentHtml.safeInternalUrl(text(request.get("jumpUrl"))):ComputeContentHtml.safeUrl(text(request.get("jumpUrl")));
            boolean active=enabled(request.getOrDefault("enabled",previous==null?true:previous.get("enabled")));
            Map<String,Object> fields=fields("kind",kind,"title",title,"body_html",body,"translations_json",data.json(translated),
                    "content_key",contentKey,
                    "content_version",contentVersion,"source_language",sourceLanguage,
                    "enabled",active,"pinned",enabled(request.get("pinned")),"show_home",enabled(request.get("showHome")),
                    "home_popup_enabled",Set.of("ANNOUNCEMENT","MESSAGE").contains(kind)&&enabled(request.getOrDefault("homePopupEnabled",previous==null?false:previous.get("homePopupEnabled"))),
                    "sort",sort(request.get("sort")),"jump_url",jumpUrl,"jump_mode",jumpMode,"starts_at",start,"ends_at",end,
                    "task_type",taskType,"task_mode",ComputeTaskModes.resolve(request),"target_type",target,"member_ids",membersJson,"group_id",groupId,"target_member_id",agentId,"updated_at",now());
            if(mapOwner)fields.put("owner_member_id",owner);
            if(id==0){fields.put("owner_member_id",owner);fields.put("created_by",operator);fields.put("status","DRAFT");fields.put("created_at",now());id=data.insert("compute_content",fields);}
            else data.update("compute_content", id, fields);
            if(sourceChanged&&translation!=null)translation.supersede(id,contentVersion);
            return activateLocked(adminRow(id,true),active,previous!=null&&enabled(previous.get("enabled")));
        });
        return completeChange(change);
    }

    private record ContentChange(long id,boolean dispatch) { }

    public Map<String,Object> setEnabled(long id,boolean active) {
        ContentChange change=tx.execute(status->{
            scope.relations().lock();
            Map<String,Object> row=adminRow(id,true);
            return activateLocked(row,active,enabled(row.get("enabled")));
        });
        return completeChange(change);
    }

    /** 启停不清空投递游标、受众快照、已读和单用户解除记录；恢复投递只补齐原受众。 */
    private ContentChange activateLocked(Map<String,Object> row,boolean active,boolean wasEnabled) {
        long id=number(row.get("id"));
        if(PUBLIC_KINDS.contains(text(row.get("kind"))))scope.requireGlobalScope();
        if(!active){repository.activateLockedContentWrite(now(), id);return new ContentChange(id,false);}
        if("TASK".equals(row.get("kind"))&&"ALL".equals(row.get("targetType")))throw new BusinessException("server.compute.v6.taskTargetRequired");
        Map<String,Object> translated=translationMap(row.get("translationsJson"));ComputeContentTranslationService.seedSource(row,translated);
        if(!ComputeBusinessTranslations.complete(translated,"en",text(row.get("bodyHtml"))))throw new BusinessException("server.compute.content.translation.englishRequired");
        Long owner=optionalId(row.get("ownerMemberId"));if(owner!=null)scope.assertActiveMember(owner);
        validateAudienceOwner(owner,text(row.get("targetType")),memberIds(row.get("memberIds")),optionalId(row.get("groupId")),optionalId(row.get("targetMemberId")));
        String state=text(row.get("status"));
        if(PUBLIC_KINDS.contains(text(row.get("kind")))||"PUBLISHED".equals(state)){
            repository.activateLockedContentWrite2(now(), id);
            return new ContentChange(id,false);
        }
        if("PUBLISHING".equals(state)&&wasEnabled)return new ContentChange(id,false);
        if(!Set.of("DRAFT","FAILED","RECALLED","PUBLISHING").contains(state))throw new BusinessException("server.compute.content.stateInvalid");
        Long audienceMax=repository.activateLockedMemberValue();
        repository.activateLockedContentWrite3(audienceMax, now(), id);
        return new ContentChange(id,true);
    }

    private Map<String,Object> completeChange(ContentChange change) {
        if(change.dispatch()){
            try{executor.execute(()->deliver(change.id()));}
            catch(RuntimeException exception){repository.completeChangeContentWrite(now(), change.id());throw new BusinessException("server.compute.content.queueFull");}
        }
        return adminDetail(change.id());
    }

    /** 旧管理端的发布与召回仍可调用，但采用同一启停状态，避免双重生命周期。 */
    public Map<String,Object> publish(long id) { assertNotFixed(adminRow(id,false));return setEnabled(id,true); }
    public void recall(long id) { tx.executeWithoutResult(status->{scope.relations().lock();var row=adminRow(id,true);assertNotFixed(row);repository.recallContentWrite(now(), id);}); }
    public void delete(long id) { tx.executeWithoutResult(status->{scope.relations().lock();var row=adminRow(id,true);assertNotFixed(row);if(enabled(row.get("enabled")))throw new BusinessException("server.compute.v18.contentDisableFirst");repository.deleteContentWrite(now(), id);}); }

    private static void assertNotFixed(Map<String,Object> row) {
        if("ARTICLE".equals(row.get("kind"))&&FIXED_CONTENT_KEYS.contains(text(row.get("contentKey"))))
            throw new BusinessException("server.compute.v8.fixedContentProtected");
    }

    public Map<String,Object> recipients(long id,Map<String,String> query) {
        if(PUBLIC_KINDS.contains(text(adminRow(id,false).get("kind"))))throw new BusinessException("server.compute.content.invalid");
        String username=text(query.get("username")).isEmpty()?null:limited(query.get("username"),100);
        return repository.recipientPage(id,query,username,scope.queryScope());
    }

    /** 单独解除收件人的行为限制，保留投递、阅读与历史确认记录；重复操作不改写审计。 */
    public void releaseRestriction(long id,long memberId,int operator) {
        tx.executeWithoutResult(status->{
            scope.relations().lock();
            Map<String,Object> content=adminRow(id,true);
            if(!"TASK".equals(content.get("kind")))throw new BusinessException("server.compute.content.stateInvalid");
            if(repository.recipientCount(id,memberId,scope.queryScope())!=1)
                throw new BusinessException(404,"server.compute.content.notFound");
            repository.releaseRestrictionContentRecipientWrite(now(), operator, id, memberId);
        });
    }

    public Map<String,Object> userList(Long memberId,Map<String,String> query,String language) {
        boolean isPublic=memberId==null;String kind=kind(query.getOrDefault("kind",isPublic?"ANNOUNCEMENT":"MESSAGE"));
        if(PUBLIC_KINDS.contains(kind)!=isPublic)throw new BusinessException("server.compute.content.invalid");
        String view=text(query.get("view"));boolean helpView="HELP".equals(view);
        // HELP仅是帮助中心的列表视图，不是内容种类；不能借它读取其他用途或个人内容。
        if(!view.isEmpty()&&(!helpView||!"ARTICLE".equals(kind)||!text(query.get("contentKey")).isEmpty()))
            throw new BusinessException("server.compute.content.invalid");
        String requestedKey=null;
        if(!helpView&&"ARTICLE".equals(kind))requestedKey=contentKey(query.get("contentKey"));
        else if(!helpView&&!text(query.get("contentKey")).isEmpty())throw new BusinessException("server.compute.content.invalid");
        List<Integer> modes="TASK".equals(kind)&&!text(query.get("scene")).isEmpty()?ComputeTaskModes.scene(query.get("scene")):List.of();
        return presentPage(repository.userPage(memberId,query,kind,requestedKey,helpView,modes,now(),now()),language,false);
    }

    /** 首页弹窗只取当前用户可见的内容，独立阅读凭据不完成行为任务。 */
    public List<Map<String,Object>> publicHomePopups(String language) {
        return repository.publicHomePopupsContentRows(now(), now())
                .stream().map(row->decode(row,language,false)).toList();
    }

    public Map<String,Object> publicHomePopupPage(Map<String,String> query,String language) {
        return presentPage(repository.popupPage(null,query,now(),now()),language,false);
    }

    public Map<String,Object> homePopups(long memberId,Map<String,String> query,String language) {
        return presentPage(repository.popupPage(memberId,query,now(),now()),language,false);
    }

    public void markHomePopup(long id,long memberId,long contentVersion) {
        tx.executeWithoutResult(status->{
            Map<String,Object> row=repository.markHomePopupContentRow(id);
            userDetail(id,PUBLIC_KINDS.contains(text(row.get("kind")))?null:memberId,"en");
            if(!enabled(row.get("homePopupEnabled"))||!Set.of("MESSAGE","ANNOUNCEMENT").contains(text(row.get("kind"))))throw new BusinessException("server.compute.content.stateInvalid");
            if(contentVersion!=number(row.get("contentVersion")))throw new BusinessException("server.compute.v9.popupUpdated");
            repository.markHomePopupContentPopupSeenWrite(id, memberId, row.get("contentVersion"), now(), id, memberId, row.get("contentVersion"));
        });
    }

    private static String contentKey(Object value) {
        String key=text(value);if(key.isEmpty())return "GENERAL";
        if(!CONTENT_KEYS.contains(key))throw new BusinessException("server.compute.content.invalid");return key;
    }

    public Map<String,Object> userDetail(long id,Long memberId,String language) {
        return userDetail(id,memberId,language,Map.of());
    }

    public Map<String,Object> userDetail(long id,Long memberId,String language,Map<String,String> query) {
        String expected="",requestedKey=null;
        if(!text(query.get("kind")).isEmpty()) {
            expected=kind(query.get("kind"));
            if(PUBLIC_KINDS.contains(expected)!=(memberId==null))throw new BusinessException("server.compute.content.invalid");
            if("ARTICLE".equals(expected))requestedKey=contentKey(query.get("contentKey"));
            else if(!text(query.get("contentKey")).isEmpty())throw new BusinessException("server.compute.content.invalid");
        } else if(!text(query.get("contentKey")).isEmpty())throw new BusinessException("server.compute.content.invalid");
        List<Map<String,Object>> rows=repository.userRows(id,memberId,expected,requestedKey,now(),now());
        if(rows.isEmpty())throw new BusinessException(404,"server.compute.content.notFound");
        return decode(rows.get(0),language,false);
    }

    public void mark(long id,long memberId,boolean complete) {
        mark(id,memberId,complete,null);
    }

    public void mark(long id,long memberId,boolean complete,String scene) {
        // 兼容旧客户端的 complete 请求，仅记录历史确认；资金守卫不依赖 completed_at 放行。
        tx.executeWithoutResult(status->{
            repository.markContentRows(id);
            Map<String,Object> content=userDetail(id,memberId,"zh");if(complete&&!"TASK".equals(content.get("kind")))throw new BusinessException("server.compute.content.stateInvalid");
            if(complete&&scene!=null&&(!Set.of("INVEST","WITHDRAW").contains(scene)||!ComputeTaskModes.scene(scene).contains(ComputeTaskModes.resolve(content))))throw new BusinessException("server.compute.content.invalid");
            int read=repository.markContentRecipientWrite(now(), id, memberId);
            int completed=complete?repository.markContentRecipientWrite2(now(), id, memberId):0;
            if(read+completed>0)repository.markContentWrite(read, completed, id);
        });
    }

    /** 每批事务锁住内容，再投递最多二百名用户；召回、失败重试和重复执行均不重复投递。 */
    void deliver(long id) {
        try {
            boolean more=true;
            while(more)more=Boolean.TRUE.equals(tx.execute(status->{
                scope.relations().lock();
                Map<String,Object> row=repository.markHomePopupContentRow(id);
                if(!"PUBLISHING".equals(row.get("status"))||!enabled(row.get("enabled"))||enabled(row.get("deleted")))return false;
                Long memberOwner=optionalId(row.get("ownerMemberId"));
                if(memberOwner!=null&&repository.deliverMemberCount(memberOwner)!=1)throw ComputeAgentScope.denied();
                List<Long> ids="USER".equals(text(row.get("targetType")))?memberIds(row.get("memberIds")):List.of();
                if("USER".equals(text(row.get("targetType")))&&ids.isEmpty())throw new BusinessException("server.compute.content.targetInvalid");
                List<Long> members=repository.deliveryMembers(row,memberOwner,ids);
                if(members.isEmpty()){repository.deliverContentWrite(now(), id);return false;}
                int added=0;for(long member:members)added+=repository.deliverContentRecipientWrite(id, member, now(), id, member);
                repository.deliverContentWrite2(added, members.get(members.size()-1), now(), id);
                return true;
            }));
        } catch(Exception exception) {log.warn("内容投递失败，内容编号 {}",id,exception);repository.deliverContentWrite3(now(), id);}
    }

    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void recoverInterruptedDeliveries() {
        try {repository.recoverInterruptedDeliveriesContentWrite(now(), now().minusMinutes(15));}
        catch(org.springframework.dao.DataAccessException ignored){ /* 增量未执行时不影响旧服务启动；内容接口仍返回明确失败。 */ }
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> presentPage(Map<String,Object> page,String language,boolean admin) {
        List<Map<String,Object>> rows=((List<Map<String,Object>>)page.get("records")).stream().map(row->decode(row,language,admin)).toList();
        if(admin)ComputeDisplayNames.administrators(data,rows,Map.of("createdBy","createdByUsername"));
        return Map.of("records",rows,"total",page.get("total"));
    }

    private Map<String,Object> decode(Map<String,Object> row,String language,boolean admin) {
        if("TASK".equals(row.get("kind")))row.put("taskMode",ComputeTaskModes.resolve(row));
        Object raw=row.remove("translationsJson");Map<String,Object> translated=translationMap(raw);
        ComputeContentTranslationService.seedSource(row,translated);
        if(admin){row.put("translations",translated);row.put("englishReady",ComputeBusinessTranslations.complete(translated,"en",text(row.get("bodyHtml"))));List<Long> ids=memberIds(row.get("memberIds"));row.put("memberIds",ids);
            row.put("memberUsernames",repository.usernames(ids));
            if(row.get("targetMemberId")!=null){List<String> names=repository.decodeMemberRows(row.get("targetMemberId"));row.put("targetUsername",names.isEmpty()?"":names.get(0));}
            return row;}
        if(translation!=null&&language!=null)translation.applyUserTranslation(row,translated,language);
        else if(language!=null){
            String sourceBody=text(row.get("bodyHtml"));row.put("title","");row.put("bodyHtml","");row.put("translationStatus","PENDING");
            for(String candidate:ComputeBusinessTranslations.candidates(locales.resolve(language)))if(ComputeBusinessTranslations.complete(translated,candidate,sourceBody)){
                Map<String,Object> value=ComputeBusinessTranslations.fields(translated,candidate);row.put("title",value.get("title"));row.put("bodyHtml",value.get("bodyHtml"));row.put("translationLanguage",candidate);row.put("translationStatus","READY");break;
            }
        }
        for(String hidden:List.of("memberIds","groupId","ownerMemberId","targetMemberId","createdBy","processedId","audienceMaxId","errorKey","recipientCount","readCount","completedCount","targetType","deleted"))row.remove(hidden);
        return row;
    }

    private Map<String,Object> translationMap(Object raw) {
        if(translation!=null)return translation.translationMap(raw);
        Map<String,Object> translated=new LinkedHashMap<>();
        if(raw!=null)data.jsonMap(text(raw)).forEach((key,value)->{
            String language=locales.normalize(key);if(!language.isEmpty())translated.putIfAbsent(language,value);
        });
        return translated;
    }

    /** 操作员输入用户名；内部仍保存不可变会员编号，并沿既有权限范围验证。 */
    private List<Long> resolveUsernames(Object raw) {
        if(!(raw instanceof List<?> values)||values.size()>1000)throw new BusinessException("server.compute.content.targetInvalid");
        List<String> names=values.stream().map(ComputeData::text).filter(name->!name.isEmpty()).distinct().toList();
        if(names.isEmpty())return List.of();
        for(String name:names)if(name.length()>100)throw new BusinessException("server.compute.content.targetInvalid");
        List<Long> ids=repository.resolveUsernames(names);
        if(ids.size()!=names.size())throw new BusinessException("server.compute.content.targetInvalid");
        return ids;
    }

    private void validateAudienceOwner(Long owner,String target,List<Long> members,Long group,Long targetMember) {
        if(owner==null)return;
        if("USER".equals(target))for(long member:members)if(!scope.relations().contains(owner,member))throw new BusinessException("server.compute.content.ownerTargetMismatch");
        if("AGENT".equals(target)&&targetMember!=null&&!scope.relations().contains(owner,targetMember))throw new BusinessException("server.compute.content.ownerTargetMismatch");
        if("GROUP".equals(target)&&group!=null&&group!=0) {
            Map<String,Object> row=repository.validateAudienceOwnerMemberGroupRow(group);
            Long groupOwner=optionalId(row.get("ownerMemberId"));
            if(groupOwner!=null&&!scope.relations().contains(owner,groupOwner)&&!scope.relations().contains(groupOwner,owner))
                throw new BusinessException("server.compute.content.ownerTargetMismatch");
        }
    }

    private List<Long> memberIds(Object value) {
        if(value==null)return List.of();Object raw=value;
        if(value instanceof String json){try{raw=new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,List.class);}catch(Exception e){throw new BusinessException("server.compute.content.targetInvalid");}}
        if(!(raw instanceof Collection<?> collection)||collection.size()>500)throw new BusinessException("server.compute.content.targetInvalid");
        return collection.stream().map(ComputeData::number).filter(id->id>0).distinct().sorted().toList();
    }
    private static Long optionalId(Object value){return value==null||text(value).isEmpty()?null:number(value);}
    private static String kind(Object value){String kind=text(value);if(!KINDS.contains(kind))throw new BusinessException("server.compute.content.invalid");return kind;}
    private static String limited(Object value,int max){String result=text(value);if(result.length()>max)throw new BusinessException("server.compute.content.tooLarge");return result;}
    private static int sort(Object value){long result=value==null?0:number(value);if(result < -1000000 || result > 1000000)throw new BusinessException("server.compute.content.invalid");return (int)result;}
    private static LocalDateTime time(Object value){if(text(value).isEmpty())return null;try{return OffsetDateTime.parse(text(value)).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();}catch(Exception e){throw new BusinessException("server.compute.content.timeInvalid");}}
}
