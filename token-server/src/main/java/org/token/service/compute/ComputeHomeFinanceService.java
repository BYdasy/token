package org.token.service.compute;

import lombok.RequiredArgsConstructor;
import org.token.exception.BusinessException;
import org.token.repository.compute.ComputeData;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

import static org.token.repository.compute.ComputeData.*;
import static org.token.service.compute.ComputeTokenAmounts.asToken;

/** 首页累计资金使用成功记账快照，余额使用当前行情；所有读取都保留后台账号的代理范围。 */
@Service
public class ComputeHomeFinanceService {
    private final ComputeData data;
    private final ComputeAnalyticsService analytics;
    private final ComputeExchangeRateService rates;
    private final org.token.repository.compute.ComputeHomeFinanceRepository repository;
    @org.springframework.beans.factory.annotation.Autowired
    public ComputeHomeFinanceService(ComputeData data,ComputeAnalyticsService analytics,ComputeExchangeRateService rates,
            org.token.repository.compute.ComputeHomeFinanceRepository repository) {
        this.data=data;this.analytics=analytics;this.rates=rates;this.repository=repository;
    }
    public ComputeHomeFinanceService(ComputeData data,ComputeAnalyticsService analytics,ComputeExchangeRateService rates) {
        this(data,analytics,rates,new org.token.repository.compute.ComputeHomeFinanceRepository(data));
    }

    @Transactional(readOnly = true)
    public Map<String,Object> overview(Map<String,String> input) {
        var query=analytics.prepareQuery(input);
        var filter=analytics.reportFilter(query);
        String unit=ComputeFinanceSnapshotService.summaryUnit(data);
        Map<String,Object> result=fields("summaryUnit",unit,"unitFallback",!unit.equals(data.setting("summaryCurrency","USDT")),
                "memberCount",repository.memberCount(filter),
                "totalDepositMembers",repository.depositors(filter),
                "activatedMemberCount",repository.activatedMembers(filter),
                "updatedAt",now().toInstant(java.time.ZoneOffset.UTC).toString());
        long missing=0;
        for(String kind:List.of("DEPOSIT","WITHDRAWAL")) {
            var totals=repository.totals(kind,filter);
            result.put("DEPOSIT".equals(kind)?"totalDeposits":"totalWithdrawals",totals.get("amount"));
            if ("WITHDRAWAL".equals(kind)) {
                result.put("withdrawalFee",totals.get("fee"));
                result.put("withdrawalNetAmount",totals.get("netAmount"));
            }
            missing+=number(totals.get("missing"));
        }
        result.put("actualDepositWithdrawalDifference",decimal(result.get("totalDeposits")).subtract(decimal(result.get("withdrawalNetAmount"))).toPlainString());
        result.put("unvaluedCount",missing);
        BigDecimal balance=BigDecimal.ZERO;
        List<String> unvalued=new ArrayList<>();
        for(var wallet:repository.balances(filter)) {
            BigDecimal amount=decimal(wallet.get("available"));
            if(amount.signum()==0)continue;
            String currency=text(wallet.get("currency"));
            BigDecimal rate=decimal(rates.currentUsdQuote(currency).get("usdRate"));
            if(rate.signum()>0)balance=balance.add(asToken(amount,rate));else unvalued.add(currency);
        }
        result.put("totalBalance",balance.toPlainString());
        result.put("unvaluedBalanceCurrencies",unvalued);
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String,Object> details(String kind,Map<String,String> input) {
        if(!Set.of("DEPOSIT","WITHDRAWAL").contains(kind))throw new BusinessException("server.compute.invalidInput");
        var query=analytics.prepareQuery(input);
        var filter=analytics.reportFilter(query);
        Long total=repository.detailCount(kind,filter);
        int size=(int)Math.min(50,Math.max(1,number(input.getOrDefault("size","10"))));
        long page=Math.max(1,number(input.getOrDefault("page","1")));
        var rows=repository.details(kind,filter,size,(page-1)*size);
        return fields("records",rows,"total",total==null?0:total,"summaryUnit",ComputeFinanceSnapshotService.summaryUnit(data));
    }
}
