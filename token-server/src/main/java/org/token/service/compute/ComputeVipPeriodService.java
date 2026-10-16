package org.token.service.compute;

import org.token.repository.compute.ComputeData;
import org.token.repository.compute.ComputeVipRepository;
import org.token.service.SystemSettingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.token.repository.compute.ComputeData.*;

/** VIP 等级周期与产品订单无关；变更等级只影响后续算力，历史规则快照继续履约。 */
@Service
public class ComputeVipPeriodService {
    private final ComputeVipRepository repository;
    private final ComputeMemberRelations relations;
    private final SystemSettingService settings;

    public ComputeVipPeriodService(ComputeData data, SystemSettingService settings) {
        this(new ComputeVipRepository(data.jdbc()),new ComputeMemberRelations(data.jdbc()),settings);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ComputeVipPeriodService(ComputeVipRepository repository,ComputeMemberRelations relations,SystemSettingService settings) {
        this.repository=repository;this.relations=relations;this.settings=settings;
    }

    public ZoneId zone() {
        return settings == null ? ZoneOffset.UTC : ZoneId.of(settings.resolveSystemTimeZone());
    }

    @Transactional
    public void initialize(long memberId, long levelId) {
        repository.lockMemberId(memberId);
        Map<String, Object> level = repository.level(levelId,false);
        LocalDateTime start = now();
        repository.initialize(memberId,start,expiry(start,level));
    }

    @Transactional
    public void changeLevel(long memberId, long levelId) {
        Map<String, Object> member = repository.lockMember(memberId);
        if (number(member.get("level")) == levelId) return;
        Map<String, Object> level = repository.level(levelId,true);
        LocalDateTime start = now();
        // KEEP 沿用已有起止时间（包括已初始化的长期周期）；从未设置过周期时按目标等级建立。
        boolean hasPeriod = member.get("vipExpiresAt") != null || member.get("vipStartedAt") != null;
        if ("KEEP".equals(text(level.get("vipCyclePolicy"))) && hasPeriod) {
            repository.keepPeriod(memberId,levelId,start);
        } else {
            repository.resetPeriod(memberId,levelId,start,expiry(start,level));
        }
    }

    /** 后台加款与定时升级共用门槛和周期生成，资格判断始终处于关系锁、会员锁内。 */
    @Transactional
    public boolean upgradeEligible(long memberId) {
        relations.lock();
        // 与等级设置保存共用关系锁，逐级开关当前读；旧全局配置不再参与自动升级决策。
        Map<String, Object> member = repository.lockMemberLevel(memberId);
        var levels = repository.lockAutomaticLevels();
        var eligibility = new ComputeVipEligibility(repository);
        var level = ComputeVipEligibility.highestEligible(eligibility.automaticLevels(memberId, levels), number(member.get("level")),
                eligibility.availableComputeBalance(memberId));
        if (level == null) return false;
        changeLevel(memberId, number(level.get("levelId")));
        return true;
    }

    private LocalDateTime expiry(LocalDateTime start, Map<String, Object> level) {
        long days = number(level.getOrDefault("vipValidityDays", 0));
        return days == 0 ? null : LocalDateTime.ofInstant(start.toInstant(ZoneOffset.UTC).atZone(zone()).plusDays(days).toInstant(), ZoneOffset.UTC);
    }
}
