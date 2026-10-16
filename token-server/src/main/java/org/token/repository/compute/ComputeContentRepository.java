package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeContentMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeContentService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeContentRepository {
    private final ComputeContentMapper mapper;

    @Autowired
    public ComputeContentRepository(ComputeContentMapper mapper) { this.mapper = mapper; }

    public ComputeContentRepository(ComputeData data) {
        this(data.mapper(ComputeContentMapper.class));
    }

    private Map<String,Object> parameters(String mode, Object... fields) {
        Map<String,Object> values=ComputeData.fields(fields);values.put("mode",mode);return values;
    }

    private Map<String,Object> page(Map<String,Object> parameters, Map<String,String> query) {
        int size=(int)Math.max(1,Math.min(100,ComputeData.number(query.getOrDefault("size","20"))));
        long offset=(Math.max(1,Math.min(100000,ComputeData.number(query.getOrDefault("page","1"))))-1)*size;
        Long total=mapper.queryCount(parameters);
        parameters.put("limit",size);parameters.put("offset",offset);
        return Map.of("records",ComputeData.normalizeRows(mapper.queryRows(parameters)),"total",total==null?0:total);
    }

    public Map<String,Object> adminPage(org.token.model.query.MemberScope scope, Map<String,String> query,
            String kind,String contentKey,String keyword,LocalDateTime start,LocalDateTime end) {
        return page(parameters("ADMIN_PAGE","scope",scope,"query",query,"kind",kind,"contentKey",contentKey,
                "keyword",keyword,"start",start,"end",end),query);
    }

    public List<Map<String,Object>> adminRows(long id,boolean lock,org.token.model.query.MemberScope scope) {
        return ComputeData.normalizeRows(mapper.queryRows(parameters("ADMIN_ROW","id",id,"lock",lock,"scope",scope)));
    }

    public long activeMemberCount(List<Long> ids,org.token.model.query.MemberScope scope) {
        return mapper.queryCount(parameters("ACTIVE_MEMBERS","ids",ids,"scope",scope));
    }

    public long activeGroupCount(long id,org.token.model.query.MemberScope scope) {
        return mapper.queryCount(parameters("ACTIVE_GROUP","id",id,"scope",scope));
    }

    public Map<String,Object> recipientPage(long id,Map<String,String> query,String username,org.token.model.query.MemberScope scope) {
        return page(parameters("RECIPIENT_PAGE","id",id,"query",query,"username",username,"scope",scope),query);
    }

    public long recipientCount(long id,long memberId,org.token.model.query.MemberScope scope) {
        return mapper.queryCount(parameters("RECIPIENT_COUNT","id",id,"memberId",memberId,"scope",scope));
    }

    public Map<String,Object> userPage(Long memberId,Map<String,String> query,String kind,String contentKey,
            boolean helpView,List<Integer> taskModes,LocalDateTime startNow,LocalDateTime endNow) {
        return page(parameters("USER_PAGE","memberId",memberId,"query",query,"kind",kind,"contentKey",contentKey,
                "helpView",helpView,"taskModes",taskModes,"startNow",startNow,"endNow",endNow),query);
    }

    public List<Map<String,Object>> userRows(long id,Long memberId,String kind,String contentKey,
            LocalDateTime startNow,LocalDateTime endNow) {
        return ComputeData.normalizeRows(mapper.queryRows(parameters("USER_ROW","id",id,"memberId",memberId,
                "kind",kind,"contentKey",contentKey,"startNow",startNow,"endNow",endNow)));
    }

    public Map<String,Object> popupPage(Long memberId,Map<String,String> query,LocalDateTime startNow,LocalDateTime endNow) {
        return page(parameters(memberId==null?"PUBLIC_POPUPS":"MEMBER_POPUPS","memberId",memberId,
                "startNow",startNow,"endNow",endNow),query);
    }

    public List<Long> deliveryMembers(Map<String,Object> content,Long memberOwner,List<Long> ids) {
        return RepositoryResults.columnList(mapper.queryRows(parameters("DELIVERY","content",content,"memberOwner",memberOwner,"ids",ids)),Long.class);
    }

    public List<String> usernames(List<Long> ids) { return ids.isEmpty()?List.of():mapper.usernames(ids); }
    public List<Long> resolveUsernames(List<String> names) { return names.isEmpty()?List.of():mapper.resolveUsernames(names); }

    public int activateLockedContentWrite(Object... args) {
        return mapper.activateLockedContentWrite(args);
    }

    public int activateLockedContentWrite2(Object... args) {
        return mapper.activateLockedContentWrite2(args);
    }

    public Long activateLockedMemberValue(Object... args) {
        return RepositoryResults.scalar(mapper.activateLockedMemberValue(args), Long.class);
    }

    public int activateLockedContentWrite3(Object... args) {
        return mapper.activateLockedContentWrite3(args);
    }

    public int completeChangeContentWrite(Object... args) {
        return mapper.completeChangeContentWrite(args);
    }

    public int recallContentWrite(Object... args) {
        return mapper.recallContentWrite(args);
    }

    public int deleteContentWrite(Object... args) {
        return mapper.deleteContentWrite(args);
    }

    public int releaseRestrictionContentRecipientWrite(Object... args) {
        return mapper.releaseRestrictionContentRecipientWrite(args);
    }

    public List<Map<String, Object>> publicHomePopupsContentRows(Object... args) {
        return ComputeData.normalizeRows(mapper.publicHomePopupsContentRows(args));
    }

    public Map<String, Object> markHomePopupContentRow(Object... args) {
        return RepositoryResults.required(mapper.markHomePopupContentRow(args));
    }

    public int markHomePopupContentPopupSeenWrite(Object... args) {
        return mapper.markHomePopupContentPopupSeenWrite(args);
    }

    public List<Map<String, Object>> markContentRows(Object... args) {
        return RepositoryResults.rawRows(mapper.markContentRows(args));
    }

    public int markContentRecipientWrite(Object... args) {
        return mapper.markContentRecipientWrite(args);
    }

    public int markContentRecipientWrite2(Object... args) {
        return mapper.markContentRecipientWrite2(args);
    }

    public int markContentWrite(Object... args) {
        return mapper.markContentWrite(args);
    }

    public Integer deliverMemberCount(Object... args) {
        return RepositoryResults.scalar(mapper.deliverMemberCount(args), Integer.class);
    }

    public int deliverContentWrite(Object... args) {
        return mapper.deliverContentWrite(args);
    }

    public int deliverContentRecipientWrite(Object... args) {
        return mapper.deliverContentRecipientWrite(args);
    }

    public int deliverContentWrite2(Object... args) {
        return mapper.deliverContentWrite2(args);
    }

    public int deliverContentWrite3(Object... args) {
        return mapper.deliverContentWrite3(args);
    }

    public int recoverInterruptedDeliveriesContentWrite(Object... args) {
        return mapper.recoverInterruptedDeliveriesContentWrite(args);
    }

    public List<String> decodeMemberRows(Object... args) {
        return RepositoryResults.columnList(mapper.decodeMemberRows(args), String.class);
    }

    public Map<String, Object> validateAudienceOwnerMemberGroupRow(Object... args) {
        return RepositoryResults.required(mapper.validateAudienceOwnerMemberGroupRow(args));
    }
}
