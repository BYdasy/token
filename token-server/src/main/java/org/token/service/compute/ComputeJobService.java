package org.token.service.compute;

import org.token.repository.compute.ComputeJobRepository;

import org.token.repository.compute.ComputeData;

import lombok.extern.slf4j.Slf4j;
import org.token.exception.BusinessException;
import org.token.service.SystemSettingService;
import org.token.util.time.SystemCron;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

import static org.token.repository.compute.ComputeData.*;

/** 数据库统一任务调度：事务抢占、运行日志、异步执行和超时恢复。 */
@Service
@Slf4j
public class ComputeJobService {
    private final ComputeData data;
    private final ComputeJobRepository repository;
    private final ComputeBusinessService business;
    private final TaskExecutor executor;
    private final TransactionTemplate transactions;
    private final SystemSettingService settings;
    private final ComputeVipPeriodService vipPeriods;
    private volatile boolean schemaWarningShown;

    public ComputeJobService(ComputeData data, ComputeBusinessService business,
                             @Qualifier("computeJobExecutor") TaskExecutor executor, PlatformTransactionManager transactionManager) {
        this(data,business,executor,transactionManager,null,null);
    }

    @Autowired
    public ComputeJobService(ComputeData data, ComputeBusinessService business,
                             @Qualifier("computeJobExecutor") TaskExecutor executor, PlatformTransactionManager transactionManager,
                             SystemSettingService settings,ComputeVipPeriodService vipPeriods, ComputeJobRepository repository) {
        this.data=data;this.business=business;this.executor=executor;
        this.transactions=new TransactionTemplate(transactionManager);this.settings=settings;this.vipPeriods=vipPeriods;

        this.repository = repository;
    }

    public ComputeJobService(ComputeData data, ComputeBusinessService business,
                             @Qualifier("computeJobExecutor") TaskExecutor executor, PlatformTransactionManager transactionManager,
                             SystemSettingService settings,ComputeVipPeriodService vipPeriods) {
        this(data, business, executor, transactionManager, settings, vipPeriods, new ComputeJobRepository(data));
    }

    private ZoneId systemZone() { return settings==null?ZoneOffset.UTC:ZoneId.of(settings.resolveSystemTimeZone()); }

    public List<Map<String, Object>> jobs() { synchronizeScheduleZone();return repository.jobsJobRows(); }

    /** 时区切换或迁移后重算下一次计划，跨进程由数据库行锁协调，不触碰运行中的收益快照。 */
    private void synchronizeScheduleZone() {
        if(settings==null)return;
        ZoneId zone=systemZone();
        for(var candidate:repository.synchronizeScheduleZoneJobRows(zone.getId())) {
            transactions.executeWithoutResult(status->{
                var job=repository.synchronizeScheduleZoneJobRow(candidate.get("id"));
                if(zone.getId().equals(text(job.get("scheduleTimeZone"))))return;
                repository.synchronizeScheduleZoneJobWrite(SystemCron.next(text(job.get("cronExpression")),Instant.now(),zone), zone.getId(), job.get("id"));
            });
        }
    }

    public Map<String, Object> update(long id, Map<String, Object> request) {
        String cron = text(request.get("cronExpression"));
        ZoneId zone=systemZone();
        LocalDateTime next = SystemCron.next(cron,Instant.now(),zone);
        return transactions.execute(status -> {
            repository.updateJobRow(id);
            repository.updateJobWrite(enabled(request.get("enabled")), cron, next, id);
            if(settings!=null)repository.updateJobWrite2(zone.getId(), id);
            return repository.updateJobRow2(id);
        });
    }

    public Map<String, Object> submit(long jobId, Integer requestedBy, boolean scheduled) {
        Map<String, Object> run = transactions.execute(status -> {
            Map<String, Object> job = repository.submitJobRow(jobId);
            if (job.get("activeRunId") != null) {
                if (scheduled) return null;
                throw new BusinessException("server.compute.jobRunning");
            }
            LocalDateTime current = now();
            if (scheduled && (!enabled(job.get("enabled")) || Instant.parse(text(job.get("nextRunAt"))).isAfter(current.toInstant(ZoneOffset.UTC)))) return null;
            LocalDateTime next = SystemCron.next(text(job.get("cronExpression")),current.toInstant(ZoneOffset.UTC),systemZone());
            long runId = data.insert("compute_job_run", fields("job_id", jobId, "job_code", job.get("jobCode"), "status", "PENDING",
                    "trigger_type", scheduled ? "SCHEDULED" : "MANUAL", "requested_by", requestedBy, "processed_count", 0, "created_at", current));
            repository.submitJobWrite(runId, next, current, jobId);
            return repository.submitJobRunRow(runId);
        });
        if (run == null) return Map.of();
        long runId = number(run.get("id"));
        try { executor.execute(() -> execute(runId)); }
        catch (RuntimeException exception) { finish(runId, "FAILED", 0, "server.compute.jobQueueFull"); throw new BusinessException("server.compute.jobQueueFull"); }
        return run;
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void schedule() {
        try {
            synchronizeScheduleZone();
            // 超过六小时没有完成的任务释放占用；资金结算按订单与日唯一键可安全再次执行。
            for (Map<String, Object> run : repository.scheduleJobRunRows(now().minusHours(6)))
                finish(number(run.get("id")), "FAILED", 0, "server.compute.jobInterrupted");
            for (Map<String, Object> job : repository.scheduleJobRows(now()))
                submit(number(job.get("id")), null, true);
            schemaWarningShown = false;
        } catch (Exception exception) {
            if (!schemaWarningShown) { log.warn("算力任务暂不可用，请确认业务增量 SQL 已执行：{}", exception.getClass().getSimpleName()); schemaWarningShown = true; }
        }
    }

    private void execute(long runId) {
        int processed = 0;
        try {
            if (repository.executeJobRunWrite(now(), runId) != 1) return;
            Map<String, Object> run = repository.submitJobRunRow(runId);
            String code = text(run.get("jobCode"));
            switch (code) {
                case "SETTLE_ORDERS" -> {
                    long cursor = 0; LocalDateTime settlementTime = now();
                    while (true) {
                        List<Map<String, Object>> orders = repository.executeOrderRows(cursor, settlementTime.minusDays(1));
                        if (orders.isEmpty()) break;
                        for (Map<String, Object> order : orders) { cursor = number(order.get("id")); processed += business.settleOrder(cursor, settlementTime); }
                        progress(runId, processed);
                    }
                }
                case "UPDATE_LEVELS" -> {
                    long cursor = 0;
                    while (true) {
                        List<Map<String, Object>> members = repository.executeMemberRows(cursor);
                        if (members.isEmpty()) break;
                        for (Map<String, Object> member : members) { cursor = number(member.get("id")); if (upgradeMember(cursor)) processed++; }
                        progress(runId, processed);
                    }
                }
                case "REFRESH_MODELS" -> processed = refreshModels();
                default -> throw new BusinessException("server.compute.unknownJob");
            }
            finish(runId, "SUCCEEDED", processed, null);
        } catch (Exception exception) {
            String key = exception instanceof BusinessException && text(exception.getMessage()).startsWith("server.compute.") ? exception.getMessage() : "server.compute.jobFailed";
            log.warn("算力业务任务失败，运行编号 {}", runId, exception); finish(runId, "FAILED", processed, key);
        }
    }

    boolean upgradeMember(long id) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            // 独立调用同样生成周期，不能回退成只更新等级的旁路。
            ComputeVipPeriodService periods = vipPeriods == null ? new ComputeVipPeriodService(data, settings) : vipPeriods;
            return periods.upgradeEligible(id);
        }));
    }

    private int refreshModels() {
        ZoneId zone=systemZone();LocalDate today = LocalDate.now(zone); int processed = 0;
        for (Map<String, Object> model : repository.refreshModelsModelRows()) {
            transactions.executeWithoutResult(status -> {
                long modelId = number(model.get("id"));
                Map<String, Object> locked = repository.refreshModelsModelRow(modelId);
                BigDecimal change = modelChange(modelId, today, decimal(locked.get("volatility")));
                BigDecimal multiplier = BigDecimal.ONE.add(change.divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP));
                BigDecimal input = decimal(locked.get("inputPrice")).multiply(multiplier).setScale(8, RoundingMode.HALF_UP);
                BigDecimal output = decimal(locked.get("outputPrice")).multiply(multiplier).setScale(8, RoundingMode.HALF_UP);
                repository.refreshModelsModelPriceWrite(modelId, today, input, output, change, now());
                Map<String, Object> price = repository.refreshModelsModelPriceRow(modelId, today);
                repository.refreshModelsModelWrite(price.get("inputPrice"), price.get("outputPrice"), now(), modelId);
            });
            processed++;
        }
        return processed;
    }

    static BigDecimal modelChange(long modelId, LocalDate date, BigDecimal volatility) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((modelId + ":" + date).getBytes(StandardCharsets.UTF_8));
            int sample = ((digest[0] & 255) * 256 + (digest[1] & 255)) - 32768;
            return volatility.multiply(BigDecimal.valueOf(sample)).divide(new BigDecimal("32768"), 4, RoundingMode.HALF_UP);
        } catch (Exception exception) { throw new IllegalStateException("模型价格波动摘要生成失败", exception); }
    }

    private void progress(long runId, int count) {
        repository.progressJobRunWrite(count, runId);
        if (Thread.currentThread().isInterrupted()) throw new BusinessException("server.compute.jobInterrupted");
    }

    private void finish(long runId, String state, int count, String error) {
        transactions.executeWithoutResult(status -> {
            Map<String, Object> run = repository.finishJobRunRow(runId);
            repository.finishJobRunWrite(state, count, error, now(), runId);
            repository.finishJobWrite(run.get("jobId"), runId);
        });
    }
}
