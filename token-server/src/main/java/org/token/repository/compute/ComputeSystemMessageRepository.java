package org.token.repository.compute;

import java.util.*;
import java.math.*;
import java.time.*;
import org.token.repository.support.RepositoryResults;
import org.token.repository.support.AnnotationMapperFactory;
import org.token.repository.dao.compute.ComputeSystemMessageMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** ComputeSystemMessageService 的数据读取与写入；事务由完整业务流程管理。 */
@Repository
public class ComputeSystemMessageRepository {
    private final ComputeSystemMessageMapper mapper;

    @Autowired
    public ComputeSystemMessageRepository(ComputeSystemMessageMapper mapper) { this.mapper = mapper; }

    public ComputeSystemMessageRepository(ComputeData data) {
        this(data.mapper(ComputeSystemMessageMapper.class));
    }

    public List<Map<String, Object>> settingsSystemMessageTemplateRows(Object... args) {
        return ComputeData.normalizeRows(mapper.settingsSystemMessageTemplateRows(args));
    }

    public Map<String, Object> updateTranslationKeySystemMessageTemplateRow(Object... args) {
        return RepositoryResults.required(mapper.updateTranslationKeySystemMessageTemplateRow(args));
    }

    public List<Map<String, Object>> templatesSystemMessageTemplateRows(Object... args) {
        return ComputeData.normalizeRows(mapper.templatesSystemMessageTemplateRows(args));
    }

    public Integer saveTranslateLanguageCount(Object... args) {
        return RepositoryResults.scalar(mapper.saveTranslateLanguageCount(args), Integer.class);
    }

    public int saveSystemMessageTemplateWrite(Object... args) {
        return mapper.saveSystemMessageTemplateWrite(args);
    }

    public Map<String, Object> depositApprovedDepositRow(Object... args) {
        return RepositoryResults.required(mapper.depositApprovedDepositRow(args));
    }

    public Map<String, Object> withdrawalCompletedWithdrawalRow(Object... args) {
        return RepositoryResults.required(mapper.withdrawalCompletedWithdrawalRow(args));
    }

    public List<Map<String, Object>> deliverSystemMessageTemplateRows(Object... args) {
        return ComputeData.normalizeRows(mapper.deliverSystemMessageTemplateRows(args));
    }

    public int deliverSystemMessageDeliveryWrite(Object... args) {
        return mapper.deliverSystemMessageDeliveryWrite(args);
    }

    public Map<String, Object> deliverMemberRow(Object... args) {
        return RepositoryResults.required(mapper.deliverMemberRow(args));
    }

    public int deliverContentRecipientWrite(Object... args) {
        return mapper.deliverContentRecipientWrite(args);
    }

    public int deliverSystemMessageDeliveryWrite2(Object... args) {
        return mapper.deliverSystemMessageDeliveryWrite2(args);
    }

    public List<Map<String, Object>> translationsTranslatesRows(Object... args) {
        return ComputeData.normalizeRows(mapper.translationsTranslatesRows(args));
    }

    public List<Map<String, Object>> upsertTranslatesRows(Object... args) {
        return ComputeData.normalizeRows(mapper.upsertTranslatesRows(args));
    }

    public int upsertTranslatesWrite(Object... args) {
        return mapper.upsertTranslatesWrite(args);
    }

    public int upsertTranslatesWrite2(Object... args) {
        return mapper.upsertTranslatesWrite2(args);
    }
}
