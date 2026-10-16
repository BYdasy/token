package org.token.service.compute;

import org.token.repository.compute.ComputeBusinessRepository;

import org.token.repository.compute.ComputeData;
import org.token.service.compute.ComputeMoneyPolicy;

import org.springframework.beans.factory.annotation.Autowired;
import org.token.util.PasswordUtils;
import org.token.exception.BusinessException;
import org.token.service.SystemSettingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

import static org.token.repository.compute.ComputeData.*;

/** 钱包与订单业务。多层奖励先锁邀请关系，再锁会员及钱包，账本与业务结果在同一事务提交。 */
@Service
public class ComputeBusinessService {
    private final ComputeData data;
    private final ComputeBusinessRepository repository;
    private final ComputeCurrencyService currencies;
    private final PasswordUtils passwords;
    private final ComputeSystemMessageService messages;
    private final SystemSettingService systemSettings;
    private final ComputeRewardService rewards;
    private final org.token.config.compute.ComputeMcagProperties mcag;
    private final ComputeFinanceSnapshotService financeSnapshots;
    private final ComputeMcagChannelService mappings;
    private final ComputeWithdrawalAddressGuard addressGuard;
    @Autowired
    public ComputeBusinessService(ComputeData data, ComputeCurrencyService currencies, PasswordUtils passwords,ComputeSystemMessageService messages,SystemSettingService systemSettings,ComputeRewardService rewards, org.token.config.compute.ComputeMcagProperties mcag, ComputeFinanceSnapshotService financeSnapshots, ComputeMcagChannelService mappings, ComputeWithdrawalAddressGuard addressGuard, ComputeBusinessRepository repository) { this.data=data;this.currencies=currencies;this.passwords=passwords;this.messages=messages;this.systemSettings=systemSettings;this.rewards=rewards;this.mcag=mcag;this.financeSnapshots=financeSnapshots;this.mappings=mappings;this.addressGuard=addressGuard;
        this.repository = repository;
    }

    public ComputeBusinessService(ComputeData data, ComputeCurrencyService currencies, PasswordUtils passwords,ComputeSystemMessageService messages,SystemSettingService systemSettings,ComputeRewardService rewards, org.token.config.compute.ComputeMcagProperties mcag, ComputeFinanceSnapshotService financeSnapshots, ComputeMcagChannelService mappings, ComputeWithdrawalAddressGuard addressGuard) {
        this(data, currencies, passwords, messages, systemSettings, rewards, mcag, financeSnapshots, mappings, addressGuard, new ComputeBusinessRepository(data));
    }
    public ComputeBusinessService(ComputeData data, ComputeCurrencyService currencies, PasswordUtils passwords,ComputeSystemMessageService messages,SystemSettingService systemSettings,ComputeRewardService rewards, org.token.config.compute.ComputeMcagProperties mcag, ComputeFinanceSnapshotService financeSnapshots) {
        this(data,currencies,passwords,messages,systemSettings,rewards,mcag,financeSnapshots,
                new ComputeMcagChannelService(data,mcag==null?new org.token.config.compute.ComputeMcagProperties():mcag),
                new ComputeWithdrawalAddressGuard(data,mcag==null?new org.token.config.compute.ComputeMcagProperties():mcag,null,new ComputeMcagChannelService(data,mcag==null?new org.token.config.compute.ComputeMcagProperties():mcag)));
    }
    public ComputeBusinessService(ComputeData data, ComputeCurrencyService currencies, PasswordUtils passwords,ComputeSystemMessageService messages,SystemSettingService systemSettings,ComputeRewardService rewards, org.token.config.compute.ComputeMcagProperties mcag) { this(data,currencies,passwords,messages,systemSettings,rewards,mcag,new ComputeFinanceSnapshotService(data)); }
    public ComputeBusinessService(ComputeData data, ComputeCurrencyService currencies, PasswordUtils passwords,ComputeSystemMessageService messages,SystemSettingService systemSettings,ComputeRewardService rewards) { this(data,currencies,passwords,messages,systemSettings,rewards,null); }
    public ComputeBusinessService(ComputeData data, ComputeCurrencyService currencies, PasswordUtils passwords,ComputeSystemMessageService messages,SystemSettingService systemSettings) {this(data,currencies,passwords,messages,systemSettings,null);}
    public ComputeBusinessService(ComputeData data, ComputeCurrencyService currencies, PasswordUtils passwords,ComputeSystemMessageService messages) {this(data,currencies,passwords,messages,null);}
    public ComputeBusinessService(ComputeData data, ComputeCurrencyService currencies, PasswordUtils passwords) {this(data,currencies,passwords,new ComputeSystemMessageService(data));}
    public ComputeBusinessService(ComputeData data) { this(data,new ComputeCurrencyService(data),new PasswordUtils()); }
    private static final Set<String> WALLETS = Set.of("COMPUTE", "PROJECT", "EARNINGS");
    /** 平台美元账务复用历史USDT钱包；币网原币仅保留在充提订单中。 */
    private static final String ACCOUNTING_WALLET_CURRENCY = "USDT";
    private static final SecureRandom RANDOM = new SecureRandom();

    public String defaultCurrency() { return currencies.defaultCurrency(); }
    void lockRewards() { if (rewards != null) rewards.lock(); }
    void creditVipIncome(long memberId, String currency, BigDecimal income, long contractId, int sequence, String orderNo) {
        String event = "AUTO_INCOME:" + contractId + ":" + sequence;
        changeWallet(memberId, "EARNINGS", currency, income, BigDecimal.ZERO, "AUTO_INCOME", event, orderNo);
        if (rewards != null) rewards.onIncomePosted(memberId, event);
    }
    private ZoneId systemZone() { return systemSettings==null?ZoneOffset.UTC:ZoneId.of(systemSettings.resolveSystemTimeZone()); }

    /** 活动参与记录与奖励账本在调用方同一事务提交；重复事件不会重复增加余额。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void creditActivityReward(long memberId, String currency, BigDecimal reward, long participationId) {
        lockMember(memberId);
        BigDecimal value = amount(reward);
        String validatedCurrency = activityWalletCurrency(currency);
        String event = "ACTIVITY:" + participationId;
        if (participationId <= 0) throw new BusinessException("server.compute.invalidInput");
        List<Map<String, Object>> previous = repository.creditActivityRewardLedgerRows(memberId, event);
        if (!previous.isEmpty()) {
            if (!validatedCurrency.equals(activityWalletCurrency(text(previous.get(0).get("currency")))) || value.compareTo(decimal(previous.get(0).get("amount"))) != 0)
                throw new BusinessException("server.compute.requestConflict");
            return;
        }
        changeWallet(memberId, "EARNINGS", validatedCurrency, value, BigDecimal.ZERO, "ACTIVITY_REWARD", event, String.valueOf(participationId));
    }

    private String activityWalletCurrency(String configuredCurrency) {
        String value=text(configuredCurrency);
        return Set.of("USD","USDT","USDC").contains(value)?ACCOUNTING_WALLET_CURRENCY:currency(value,null);
    }

    @Transactional
    public void initializeMember(long memberId) {
        lockMember(memberId);
        initializeWallets(memberId);
    }

    /** 后台开户只初始化零余额钱包；注册奖励由独立的正式注册入口决定。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void initializeWallets(long memberId) {
        repository.initializeWalletsMemberRow(memberId);
        String defaultCurrency=ACCOUNTING_WALLET_CURRENCY;
        for (String wallet : WALLETS) ensureWallet(memberId,wallet,defaultCurrency);
    }

    @Transactional
    public List<Map<String, Object>> wallets(long memberId) {
        return wallets(memberId,null);
    }

    @Transactional
    public List<Map<String,Object>> wallets(long memberId,String requestedCurrency) {
        lockMember(memberId);
        // 选充值或提款币种不能再创建该原币的业务钱包；旧非零原币余额仍原样返回供历史核对。
        String selected=ACCOUNTING_WALLET_CURRENCY;
        for (String wallet : WALLETS) ensureWallet(memberId,wallet,selected);
        return repository.walletsWalletRows(memberId, selected);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> dashboard(long memberId) {
        String summaryCurrency = currencies.defaultCurrency();
        // 单次算力只计算收益，不作为在投本金；历史点击协议的冻结本金也不能重复累计。
        Map<String, Object> stats = repository.dashboardTotals(memberId, summaryCurrency);
        stats.put("summaryCurrency", summaryCurrency);
        stats.put("defaultCurrency",summaryCurrency);
        stats.put("currencySummaries", repository.currencyTotals(memberId));
        stats.put("wallets", repository.walletsWalletRows(memberId, summaryCurrency));
        ZoneId zone=systemZone();LocalDate today=LocalDate.now(zone);
        var daily=repository.dailyIncome(memberId,summaryCurrency,today,zone);
        List<Map<String,Object>> trend=new ArrayList<>();
        for(int index=0;index<7;index++)trend.add(fields("day",today.minusDays(6-index).toString(),"income",daily.get("day"+index)));
        stats.put("todayIncome",daily.get("day6"));stats.put("incomeTrend",trend);stats.put("incomeTimeZone",zone.getId());
        stats.put("orders", repository.dashboardOrderRows(memberId));
        return stats;
    }

    @Transactional
    public Map<String, Object> transfer(long memberId, Map<String, Object> request) {
        lockMember(memberId);
        requirePayPassword(memberId,request);
        Map<String, Object> previous = beginRequest(memberId, "TRANSFER", request);
        if (previous != null) return previous;
        Map<String,Object> quote = transferQuote(request, true);
        String from = text(quote.get("fromWallet")), to = text(quote.get("toWallet")), currency = text(quote.get("currency"));
        BigDecimal amount = decimal(quote.get("amount")), fee = decimal(quote.get("fee")), net = decimal(quote.get("netAmount"));
        // 新客户端携带已展示的报价，配置变化时重新确认；历史客户端保留原有服务端计费口径。
        if (request.containsKey("expectedFee") || request.containsKey("expectedNetAmount")) {
            if (request.get("expectedFee") == null || request.get("expectedNetAmount") == null)
                throw new BusinessException("server.compute.quoteRequired");
            if (decimal(request.get("expectedFee")).compareTo(fee) != 0 || decimal(request.get("expectedNetAmount")).compareTo(net) != 0)
                throw new BusinessException("server.compute.quoteChanged");
        }
        Map<String,Object> rule = data.jsonMap(data.json(quote.get("rule")));
        long id = data.insert("compute_transfer", fields("member_id", memberId, "from_wallet", from, "to_wallet", to,
                "currency", currency, "amount", amount, "fee", fee, "net_amount", net, "rule_id", rule.get("id"), "rule_snapshot", data.json(rule), "created_at", now()));
        changeWallet(memberId, from, currency, amount.negate(), BigDecimal.ZERO, "TRANSFER_OUT", "TRANSFER:" + id + ":OUT", "" + id);
        changeWallet(memberId, to, currency, net, BigDecimal.ZERO, "TRANSFER_IN", "TRANSFER:" + id + ":IN", "" + id);
        return finishRequest(memberId, request, repository.transferTransferRow(id));
    }

    /** 与实际划转共用当前启用规则和取整，不写入请求、钱包或账本。 */
    @Transactional(readOnly = true)
    public Map<String,Object> transferQuote(long memberId, Map<String,Object> request) {
        repository.transferQuoteMemberRow(memberId);
        Map<String,Object> quote = transferQuote(request, false);
        quote.remove("rule");
        return quote;
    }

    private Map<String,Object> transferQuote(Map<String,Object> request, boolean lock) {
        String from = walletType(request.get("fromWallet")), to = walletType(request.get("toWallet"));
        if (from.equals(to)) throw new BusinessException("server.compute.sameWallet");
        String currency = ACCOUNTING_WALLET_CURRENCY;
        if (!text(request.get("currency")).isEmpty() && !currency.equals(text(request.get("currency"))))
            throw new BusinessException("server.compute.unsupportedCurrency");
        if (!transferWalletsSupported(currency, from, to, lock)) throw new BusinessException("server.compute.transferRuleMissing");
        BigDecimal amount = amount(org.token.service.compute.ComputeTokenAmounts.requestAmount(data, request, currency));
        List<Map<String,Object>> rules = repository.transferRules(from, to, currency, amount, lock);
        if (rules.isEmpty()) throw new BusinessException("server.compute.transferRuleMissing");
        Map<String,Object> rule = rules.get(0);
        BigDecimal fee = ComputeMoneyPolicy.fee(amount, rule, 0, LocalTime.now(systemZone()));
        return fields("fromWallet", from, "toWallet", to, "currency", currency, "amount", amount,
                "fee", fee, "netAmount", amount.subtract(fee), "rule", rule);
    }

    /** 候选方向只来自当前默认币种的启用规则与有效钱包通道，不暴露后台配置或内部主键。 */
    @Transactional(readOnly = true)
    public Map<String,Object> transferOptions(long memberId) {
        repository.transferQuoteMemberRow(memberId);
        String currency = ACCOUNTING_WALLET_CURRENCY;
        List<Map<String,Object>> directions = new ArrayList<>();
        for (var rule : repository.transferOptionsTransferRuleRows(currency)) {
            String from = text(rule.get("fromWallet")), to = text(rule.get("toWallet"));
            if (!from.equals(to) && WALLETS.contains(from) && WALLETS.contains(to) && transferWalletsSupported(currency, from, to, false))
                directions.add(fields("fromWallet", from, "toWallet", to));
        }
        BigDecimal rate = directions.isEmpty() ? null : org.token.service.compute.ComputeTokenAmounts.rate(data, currency);
        return fields("directions", directions, "currency", currency, "assetRate", rate);
    }

    private boolean transferWalletsSupported(String currency, String from, String to, boolean lock) {
        return repository.transferChannels(currency, lock).stream().anyMatch(channel -> {
            Set<String> wallets = new HashSet<>(Arrays.asList(text(channel.get("supportAccountTypes")).split(",")));
            return wallets.contains(from) && wallets.contains(to);
        });
    }

    @Transactional
    public Map<String, Object> buyOrder(long memberId, Map<String, Object> request) {
        lockRewards();
        lockMember(memberId);
        Map<String, Object> previous = beginRequest(memberId, "ORDER", request);
        if (previous != null) return previous;
        Map<String, Object> product = repository.buyOrderProductRow(number(request.get("productId")));
        String type = text(product.get("type"));
        if(!"GPU".equals(type))throw new BusinessException("server.compute.invalidInput");
        Map<String, Object> purchaseQuote = new ComputeProductQuoteService(data).consume(memberId, request, product);
        BigDecimal amount = amount(purchaseQuote.get("amount"));
        if ("AMOUNT".equals(ComputeProductPurchasePolicy.mode(product)) && (amount.compareTo(decimal(product.get("minAmount"))) < 0 || amount.compareTo(decimal(product.get("maxAmount"))) > 0))
            throw new BusinessException("server.compute.amountOutsideRange");
        long purchaseQuantity = number(purchaseQuote.get("quantity"));
        BigDecimal purchaseToken = ComputeTokenAmounts.asToken(amount, decimal(purchaseQuote.get("assetRate")));
        BigDecimal debit = decimal(purchaseQuote.get("debitAmount"));
        String currency = currency(product.get("currency"), null);
        if (!ACCOUNTING_WALLET_CURRENCY.equals(currency)) throw new BusinessException("server.compute.unsupportedCurrency");
        String source = "PROJECT";
        BigDecimal minimumRate = decimal(product.get("dailyRateMin")), maximumRate = decimal(product.get("dailyRateMax"));
        BigDecimal dailyRate = minimumRate.add(maximumRate.subtract(minimumRate)
                .multiply(BigDecimal.valueOf(RANDOM.nextInt(1_000_001))).divide(new BigDecimal("1000000"), 8, RoundingMode.DOWN));
        int days = (int) number(product.get("durationDays"));
        LocalDateTime startsAt = now();
        long id = data.insert("compute_order", fields("member_id", memberId, "product_id", product.get("id"), "product_name", product.get("name"),
                "product_type", type, "source_wallet", source, "currency", currency, "amount", amount, "daily_rate",dailyRate,
                "duration_days", days, "settlement_mode", product.get("settlementMode"),
                "product_snapshot", data.json(product), "expected_income", ComputeMoneyPolicy.dailyTotalIncome(amount, dailyRate, days),
                "paid_income", BigDecimal.ZERO, "settled_days", 0, "status", "ACTIVE", "starts_at", startsAt,
                "matures_at", startsAt.plusDays(days), "created_at", startsAt,
                "product_quote_json", data.json(purchaseQuote)));
        repository.buyOrderOrderWrite2(ComputeProductPurchasePolicy.mode(product), purchaseQuantity, purchaseQuote.get("unitPriceToken"), purchaseToken, id);
        if (debit.signum() > 0) changeWallet(memberId, source, currency, debit.negate(), BigDecimal.ZERO, "ORDER_BUY", "ORDER:" + id, "" + id);
        return finishRequest(memberId, request, repository.buyOrderOrderRow(id));
    }

    @Transactional
    public Map<String, Object> deposit(long memberId, Map<String, Object> request) {
        // 已接入的链上充值只能由验签查询结果入账，客户端金额不能生成可人工放行的重复申请。
        if (mcag != null && mcag.isEnabled() && mappings.findChannel(text(request.get("currency")).isEmpty() ? defaultCurrency() : text(request.get("currency")), text(request.get("network"))) != null)
            throw new BusinessException("server.compute.mcag.automaticDeposit");
        lockMember(memberId);
        Map<String, Object> previous = beginRequest(memberId, "DEPOSIT", request);
        if (previous != null) return previous;
        return finishRequest(memberId, request, createDeposit(memberId, request, null));
    }

    /** 后台手工记录的权限、范围和幂等操作由管理服务持有，同事务复用充值状态机。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String,Object> manualDeposit(long memberId,Map<String,Object> request,int operator) {
        lockMember(memberId);
        return createDeposit(memberId,request,operator);
    }

    private Map<String,Object> createDeposit(long memberId,Map<String,Object> request,Integer operator) {
        String network = text(request.get("network"));
        String currency = currency(request.get("currency"), network);
        String depositWallet = walletType(request.get("walletType"));
        if ("EARNINGS".equals(depositWallet)) throw new BusinessException("server.compute.depositWalletInvalid");
        BigDecimal depositAmount=amount(request.get("amount"));
        currencies.requireChannel(currency,network,"DEPOSIT",depositWallet,depositAmount);
        long id = data.insert("compute_deposit", fields("member_id", memberId, "wallet_type", depositWallet,
                "currency", currency, "network", network, "amount", depositAmount,
                "status", "PENDING", "payment_status", "MANUAL_REVIEW", "record_source",operator==null?"USER":"ADMIN_MANUAL",
                "accounting_version",2,"accounting_currency","USD","accounting_status","PENDING",
                "created_by",operator,"creation_remark",operator==null?"":text(request.get("remark")),"created_at", now()));
        return repository.createDepositDepositRow(id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> quote(long memberId, Map<String, Object> request) {
        Map<String, Object> member = repository.quoteMemberRow(memberId);
        return withdrawalQuote(member, request);
    }

    /** 无申请金额时也可查询选中原币的可提额度，始终来自统一收益钱包而非原币钱包。 */
    @Transactional(readOnly = true)
    public Map<String,Object> withdrawalBalance(long memberId,Map<String,Object> request) {
        repository.transferQuoteMemberRow(memberId);
        if (!enabled(data.setting("withdrawalEnabled","true"))) throw new BusinessException("server.compute.withdrawalDisabled");
        String network=text(request.get("network")),currency=currency(request.get("currency"),network);
        var channel=currencies.requireChannel(currency,network,"WITHDRAW","EARNINGS",null);
        return withdrawalFunds(memberId,channel,new ComputeUsdAccounting(data).strictQuote(currency));
    }

    @Transactional
    public Map<String, Object> withdraw(long memberId, Map<String, Object> request) {
        return withdraw(memberId,request,null);
    }

    @Transactional
    public Map<String, Object> withdraw(long memberId, Map<String, Object> request, ComputeWithdrawalAddressGuard.Proof proof) {
        Map<String, Object> member = lockMember(memberId);
        requirePayPassword(memberId,request);
        Map<String, Object> previous = beginRequest(memberId, "WITHDRAWAL", request);
        if (previous != null) return previous;
        new ComputeContentTaskGuard(data).assertSceneCompleted(memberId, "WITHDRAW");
        return finishRequest(memberId,request,createWithdrawal(member,request,null,proof));
    }

    /** 后台手工提款免用户支付密码；资金冻结、地址与报价校验仍使用同一业务实现。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String,Object> manualWithdrawal(long memberId,Map<String,Object> request,int operator) {
        return manualWithdrawal(memberId,request,operator,null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String,Object> manualWithdrawal(long memberId,Map<String,Object> request,int operator,ComputeWithdrawalAddressGuard.Proof proof) {
        Map<String,Object> member = lockMember(memberId);
        new ComputeContentTaskGuard(data).assertSceneCompleted(memberId,"WITHDRAW");
        return createWithdrawal(member,request,operator,proof);
    }

    Map<String,Object> withdrawalReplay(long memberId,Map<String,Object> request) {
        ComputeWithdrawalAddressGuard.assertOutsideTransaction();
        repository.transferQuoteMemberRow(memberId);
        requirePayPassword(memberId,request);
        return requestResult(memberId,"WITHDRAWAL",request,false);
    }

    ComputeWithdrawalAddressGuard.Check withdrawalCheck(long memberId,Map<String,Object> request) {
        ComputeWithdrawalAddressGuard.assertOutsideTransaction();
        return withdrawalCheck(memberId,request,false,true);
    }

    /** 仅供已授权的后台手工提款预查，用户请求不能通过携带 operator 等字段进入此分支。 */
    ComputeWithdrawalAddressGuard.Check manualWithdrawalCheck(long memberId,Map<String,Object> request) {
        ComputeWithdrawalAddressGuard.assertOutsideTransaction();
        return withdrawalCheck(memberId,request,false,false);
    }

    private ComputeWithdrawalAddressGuard.Check withdrawalCheck(long memberId,Map<String,Object> request,boolean lock,boolean requireBoundAddress) {
        Long addressId = null;
        if (requireBoundAddress) addressId = requiredWithdrawalAddressId(request.get("addressId"));
        else if (request.get("addressId") != null) addressId = number(request.get("addressId"));
        if (!enabled(data.setting("withdrawalEnabled", "true"))) throw new BusinessException("server.compute.withdrawalDisabled");
        String network = text(request.get("network")), currency = currency(request.get("currency"), network);
        BigDecimal amount = amount(request.get("amount"));
        Map<String,Object> channel=currencies.requireChannel(currency,network,"WITHDRAW","EARNINGS",amount);
        Map<String,Object> addressInput=new LinkedHashMap<>(request);
        if(addressId!=null) {
            addressInput=repository.withdrawalAddress(addressId,memberId,currency,network,lock);
            if(addressInput.get("fieldsJson")!=null)addressInput.put("fields",data.jsonMap(addressInput.get("fieldsJson")));
        }
        var normalized=currencies.validateAddress(channel,addressInput);
        if(addressId!=null && (request.containsKey("address")||request.containsKey("memo")||request.containsKey("fields"))) {
            var confirmed=currencies.validateAddress(channel,request);
            if(!confirmed.equals(normalized))throw new BusinessException("server.compute.recipientChanged");
        }
        return new ComputeWithdrawalAddressGuard.Check(memberId,channel,normalized);
    }

    private static long requiredWithdrawalAddressId(Object value) {
        String input = String.valueOf(value);
        if (!input.matches("[1-9][0-9]{0,18}")) throw new BusinessException("server.compute.boundWithdrawalAddressRequired");
        try { return Long.parseLong(input); }
        catch (NumberFormatException exception) { throw new BusinessException("server.compute.boundWithdrawalAddressRequired"); }
    }

    private Map<String,Object> createWithdrawal(Map<String,Object> member,Map<String,Object> request,Integer operator,ComputeWithdrawalAddressGuard.Proof proof) {
        long memberId=number(member.get("id"));
        // 原请求重放已在入口返回；新用户提款在会员锁内重新锁定绑定地址，防止远端预查期间删除、停用或改收款人。
        var check=withdrawalCheck(memberId,request,true,operator==null);
        var channel=check.channel(); var normalized=check.recipient();
        String currency=text(channel.get("currency")),network=text(channel.get("network"));
        BigDecimal amount=amount(request.get("amount"));
        addressGuard.assertAllowed(memberId,channel,normalized,proof);
        String address=normalized.address();
        if(repository.createWithdrawalAddressCount(memberId, currency, network, address, normalized.memo())>0)throw new BusinessException("server.compute.management.addressDisabled");
        Map<String, Object> quote = withdrawalQuote(member, request);
        if (request.get("expectedFee") == null || request.get("expectedNetAmount") == null)
            throw new BusinessException("server.compute.quoteRequired");
        BigDecimal expectedFee = decimal(request.get("expectedFee"));
        BigDecimal expectedNet = amount(request.get("expectedNetAmount"));
        if (expectedFee.signum() < 0 || expectedFee.scale() > 8)
            throw new BusinessException("server.compute.invalidAmount");
        // 跨免费时段、次数变化或后台改价都必须重新让用户确认；已成功的同请求在前面的幂等分支直接返回。
        if (expectedFee.compareTo(decimal(quote.get("fee"))) != 0 || expectedNet.compareTo(decimal(quote.get("netAmount"))) != 0)
            throw new BusinessException("server.compute.quoteChanged");
        if (request.get("expectedAccountingRate")==null || request.get("expectedAccountingAmount")==null)
            throw new BusinessException("server.compute.quoteRequired");
        if (decimal(request.get("expectedAccountingRate")).compareTo(decimal(quote.get("accountingRate")))!=0
                || decimal(request.get("expectedAccountingAmount")).compareTo(decimal(quote.get("accountingAmount")))!=0)
            throw new BusinessException("server.compute.quoteChanged");
        var commitChannel=currencies.requireChannel(currency,network,"WITHDRAW","EARNINGS",amount);
        Map<String,Object> gatewaySnapshot=addressGuard.payoutSnapshot(memberId,commitChannel,normalized,proof);
        if(enabled(member.get("isTest")))gatewaySnapshot=fields("mode","MANUAL");
        var localReview = new ComputeWithdrawalReviewPolicy(data).decision(currency, amount, "MCAG".equals(gatewaySnapshot.get("mode")), enabled(member.get("isTest")),
                decimal(quote.get("accountingRate")),decimal(quote.get("accountingAmount")));
        var ruleSnapshot = new LinkedHashMap<>(data.jsonMap(data.json(quote.get("rule"))));
        ruleSnapshot.put("localReview",localReview);
        long id = data.insert("compute_withdrawal", fields("member_id", memberId, "currency", currency, "network", network,
                "amount", amount, "fee", quote.get("fee"), "net_amount", quote.get("netAmount"), "address", address, "address_snapshot",data.json(normalized.snapshot()),
                "accounting_version",2,"accounting_currency","USD","accounting_amount",quote.get("accountingAmount"),
                "accounting_rate",quote.get("accountingRate"),"accounting_quote_json",data.json(quote.get("accountingQuote")),
                "accounting_fee",quote.get("accountingFee"),"accounting_net_amount",quote.get("accountingNetAmount"),"accounting_status","FROZEN",
                "rule_id", quote.get("ruleId"), "rule_snapshot", data.json(ruleSnapshot), "status", "PENDING",
                "record_source",operator==null?"USER":"ADMIN_MANUAL","created_by",operator,
                "creation_remark",operator==null?"":text(request.get("remark")),"gateway_snapshot",data.json(gatewaySnapshot),"created_at", now()));
        BigDecimal accountingAmount=positiveAccountingAmount(quote.get("accountingAmount"));
        changeWallet(memberId, "EARNINGS", ACCOUNTING_WALLET_CURRENCY, accountingAmount.negate(), accountingAmount, "WITHDRAW_FREEZE", "WITHDRAW:" + id + ":FREEZE", "" + id);
        if(operator!=null)annotateLedger(memberId,"WITHDRAW:"+id+":FREEZE",operator,text(request.get("remark")));
        if (!enabled(localReview.get("required"))) queueWithdrawal(repository.createWithdrawalWithdrawalRow(id), null, "");
        return repository.createWithdrawalWithdrawalRow(id);
    }

    @Transactional
    public Map<String, Object> reviewDeposit(long id, boolean approved, String remark, int operator) {
        return reviewDepositInternal(id, approved, remark, operator);
    }

    /** 只供同包 MCAG 事务调用；空审核员表示系统验签入账，不冒用后台账号。 */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean creditMcagDeposit(long id) {
        Map<String,Object> deposit = repository.creditMcagDepositDepositRow(id);
        if (!"MCAG_VERIFIED".equals(deposit.get("paymentStatus")) || !"MCAG".equals(deposit.get("recordSource")))
            throw new BusinessException("server.compute.requestConflict");
        return "APPROVED".equals(reviewDepositInternal(id, true, "MCAG", null).get("status"));
    }

    private Map<String, Object> reviewDepositInternal(long id, boolean approved, String remark, Integer operator) {
        // 入账后的 VIP 资格也读取直推关系，始终先取关系锁，再锁会员和资金。
        new ComputeMemberRelations(data.jdbc()).lock();
        lockRewards();
        Map<String, Object> existing = repository.reviewDepositInternalDepositRow(id);
        long memberId = number(existing.get("memberId")); repository.initializeWalletsMemberRow(memberId);
        Map<String, Object> deposit = repository.reviewDepositInternalDepositRow2(id);
        if ("MCAG".equals(deposit.get("recordSource")) && (operator != null || !"MCAG_VERIFIED".equals(deposit.get("paymentStatus"))))
            throw new BusinessException("server.compute.mcag.automaticDeposit");
        if (!"PENDING".equals(deposit.get("status"))) throw new BusinessException("server.compute.alreadyReviewed");
        if (approved) {
            var prepared=new ComputeDepositAccounting(data).prepare(deposit);
            // 缺报价的充值继续待处理，不能先通过审核或发奖；下一次权威查单/审核可继续原记录。
            if (prepared.isEmpty()) return repository.createDepositDepositRow(id);
            deposit=prepared.get();
        }
        LocalDateTime postedAt = now();
        repository.reviewDepositInternalDepositWrite(approved ? "APPROVED" : "REJECTED", limited(remark, 500), operator, postedAt, id);
        if (approved) {
            changeWallet(memberId, text(deposit.get("walletType")), ACCOUNTING_WALLET_CURRENCY, positiveAccountingAmount(deposit.get("accountingAmount")), BigDecimal.ZERO,
                    "DEPOSIT", "DEPOSIT:" + id, "" + id);
            repository.reviewDepositInternalDepositWrite2(id);
            deposit.put("accountingStatus","POSTED");
            if (operator != null) annotateLedger(memberId,"DEPOSIT:"+id,operator,remark);
            financeSnapshots.capture("DEPOSIT", deposit, postedAt);
            if (rewards != null) rewards.onDepositApproved(id);
            // 与人工加款共用“可用余额达到门槛”的判断；在本次入账事务内立即升级，不等待额外加款或定时任务。
            if ("COMPUTE".equals(deposit.get("walletType")))
                new ComputeVipPeriodService(data, systemSettings).upgradeEligible(memberId);
            // 扩大的原币列会补齐30位尾零；通知保留有效精度，避免把存储补位展示给用户。
            messages.depositApproved(id,memberId,decimal(deposit.get("amount")).stripTrailingZeros(),text(deposit.get("currency")));
        }
        return repository.createDepositDepositRow(id);
    }

    @Transactional
    public Map<String, Object> reviewWithdrawal(long id, boolean approved, String remark, int operator) {
        Map<String, Object> existing = repository.reviewWithdrawalWithdrawalRow(id);
        long memberId = number(existing.get("memberId")); Map<String,Object> member=repository.reviewWithdrawalMemberRow(memberId);
        Map<String, Object> withdrawal = repository.reviewWithdrawalWithdrawalRow2(id);
        if (!"PENDING".equals(withdrawal.get("status"))) throw new BusinessException("server.compute.alreadyReviewed");
        Object storedGateway=withdrawal.get("gatewaySnapshot");
        Map<String,Object> gatewaySnapshot=storedGateway==null||text(storedGateway).isBlank()?Map.of():data.jsonMap(storedGateway);
        boolean gatewayPayment="MCAG".equals(gatewaySnapshot.get("mode"));
        if (approved && !enabled(member.get("isTest")) && gatewaySnapshot.isEmpty() && mcag != null && mcag.isEnabled()) {
            // 历史空快照按资产身份恢复支付路径；首次同步尚无映射不能把加密货币降级为人工完成。
            var assets = repository.reviewWithdrawalCurrencyAssetRows(withdrawal.get("currency"));
            if (assets.size() != 1 || !Set.of("CRYPTO", "FIAT").contains(text(assets.get(0).get("assetType"))))
                throw new BusinessException("server.compute.unsupportedCurrency");
            gatewayPayment = "CRYPTO".equals(assets.get(0).get("assetType"));
        }
        if (approved && !enabled(member.get("isTest")) && gatewayPayment) {
            queueWithdrawal(withdrawal,operator,remark);
            return repository.createWithdrawalWithdrawalRow(id);
        }
        var accounting=withdrawalPosting(withdrawal);
        BigDecimal amount = accounting.amount();
        changeWallet(memberId, "EARNINGS", accounting.currency(), approved ? BigDecimal.ZERO : amount, amount.negate(),
                approved ? "WITHDRAW_APPROVED" : "WITHDRAW_RELEASE", "WITHDRAW:" + id + ":REVIEW", "" + id);
        // 未接入的人工通道保留核验流程；测试完成分支绝不触发真实代付。
        String completion=approved?(enabled(member.get("isTest"))?"TEST_COMPLETED":"MANUAL_CONFIRMED"):null;
        LocalDateTime postedAt = now();
        repository.reviewWithdrawalWithdrawalWrite(approved ? "APPROVED" : "REJECTED", limited(remark, 500), operator, postedAt, completion, approved ? completion : "MANUAL_REJECTED", id);
        finishWithdrawalAccounting(withdrawal,approved);
        if (approved) {
            financeSnapshots.capture("WITHDRAWAL", withdrawal, postedAt);
            messages.withdrawalCompleted(id, memberId, decimal(withdrawal.get("netAmount")), text(withdrawal.get("currency")));
        }
        annotateLedger(memberId,"WITHDRAW:"+id+":REVIEW",operator,remark);
        return repository.createWithdrawalWithdrawalRow(id);
    }

    /** 仅对尚未进入任何代付流程的待审核单直接记为成功，保留原币订单与原冻结快照。 */
    @Transactional
    public Map<String,Object> directApproveWithdrawal(long id, String remark, int operator) {
        Map<String,Object> existing = repository.reviewWithdrawalWithdrawalRow(id);
        long memberId = number(existing.get("memberId"));
        repository.initializeWalletsMemberRow(memberId);
        Map<String,Object> withdrawal = repository.reviewWithdrawalWithdrawalRow2(id);
        if ("APPROVED".equals(withdrawal.get("status")) && "DIRECT_CONFIRMED".equals(withdrawal.get("completionMode"))) return withdrawal;
        if (!"PENDING".equals(withdrawal.get("status"))) throw new BusinessException("server.compute.v24Finance.directUnavailable");
        String paymentStatus = text(withdrawal.get("paymentStatus")), chainStatus = text(withdrawal.get("chainStatus"));
        if (!text(withdrawal.get("completionMode")).isEmpty() || !text(withdrawal.get("externalOrderNo")).isEmpty()
                || !text(withdrawal.get("txHash")).isEmpty() || !text(withdrawal.get("senderAddress")).isEmpty()
                || !Set.of("", "MANUAL_PENDING", "MANUAL_REVIEW").contains(paymentStatus)
                || !Set.of("", "NOT_APPLICABLE").contains(chainStatus)
                || !repository.directApproveWithdrawalMcagPayoutRows(id).isEmpty())
            throw new BusinessException("server.compute.v24Finance.directUnavailable");
        String auditRemark = limited(text(remark), 500);
        if (auditRemark.isBlank()) throw new BusinessException("server.compute.v24Finance.directRemarkRequired");
        var accounting = withdrawalPosting(withdrawal);
        if (withdrawal.get("accountingVersion") == null || number(withdrawal.get("accountingVersion")) == 1) {
            var freezes = repository.directApproveWithdrawalLedgerRows(memberId, "WITHDRAW:" + id + ":FREEZE");
            if (freezes.size() != 1) throw new BusinessException("server.compute.requestConflict");
            var freeze = freezes.get(0);
            if (!"EARNINGS".equals(freeze.get("walletType")) || !accounting.currency().equals(freeze.get("currency"))
                    || !"WITHDRAW_FREEZE".equals(freeze.get("kind")) || !Long.toString(id).equals(text(freeze.get("referenceId")))
                    || accounting.amount().negate().compareTo(decimal(freeze.get("amount"))) != 0
                    || accounting.amount().compareTo(decimal(freeze.get("frozenChange"))) != 0)
                throw new BusinessException("server.compute.requestConflict");
        }
        String event = "WITHDRAW:" + id + ":DIRECT";
        changeWallet(memberId, "EARNINGS", accounting.currency(), BigDecimal.ZERO, accounting.amount().negate(),
                "WITHDRAW_APPROVED", event, Long.toString(id));
        LocalDateTime postedAt = now();
        if (repository.directApproveWithdrawalWithdrawalWrite(auditRemark, operator, postedAt, id) != 1) throw new BusinessException("server.compute.requestConflict");
        finishWithdrawalAccounting(withdrawal, true);
        financeSnapshots.capture("WITHDRAWAL", withdrawal, postedAt);
        annotateLedger(memberId, event, operator, auditRemark);
        messages.withdrawalCompleted(id, memberId, decimal(withdrawal.get("netAmount")), text(withdrawal.get("currency")));
        return repository.createWithdrawalWithdrawalRow(id);
    }

    /** 调用方已持会员锁；冻结与持久任务同事务提交，外部发单仍由原代付工作器完成。 */
    private void queueWithdrawal(Map<String,Object> withdrawal, Integer operator, String remark) {
        if(mcag==null||!mcag.isEnabled())throw new BusinessException(503,"server.compute.gateway.notConfigured");
        withdrawalPosting(withdrawal);
        ComputeMcagPayoutService.enqueue(data, mcag, withdrawal);
        repository.queueWithdrawalWithdrawalWrite(limited(remark,500), operator, now(), withdrawal.get("id"));
    }

    /** 已验证的 MCAG 终态专用；由代付服务在同一短事务和相同锁顺序内调用。 */
    @Transactional(propagation = Propagation.MANDATORY)
    void completeMcagWithdrawal(long id, boolean success) {
        var existing = repository.reviewWithdrawalWithdrawalRow(id);
        long memberId = number(existing.get("memberId"));
        repository.initializeWalletsMemberRow(memberId);
        var withdrawal = repository.reviewWithdrawalWithdrawalRow2(id);
        String expected = success ? "MCAG_COMPLETED" : "MCAG_RELEASED";
        if (expected.equals(withdrawal.get("completionMode"))) return;
        if (!"PROCESSING".equals(withdrawal.get("status")) || !"MCAG_PENDING".equals(withdrawal.get("completionMode")))
            throw new BusinessException("server.compute.requestConflict");
        var accounting=withdrawalPosting(withdrawal);
        BigDecimal amount = accounting.amount();
        String event = "WITHDRAW:" + id + ":MCAG_FINAL";
        changeWallet(memberId,"EARNINGS",accounting.currency(),success ? BigDecimal.ZERO : amount,amount.negate(),
                success ? "WITHDRAW_APPROVED" : "WITHDRAW_RELEASE",event,text(withdrawal.get("orderNo")));
        if (withdrawal.get("reviewedBy") != null) annotateLedger(memberId,event,(int)number(withdrawal.get("reviewedBy")),text(withdrawal.get("remark")));
        LocalDateTime postedAt = now();
        repository.completeMcagWithdrawalWithdrawalWrite(success ? "APPROVED" : "REJECTED", expected, success ? "MCAG_SUCCEEDED" : "MCAG_FAILED", postedAt, id);
        finishWithdrawalAccounting(withdrawal,success);
        if (success) {
            financeSnapshots.capture("WITHDRAWAL", withdrawal, postedAt);
            messages.withdrawalCompleted(id, memberId, decimal(withdrawal.get("netAmount")), text(withdrawal.get("currency")));
        }
    }

    @Transactional
    public int settleOrder(long orderId, LocalDateTime settlementTime) {
        lockRewards();
        Map<String, Object> existing = repository.settleOrderOrderRow(orderId);
        long memberId = number(existing.get("memberId"));
        // 停用会员仍须履行已有订单；此处只锁定身份，不检查 enabled。
        repository.initializeWalletsMemberRow(memberId);
        Map<String, Object> order = repository.settleOrderOrderRow2(orderId);
        if (!"ACTIVE".equals(order.get("status"))) return 0;
        int duration = (int) number(order.get("durationDays")), settled = (int) number(order.get("settledDays"));
        LocalDateTime starts = LocalDateTime.ofInstant(Instant.parse(text(order.get("startsAt"))), ZoneOffset.UTC);
        int elapsed = (int) Math.min(duration, Math.max(0, ChronoUnit.DAYS.between(starts, settlementTime)));
        String mode = text(order.get("settlementMode"));
        if (elapsed <= settled || (Set.of("A", "B").contains(mode) && elapsed < duration)) return 0;
        BigDecimal principal = decimal(order.get("amount"));
        BigDecimal paid = decimal(order.get("paidIncome"));
        int firstDay = Set.of("A", "B").contains(mode) ? duration : settled + 1;
        for (int day = firstDay; day <= elapsed; day++) {
            BigDecimal cumulative = ComputeMoneyPolicy.dailyCumulativeIncome(mode,principal,decimal(order.get("dailyRate")),duration,day);
            BigDecimal income = cumulative.subtract(paid);
            BigDecimal returned = day == duration && ComputeMoneyPolicy.returnsPrincipal(mode) ? principal : BigDecimal.ZERO;
            data.insert("compute_order_settlement", fields("order_id", orderId, "member_id", memberId, "settlement_day", day,
                    "settlement_date", starts.plusDays(day).toInstant(ZoneOffset.UTC).atZone(systemZone()).toLocalDate(), "income", income, "principal", returned, "created_at", now()));
            if (income.signum() > 0) changeWallet(memberId, "EARNINGS", text(order.get("currency")), income, BigDecimal.ZERO,
                    "ORDER_INCOME", "SETTLE:" + orderId + ":" + day, "" + orderId);
            if (income.signum() > 0 && rewards != null) rewards.onIncomePosted(memberId, "SETTLE:" + orderId + ":" + day);
            if (returned.signum() > 0) changeWallet(memberId, "EARNINGS", text(order.get("currency")), returned, BigDecimal.ZERO,
                    "ORDER_PRINCIPAL", "PRINCIPAL:" + orderId, "" + orderId);
            paid = cumulative;
        }
        repository.settleOrderOrderWrite(paid, elapsed, elapsed == duration ? "COMPLETED" : "ACTIVE", orderId);
        return elapsed - settled;
    }

    @Transactional
    public Map<String, Object> saveAddress(long memberId, Map<String, Object> request) {
        return saveAddress(memberId,request,null);
    }

    @Transactional
    public Map<String, Object> saveAddress(long memberId, Map<String, Object> request, ComputeWithdrawalAddressGuard.Proof proof) {
        lockMember(memberId);
        requirePayPassword(memberId, request);
        String network = text(request.get("network")), currency = currency(request.get("currency"), network);
        var channel=currencies.requireChannel(currency,network,"ADDRESS","EARNINGS",null);
        var normalized=currencies.validateAddress(channel,request);
        String addressKey=addressGuard.assertAllowed(memberId,channel,normalized,proof);
        String address=normalized.address(),label=limited(text(request.get("label")),80);
        if(label.isEmpty())throw new BusinessException("server.compute.invalidInput");
        List<Map<String,Object>> existing=repository.saveAddressAddressRows(memberId, currency, network, addressKey, address.toLowerCase(Locale.ROOT)).stream()
                .filter(row->addressKey.equals(ComputeWithdrawalAddressGuard.key(text(channel.get("chainType")),network,text(row.get("address")),text(row.get("memo"))))).toList();
        if(!existing.isEmpty()){
            currencies.assertAddressCapacity(memberId,number(existing.get(0).get("id")));
            repository.saveAddressAddressWrite(label, data.json(normalized.fields()), addressKey, now(), existing.get(0).get("id"));
            return repository.saveAddressAddressRow(existing.get(0).get("id"));
        }
        currencies.assertAddressCapacity(memberId,null);
        long id=data.insert("compute_address",fields("member_id",memberId,"label",label,"currency",currency,"network",network,"address",address,"memo",normalized.memo(),"address_key",addressKey,"fields_json",data.json(normalized.fields()),"created_at",now()));
        return repository.saveAddressAddressRow(id);
    }

    @Transactional
    public void deleteAddress(long memberId, long id) {
        lockMember(memberId);
        if (!enabled(data.setting("withdrawalAddressDeleteEnabled", "true")))
            throw new BusinessException("server.compute.withdrawalAddressDeleteDisabled");
        addressGuard.assertRemovable(repository.deleteAddressAddressRow(id, memberId));
        if (repository.deleteAddressAddressWrite(now(), id, memberId) != 1) throw new BusinessException("server.compute.notFound");
    }

    public Map<String, Object> addressPolicy() {
        return fields("withdrawalAddressDeleteEnabled", enabled(data.setting("withdrawalAddressDeleteEnabled", "true")));
    }

    private Map<String, Object> withdrawalQuote(Map<String, Object> member, Map<String, Object> request) {
        if (!enabled(data.setting("withdrawalEnabled", "true"))) throw new BusinessException("server.compute.withdrawalDisabled");
        long memberId = number(member.get("id")); String network = text(request.get("network"));
        String currency = currency(request.get("currency"), network); BigDecimal amount = amount(request.get("amount"));
        Map<String,Object> channel=currencies.requireChannel(currency,network,"WITHDRAW","EARNINGS",amount);
        List<Map<String, Object>> rules = repository.withdrawalQuoteFeeRuleRows(memberId, member.get("level"), currency, network, amount, amount);
        Map<String,Object> withdrawalLimit = new ComputeWithdrawalLimitPolicy(data).assertAllowed(memberId,rules.isEmpty()?Map.of():rules.get(0),systemZone(),Instant.now());
        if("CHANNEL".equals(channel.get("feeMode"))){
            BigDecimal fee=ComputeCurrencyService.channelFee(channel,amount);
            validatePayoutPrecision(member,channel,amount,amount.subtract(fee));
            return addWithdrawalAccounting(memberId,channel,amount,fee,fields("fee",fee.toPlainString(),"netAmount",amount.subtract(fee).toPlainString(),"ruleId",null,"channelId",channel.get("id"),"feeSource","CHANNEL","withdrawalLimit",withdrawalLimit,"rule",fields("channel",channel,"withdrawalLimit",withdrawalLimit)));
        }
        if (rules.isEmpty()) throw new BusinessException("server.compute.withdrawalRuleMissing");
        Map<String, Object> rule = rules.get(0);
        ZoneId zone=systemZone(); LocalDateTime systemTime=LocalDateTime.now(zone); LocalDate today = systemTime.toLocalDate();
        LocalDate beginning = "WEEK".equals(rule.get("period")) ? today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : today.withDayOfMonth(1);
        Long count = repository.withdrawalQuoteWithdrawalCount(memberId, currency, network, LocalDateTime.ofInstant(beginning.atStartOfDay(zone).toInstant(),ZoneOffset.UTC));
        BigDecimal fee = ComputeMoneyPolicy.withdrawalFee(amount, rule, count == null ? 0 : count, systemTime);
        validatePayoutPrecision(member,channel,amount,amount.subtract(fee));
        return addWithdrawalAccounting(memberId,channel,amount,fee,fields("fee", fee.toPlainString(), "netAmount", amount.subtract(fee).toPlainString(), "ruleId", rule.get("id"), "channelId",channel.get("id"),"feeSource","ADVANCED_RULE","withdrawalLimit",withdrawalLimit,"rule",fields("channel",channel,"advancedRule",rule,"withdrawalLimit",withdrawalLimit)));
    }

    private Map<String,Object> withdrawalFunds(long memberId,Map<String,Object> channel,Map<String,Object> quote) {
        BigDecimal rate=decimal(quote.get("usdRate"));
        var balances=repository.withdrawalFundsWalletRows(memberId, ACCOUNTING_WALLET_CURRENCY);
        BigDecimal available=balances.isEmpty()?BigDecimal.ZERO:decimal(balances.get(0).get("available")).max(BigDecimal.ZERO);
        int scale=(int)Math.min(8,Math.max(0,number(channel.getOrDefault("amountScale",8))));
        var mapped=mappings.findChannel(text(channel.get("currency")),text(channel.get("network")));
        if(mapped!=null)scale=Math.min(scale,Math.max(0,mapped.getDecimals()));
        return fields("currency",channel.get("currency"),"network",channel.get("network"),"accountingCurrency","USD",
                "accountingRate",rate.toPlainString(),"availableUsd",available.toPlainString(),
                "availableAmount",ComputeUsdAccounting.nativeAvailable(available,rate,scale).toPlainString(),"quoteAt",Instant.now().toString());
    }

    private Map<String,Object> addWithdrawalAccounting(long memberId,Map<String,Object> channel,BigDecimal nativeAmount,BigDecimal nativeFee,Map<String,Object> result) {
        var quote=new ComputeUsdAccounting(data).strictQuote(text(channel.get("currency")));
        BigDecimal rate=decimal(quote.get("usdRate"));
        BigDecimal gross=positiveAccountingAmount(ComputeUsdAccounting.withdrawalUsd(nativeAmount,rate));
        BigDecimal fee=ComputeUsdAccounting.usdAmount(nativeFee,rate);
        // 冻结向上、费用折算向下；尾差保留在净额快照，保证三项账务金额严格守恒。
        BigDecimal net=positiveAccountingAmount(gross.subtract(fee));
        result.putAll(withdrawalFunds(memberId,channel,quote));
        result.putAll(fields("accountingVersion",2,"accountingAmount",gross.toPlainString(),"accountingFee",fee.toPlainString(),
                "accountingNetAmount",net.toPlainString(),"accountingQuote",quote,"feeCurrency",channel.get("currency"),"feeAmountUnit","WITHDRAWAL_CURRENCY"));
        return result;
    }

    private record WithdrawalPosting(String currency,BigDecimal amount) { }

    /** 旧订单按原币履约；新版必须使用提交时冻结的固定美元快照，任何缺失都不能猜测退款。 */
    private WithdrawalPosting withdrawalPosting(Map<String,Object> withdrawal) {
        long version=withdrawal.get("accountingVersion")==null?1:number(withdrawal.get("accountingVersion"));
        if(version==1)return new WithdrawalPosting(text(withdrawal.get("currency")),amount(withdrawal.get("amount")));
        if(version!=2 || !"USD".equals(withdrawal.get("accountingCurrency")) || !"FROZEN".equals(withdrawal.get("accountingStatus"))
                || withdrawal.get("accountingAmount")==null || withdrawal.get("accountingRate")==null
                || withdrawal.get("accountingFee")==null || withdrawal.get("accountingNetAmount")==null
                || withdrawal.get("accountingQuoteJson")==null)
            throw new BusinessException("server.compute.requestConflict");
        BigDecimal gross=positiveAccountingAmount(withdrawal.get("accountingAmount"));
        BigDecimal rate=decimal(withdrawal.get("accountingRate")),fee=decimal(withdrawal.get("accountingFee"));
        BigDecimal net=positiveAccountingAmount(withdrawal.get("accountingNetAmount"));
        if(rate.signum()<=0 || fee.signum()<0 || fee.scale()>8 || gross.compareTo(fee.add(net))!=0
                || gross.compareTo(ComputeUsdAccounting.withdrawalUsd(amount(withdrawal.get("amount")),rate))!=0
                || fee.compareTo(ComputeUsdAccounting.usdAmount(decimal(withdrawal.get("fee")),rate))!=0)
            throw new BusinessException("server.compute.requestConflict");
        if(rate.compareTo(decimal(data.jsonMap(withdrawal.get("accountingQuoteJson")).get("usdRate")))!=0)
            throw new BusinessException("server.compute.withdrawalRateSnapshotMismatch");
        // 钱包冻结余额是多笔订单的合计；结算前必须确认本单曾实际冻结同一金额，不能消费另一笔订单的冻结。
        String reference=Long.toString(number(withdrawal.get("id")));
        var freezes=repository.directApproveWithdrawalLedgerRows(number(withdrawal.get("memberId")), "WITHDRAW:"+reference+":FREEZE");
        if(freezes.size()!=1)throw new BusinessException("server.compute.requestConflict");
        var freeze=freezes.get(0);
        if(!"EARNINGS".equals(freeze.get("walletType")) || !ACCOUNTING_WALLET_CURRENCY.equals(freeze.get("currency"))
                || !"WITHDRAW_FREEZE".equals(freeze.get("kind")) || !reference.equals(text(freeze.get("referenceId")))
                || gross.negate().compareTo(decimal(freeze.get("amount")))!=0 || gross.compareTo(decimal(freeze.get("frozenChange")))!=0)
            throw new BusinessException("server.compute.requestConflict");
        return new WithdrawalPosting(ACCOUNTING_WALLET_CURRENCY,gross);
    }

    private void finishWithdrawalAccounting(Map<String,Object> withdrawal,boolean approved) {
        if(withdrawal.get("accountingVersion")==null || number(withdrawal.get("accountingVersion"))==1)return;
        String state=approved?"POSTED":"RELEASED";
        if(repository.finishWithdrawalAccountingWithdrawalWrite(state, withdrawal.get("id"))!=1)
            throw new BusinessException("server.compute.requestConflict");
        withdrawal.put("accountingStatus",state);
    }

    private static BigDecimal positiveAccountingAmount(Object value) {
        BigDecimal result=decimal(value);
        if(result.signum()<=0 || result.scale()>8 || result.precision()-result.scale()>20)
            throw new BusinessException("server.compute.invalidAmount");
        return result.setScale(8,RoundingMode.UNNECESSARY);
    }

    private void validatePayoutPrecision(Map<String,Object> member,Map<String,Object> channel,BigDecimal gross,BigDecimal net) {
        if (mcag==null || !mcag.isEnabled() || enabled(member.get("isTest"))) return;
        var mapped=mappings.findChannel(text(channel.get("currency")),text(channel.get("network")));
        if(mapped==null)return;
        int scale=Math.min(8,mapped.getDecimals());
        if (scale<0 || gross.stripTrailingZeros().scale()>scale || net.stripTrailingZeros().scale()>scale)
            throw new BusinessException("server.compute.mcag.payoutPrecision");
    }

    ComputeWithdrawalAddressGuard.Check addressCheck(long memberId,Map<String,Object> request) {
        ComputeWithdrawalAddressGuard.assertOutsideTransaction();
        repository.transferQuoteMemberRow(memberId);
        requirePayPassword(memberId,request);
        String network=text(request.get("network")),currency=currency(request.get("currency"),network);
        return managedAddressCheck(memberId,currency,network,request);
    }

    ComputeWithdrawalAddressGuard.Check managedAddressCheck(long memberId,String currency,String network,Map<String,Object> request) {
        var channel=currencies.requireChannel(currency,network,"ADDRESS","EARNINGS",null);
        return new ComputeWithdrawalAddressGuard.Check(memberId,channel,currencies.validateAddress(channel,request));
    }

    String validateManagedAddress(long memberId,Map<String,Object> channel,ComputeCurrencyService.AddressData normalized,ComputeWithdrawalAddressGuard.Proof proof) {
        return addressGuard.assertAllowed(memberId,channel,normalized,proof);
    }

    private void ensureWallet(long memberId, String type, String currency) {
        repository.ensureWalletWalletWrite(memberId, type, currency, now());
    }

    void changeWallet(long memberId, String wallet, String currency, BigDecimal availableDelta, BigDecimal frozenDelta,
                      String kind, String event, String reference) {
        ensureWallet(memberId, wallet, currency);
        Map<String, Object> balance = repository.changeWalletWalletRow(memberId, wallet, currency);
        BigDecimal available = decimal(balance.get("available")).add(availableDelta), frozen = decimal(balance.get("frozen")).add(frozenDelta);
        if (available.signum() < 0 || frozen.signum() < 0) throw new BusinessException("server.compute.insufficientBalance");
        repository.changeWalletWalletWrite(available, frozen, now(), balance.get("id"));
        data.insert("compute_ledger", fields("member_id", memberId, "wallet_type", wallet, "currency", currency, "kind", kind,
                "amount", availableDelta, "frozen_change", frozenDelta, "available_after", available, "frozen_after", frozen,
                "reference_id", reference, "event_key", event, "created_at", now()));
    }

    private void annotateLedger(long memberId,String event,int operator,String remark) {
        repository.annotateLedgerLedgerWrite(operator, Objects.toString(remark,""), memberId, event);
    }

    private void requirePayPassword(long memberId,Map<String,Object> request) {
        String hash=repository.requirePayPasswordMemberValue(memberId);
        if (hash == null || hash.isBlank()) throw new BusinessException("server.compute.v13.payPasswordRequired");
        if (!passwords.matchesPassword(Objects.toString(request.get("payPassword"), ""), hash))
            throw new BusinessException("server.compute.payPasswordInvalid");
    }

    private Map<String, Object> lockMember(long memberId) { return repository.lockMemberMemberRow(memberId); }
    private String walletType(Object value) { String wallet = text(value); if (!WALLETS.contains(wallet)) throw new BusinessException("server.compute.invalidWallet"); return wallet; }

    private String currency(Object value, String network) {
        String currency = text(value);if(currency.isEmpty())currency=currencies.defaultCurrency();
        Integer count=repository.activeCurrencyCount(currency,network);
        if (count == null || count == 0) throw new BusinessException("server.compute.unsupportedCurrency");
        return currency;
    }

    public String validateAddress(String network, Object value) {
        String address = text(value);
        boolean valid = switch (network) {
            case "TRC20" -> address.matches("T[1-9A-HJ-NP-Za-km-z]{33}");
            case "ERC20", "BEP20" -> address.matches("0x[0-9a-fA-F]{40}");
            default -> address.matches("[A-Za-z0-9:_-]{16,200}");
        };
        if (!valid) throw new BusinessException("server.compute.invalidAddress");
        return address;
    }

    private Map<String, Object> beginRequest(long memberId, String kind, Map<String, Object> request) {
        return requestResult(memberId,kind,request,true);
    }

    private Map<String, Object> requestResult(long memberId, String kind, Map<String, Object> request, boolean create) {
        String requestId = text(request.get("requestId"));
        if (!requestId.matches("[A-Za-z0-9:_-]{8,80}") || !requestId.equals(request.get("requestId"))) throw new BusinessException("server.compute.requestIdRequired");
        String digest;
        try { digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.json(new TreeMap<>(request)).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("请求摘要生成失败", exception); }
        List<Map<String, Object>> previous = repository.requestResultRequestRows(memberId, requestId);
        if (!previous.isEmpty()) {
            Map<String, Object> row = previous.get(0);
            if (!kind.equals(row.get("requestType")) || !digest.equals(row.get("payloadHash"))) throw new BusinessException("server.compute.requestConflict");
            if (row.get("resultJson") == null) throw new BusinessException("server.compute.requestInProgress");
            return data.jsonMap(text(row.get("resultJson")));
        }
        if(create)data.insert("compute_request", fields("member_id", memberId, "request_id", requestId, "request_type", kind, "payload_hash", digest, "created_at", now()));
        return null;
    }

    private Map<String, Object> finishRequest(long memberId, Map<String, Object> request, Map<String, Object> result) {
        repository.finishRequestRequestWrite(data.json(result), memberId, request.get("requestId"));
        return result;
    }

    static String limited(String value, int maximum) {
        if (value != null && value.length() > maximum) throw new BusinessException("server.compute.invalidInput");
        return value;
    }
}
