package org.token.service.compute;

import lombok.RequiredArgsConstructor;
import org.token.exception.BusinessException;
import org.token.service.SystemSettingService;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeAgentScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.token.repository.compute.ComputeData.*;

/** 运营增长分析按系统自然日分桶；测试账号计入人数和笔数，仅其金额不进入汇总。 */
@Service
public class ComputeAnalyticsService {
    private static final DateTimeFormatter SECOND_TIME=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private final ComputeData data;
    private final ComputeAgentScope scope;
    private final SystemSettingService settings;
    private final ComputeCurrencyService currencies;
    private final org.token.repository.compute.ComputeAnalyticsRepository repository;
    @org.springframework.beans.factory.annotation.Autowired
    public ComputeAnalyticsService(ComputeData data,ComputeAgentScope scope,SystemSettingService settings,ComputeCurrencyService currencies,
            org.token.repository.compute.ComputeAnalyticsRepository repository) {
        this.data=data;this.scope=scope;this.settings=settings;this.currencies=currencies;this.repository=repository;
    }
    public ComputeAnalyticsService(ComputeData data,ComputeAgentScope scope,SystemSettingService settings,ComputeCurrencyService currencies) {
        this(data,scope,settings,currencies,new org.token.repository.compute.ComputeAnalyticsRepository(data));
    }

    @Transactional(readOnly=true)
    public Map<String,Object> overview(Map<String,String> query) { return overviewAt(query,Instant.now()); }

    @Transactional(readOnly=true)
    public Map<String,Object> dailyReport(Map<String,String> query) { return reportAt(query,Instant.now(),false); }

    /** 首页当天指标与近三十天图表独立取值，不沿用用户上次筛选区间。 */
    @Transactional(readOnly=true)
    public Map<String,Object> dashboardReport() { return dashboardReportAt(Instant.now()); }

    Map<String,Object> dashboardReportAt(Instant now) {
        ZoneId zone=ZoneId.of(settings.resolveSystemTimeZone());
        LocalDate today=now.atZone(zone).toLocalDate(),first=today.minusDays(29);
        Map<String,String> query=Map.of();
        List<Map<String,Object>> trend=period(query,first,today,zone);
        Map<String,Object> current=new LinkedHashMap<>(trend.get(trend.size()-1));
        current.remove("day");
        return fields("summaryUnit",ComputeFinanceSnapshotService.summaryUnit(data),"timeZone",zone.getId(),
                "startDate",today.toString(),"endDate",today.toString(),"trendStartDate",first.toString(),"trendEndDate",today.toString(),
                "current",current,"trend",trend);
    }

    /** 全局报表按系统时区解释输入秒，结束秒包含在统计区间内。 */
    @Transactional(readOnly=true)
    public Map<String,Object> globalReport(Map<String,String> query) { return globalReportAt(query,Instant.now()); }

    Map<String,Object> globalReportAt(Map<String,String> input,Instant now) {
        ZoneId zone=ZoneId.of(settings.resolveSystemTimeZone());
        ReportWindow window=reportWindow(input,now,zone);
        Map<String,String> query=prepareQuery(input);
        List<Map<String,Object>> trend=period(query,window.first(),window.last(),zone,window.fromUtc(),window.toUtc());
        return fields("summaryUnit",ComputeFinanceSnapshotService.summaryUnit(data),"timeZone",zone.getId(),
                "startDate",window.first().toString(),"endDate",window.last().toString(),
                "startTime",window.start().format(SECOND_TIME),"endTime",window.end().format(SECOND_TIME),
                "current",periodTotals(query,trend,window.fromUtc(),window.toUtc()),"trend",trend);
    }

    Map<String,Object> overviewAt(Map<String,String> query,Instant now) {
        return reportAt(query,now,true);
    }

    private Map<String,Object> reportAt(Map<String,String> query,Instant now,boolean comparison) {
        query=prepareQuery(query);
        ZoneId zone=ZoneId.of(settings.resolveSystemTimeZone());
        LocalDate end=parseDate(query.get("endDate"),now.atZone(zone).toLocalDate());
        LocalDate start=parseDate(query.get("startDate"),end.minusDays(29));
        long days=ChronoUnit.DAYS.between(start,end)+1;
        if(days<1||days>93)throw new BusinessException("server.v6.analytics.dateRange");
        List<Map<String,Object>> trend=period(query,start,end,zone);
        Map<String,Object> result=fields("summaryUnit",ComputeFinanceSnapshotService.summaryUnit(data),"timeZone",zone.getId(),"startDate",start.toString(),"endDate",end.toString(),
                "current",periodTotals(query,trend,start,end,zone),"trend",trend);
        if(comparison) {
            LocalDate previousStart=start.minusDays(days),previousEnd=start.minusDays(1);
            result.put("previousStartDate",previousStart.toString());result.put("previousEndDate",previousEnd.toString());
            result.put("previous",periodTotals(query,period(query,previousStart,previousEnd,zone),previousStart,previousEnd,zone));
        }
        return result;
    }

    Map<String,String> prepareQuery(Map<String,String> input) {
        Map<String,String> query=new LinkedHashMap<>(input);
        // 用户输入精确用户名，先在其可见范围内解析，再进行整棵子树汇总。
        if(!text(query.get("teamUsername")).isEmpty()) {
            List<Long> ids=repository.visibleUsername(text(query.get("teamUsername")),scope.queryScope());
            if(ids.size()!=1)throw ComputeAgentScope.denied();
            query.put("teamMemberId",String.valueOf(ids.get(0)));
        }
        return query;
    }

    private static List<Map<String,Object>> emptyTrend(LocalDate first,LocalDate last) {
        return first.datesUntil(last.plusDays(1)).map(day->fields("day",day.toString(),"newMembers",0L,"depositMembers",0L,"firstDepositMembers",0L,"depositAmount","0","withdrawalAmount","0","withdrawalFee","0","withdrawalGrossAmount","0","depositCount",0L,"withdrawalCount",0L,"unvaluedCount",0L)).toList();
    }
    private List<Map<String,Object>> period(Map<String,String> query,LocalDate first,LocalDate last,ZoneId zone) {
        return period(query,first,last,zone,utc(first,zone),utc(last.plusDays(1),zone));
    }

    private List<Map<String,Object>> period(Map<String,String> query,LocalDate first,LocalDate last,ZoneId zone,LocalDateTime fromUtc,LocalDateTime toUtc) {
        List<LocalDate> days=first.datesUntil(last.plusDays(1)).toList();
        List<Map<String,Object>> result=emptyTrend(first,last);
        for(String resource:List.of("members","deposits","withdrawals")) {
            List<String> measures=switch(resource) {
                case "members"->List.of("newMembers");
                case "deposits"->List.of("depositAmount","depositCount","unvaluedCount");
                default->List.of("withdrawalAmount","withdrawalFee","withdrawalGrossAmount","withdrawalCount","unvaluedCount");
            };
            var buckets=days.stream().map(day->new org.token.model.query.ReportBucket(
                    later(utc(day,zone),fromUtc),earlier(utc(day.plusDays(1),zone),toUtc))).toList();
            for(var values:repository.period(resource,buckets,fromUtc,toUtc,reportFilter(query))) {
                for(int i=0;i<days.size();i++)for(String metric:measures){
                    BigDecimal addition=decimal(values.get("v_"+i+"_"+metric));
                    BigDecimal value=decimal(result.get(i).get(metric)).add(addition);
                    result.get(i).put(metric,isCount(metric)?value.longValueExact():value.toPlainString());
                }
                if("deposits".equals(resource))for(int i=0;i<days.size();i++)for(String metric:List.of("depositMembers","firstDepositMembers"))
                    result.get(i).put(metric,number(values.get("v_"+i+"_"+metric)));
            }
        }
        result.forEach(day->day.put("netFlow",decimal(day.get("depositAmount")).subtract(decimal(day.get("withdrawalAmount"))).toPlainString()));
        return result;
    }

    /** 区间充值人数必须跨日去重；首充始终查全历史，不能将当日或当期首单当作首充。 */
    private Map<String,Object> periodTotals(Map<String,String> query,List<Map<String,Object>> rows,LocalDate first,LocalDate last,ZoneId zone) {
        return periodTotals(query,rows,utc(first,zone),utc(last.plusDays(1),zone));
    }

    private Map<String,Object> periodTotals(Map<String,String> query,List<Map<String,Object>> rows,LocalDateTime fromUtc,LocalDateTime toUtc) {
        Map<String,Object> result=totals(rows);
        var counts=repository.people(fromUtc,toUtc,reportFilter(query));
        for(String key:List.of("depositMembers","firstDepositMembers"))result.put(key,number(counts.get(key)));
        return result;
    }

    org.token.model.query.ReportMemberFilter reportFilter(Map<String,String> query) {
        var memberScope=scope.queryScope();
        Long groupId=null,memberId=null;
        if(!text(query.get("groupId")).isEmpty()) {
            groupId=number(query.get("groupId"));if(groupId<0)throw new BusinessException("server.compute.invalidInput");
        }
        if(!text(query.get("teamMemberId")).isEmpty()) {
            memberId=number(query.get("teamMemberId"));scope.assertMember(memberId);
        }
        return new org.token.model.query.ReportMemberFilter(memberScope,groupId,memberId);
    }
    private static Map<String,Object> totals(List<Map<String,Object>> rows) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:List.of("newMembers","depositAmount","withdrawalAmount","withdrawalFee","withdrawalGrossAmount","depositCount","withdrawalCount","unvaluedCount")){
            BigDecimal total=rows.stream().map(row->decimal(row.get(key))).reduce(BigDecimal.ZERO,BigDecimal::add);
            result.put(key,isCount(key)?total.longValueExact():total.toPlainString());
        }
        result.put("netFlow",decimal(result.get("depositAmount")).subtract(decimal(result.get("withdrawalAmount"))).toPlainString());
        return result;
    }
    private static boolean isCount(String key){return key.endsWith("Count")||"newMembers".equals(key);}
    private record ReportWindow(LocalDateTime start,LocalDateTime end,LocalDateTime fromUtc,LocalDateTime toUtc) {
        LocalDate first(){return start.toLocalDate();}
        LocalDate last(){return end.toLocalDate();}
    }
    private static ReportWindow reportWindow(Map<String,String> input,Instant now,ZoneId zone) {
        try {
            String startText=text(input.get("startTime")),endText=text(input.get("endTime"));
            LocalDateTime start,end;
            if(!startText.isEmpty()||!endText.isEmpty()) {
                if(!startText.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")||!endText.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"))throw new IllegalArgumentException();
                start=LocalDateTime.parse(startText,SECOND_TIME);end=LocalDateTime.parse(endText,SECOND_TIME);
            } else {
                LocalDate last=parseDate(input.get("endDate"),now.atZone(zone).toLocalDate());
                LocalDate first=parseDate(input.get("startDate"),last.minusDays(29));
                start=first.atStartOfDay();end=last.atTime(23,59,59);
            }
            long days=ChronoUnit.DAYS.between(start.toLocalDate(),end.toLocalDate())+1;
            if(end.isBefore(start)||days<1||days>93||start.getYear()<1000||end.getYear()>9999)throw new IllegalArgumentException();
            // 不静默平移不存在的本地时间，也不猜测夏令时回拨时重复小时的偏移。
            if(zone.getRules().getValidOffsets(start).size()!=1||zone.getRules().getValidOffsets(end).size()!=1)throw new IllegalArgumentException();
            LocalDateTime fromUtc=LocalDateTime.ofInstant(start.atZone(zone).toInstant(),ZoneOffset.UTC);
            LocalDateTime toUtc=LocalDateTime.ofInstant(end.atZone(zone).toInstant().plusSeconds(1),ZoneOffset.UTC);
            return new ReportWindow(start,end,fromUtc,toUtc);
        } catch(Exception invalid) {
            throw new BusinessException("server.v36.analytics.timeRange");
        }
    }
    private static LocalDateTime later(LocalDateTime first,LocalDateTime second){return first.isAfter(second)?first:second;}
    private static LocalDateTime earlier(LocalDateTime first,LocalDateTime second){return first.isBefore(second)?first:second;}
    private static LocalDateTime utc(LocalDate date,ZoneId zone){return LocalDateTime.ofInstant(date.atStartOfDay(zone).toInstant(),ZoneOffset.UTC);}
    private static LocalDate parseDate(String text,LocalDate fallback){if(text==null||text.isBlank())return fallback;try{return LocalDate.parse(text);}catch(Exception invalid){throw new BusinessException("server.v6.analytics.dateRange");}}
}
