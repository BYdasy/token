package org.token.service.compute;

import org.springframework.stereotype.Service;
import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeBusinessService;
import org.token.service.compute.ComputeCatalogService;

import lombok.RequiredArgsConstructor;
import org.token.model.security.ComputeMemberPrincipal;
import org.token.model.response.OperationOutcome;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;

/** 用户业务端点，只从独立会员身份读取数据归属。 */
@Service

@RequiredArgsConstructor
public class ComputeUserApplicationService {
    private final ComputeBusinessService business;
    private final ComputeCatalogService catalog;
    private final ComputeData data;

    private final org.token.service.compute.ComputeWithdrawalCoordinator withdrawals;
    private final org.token.repository.compute.ComputeControllerQueryRepository repository;

    public OperationOutcome catalog( ComputeMemberPrincipal principal, String language) {
        uid(principal);
        return OperationOutcome.success(catalog.catalog(language));
    }

    public OperationOutcome dashboard( ComputeMemberPrincipal principal) { return OperationOutcome.success(publicOrderData(business.dashboard(uid(principal)))); }

    public OperationOutcome wallets( ComputeMemberPrincipal principal,  String currency) { return OperationOutcome.success(business.wallets(uid(principal), currency)); }

    public OperationOutcome transfer( ComputeMemberPrincipal principal,  Map<String, Object> request) { return OperationOutcome.success(business.transfer(uid(principal), request)); }

    public OperationOutcome transferOptions( ComputeMemberPrincipal principal) { return OperationOutcome.success(business.transferOptions(uid(principal))); }

    public OperationOutcome transferQuote( ComputeMemberPrincipal principal,  Map<String,Object> request) { return OperationOutcome.success(business.transferQuote(uid(principal), request)); }

    public OperationOutcome buy( ComputeMemberPrincipal principal,  Map<String, Object> request) { return OperationOutcome.success(publicOrderData(business.buyOrder(uid(principal), request))); }

    public OperationOutcome deposit( ComputeMemberPrincipal principal,  Map<String, Object> request) { return OperationOutcome.success(business.deposit(uid(principal), request)); }

    public OperationOutcome quote( ComputeMemberPrincipal principal,  Map<String, Object> request) {
        Map<String, Object> quote = business.quote(uid(principal), request);
        Map<String,Object> response = new LinkedHashMap<>();
        for (String key : List.of("fee", "netAmount", "ruleId", "channelId", "feeSource", "accountingCurrency",
                "accountingRate", "accountingAmount", "accountingFee", "accountingNetAmount", "availableUsd", "availableAmount", "quoteAt"))
            response.put(key, quote.get(key));
        return OperationOutcome.success(response);
    }

    public OperationOutcome withdrawalBalance( ComputeMemberPrincipal principal,  Map<String,String> request) {
        return OperationOutcome.success(business.withdrawalBalance(uid(principal), new LinkedHashMap<>(request)));
    }

    public OperationOutcome withdraw( ComputeMemberPrincipal principal,  Map<String, Object> request) { return OperationOutcome.success(publicOrderData(withdrawals.withdraw(uid(principal), request))); }

    public OperationOutcome page( ComputeMemberPrincipal principal,  String resource,  Map<String, String> query) {
        Map<String, Object> response = new LinkedHashMap<>(catalog.page(resource, uid(principal), query));
        // 周期进度只用于展示，以服务端UTC时间校准，不写回结算天数或收益。
        if ("orders".equals(resource)) response.put("serverTime", java.time.Instant.now().toString());
        if (response.get("records") instanceof List<?> rows) {
            @SuppressWarnings("unchecked") var records = (List<Map<String,Object>>) rows;
            new org.token.service.compute.ComputeUsdAccounting(data).presentRecords(resource, records);
        }
        return OperationOutcome.success(publicOrderData(response));
    }


    public OperationOutcome addresses( ComputeMemberPrincipal principal) {
        var rows = repository.memberAddresses(uid(principal));
        rows.forEach(row -> {
            row.remove("addressKey");
            Object raw = row.remove("fieldsJson");
            if (raw instanceof byte[] bytes) raw = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            row.put("fields", raw == null ? Map.of() : data.jsonMap(raw.toString()));
        });
        return OperationOutcome.success(rows);
    }

    public OperationOutcome saveAddress( ComputeMemberPrincipal principal,  Map<String, Object> request) { return OperationOutcome.success(publicOrderData(withdrawals.saveAddress(uid(principal), request))); }

    public OperationOutcome addressPolicy(ComputeMemberPrincipal principal) { uid(principal); return OperationOutcome.success(business.addressPolicy()); }

    public OperationOutcome deleteAddress( ComputeMemberPrincipal principal,  long id) { business.deleteAddress(uid(principal), id); return OperationOutcome.success(); }

    private static long uid(ComputeMemberPrincipal principal) {
        if (principal == null) throw new AuthenticationCredentialsNotFoundException("需要会员登录");
        return principal.id();
    }

    /** 折抵快照仅供服务端审计；用户仍能查看订单原价、折抵和实际支付金额。 */
    private static Object publicOrderData(Object value) {
        if (value instanceof Map<?, ?> fields) {
            Map<String, Object> result = new LinkedHashMap<>();
            fields.forEach((key, item) -> {
                if (!java.util.Set.of("productQuoteJson","gatewaySnapshot","addressKey","accountingQuoteJson").contains(String.valueOf(key))) result.put(String.valueOf(key), publicOrderData(item));
            });
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(ComputeUserApplicationService::publicOrderData).toList();
        return value;
    }
}
