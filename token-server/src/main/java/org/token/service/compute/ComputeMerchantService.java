package org.token.service.compute;

import org.token.repository.compute.ComputeDisplayNameRepository;

import org.token.repository.compute.ComputeMerchantRepository;

import org.token.exception.BusinessException;
import org.token.service.TotpService;
import org.token.config.compute.ComputeMcagProperties;
import org.token.repository.compute.ComputeData;
import org.token.model.request.compute.DepositHashRecheckRequest;
import org.token.service.compute.ComputeAgentScope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/** 商户管理只代理真实商户协议；不把平台管理权限、私钥或用户钱包账本混入该边界。 */
@Service
public class ComputeMerchantService {
    private final ObjectProvider<ComputeMcagClient> clients;
    private final ComputeMcagProperties config;
    private final ComputeAgentScope scope;
    private final TotpService totp;
    private final ComputeData data;
    private final ComputeMerchantRepository repository;
    private final ComputeDisplayNameRepository displayNames;
    private final ComputeMcagConfigSyncService configurationSync;
    @org.springframework.beans.factory.annotation.Autowired
    public ComputeMerchantService(ObjectProvider<ComputeMcagClient> clients,ComputeMcagProperties config,ComputeAgentScope scope,TotpService totp,ComputeData data,ComputeMcagConfigSyncService configurationSync,
            ComputeMerchantRepository repository,ComputeDisplayNameRepository displayNames) {
        this.clients=clients;this.config=config;this.scope=scope;this.totp=totp;this.data=data;this.configurationSync=configurationSync;

        this.repository = repository;
        this.displayNames = displayNames;
    }

    public ComputeMerchantService(ObjectProvider<ComputeMcagClient> clients,ComputeMcagProperties config,ComputeAgentScope scope,TotpService totp,ComputeData data,ComputeMcagConfigSyncService configurationSync,ComputeMerchantRepository repository) {
        this(clients,config,scope,totp,data,configurationSync,repository,new ComputeDisplayNameRepository(data));
    }

    public ComputeMerchantService(ObjectProvider<ComputeMcagClient> clients,ComputeMcagProperties config,ComputeAgentScope scope,TotpService totp,ComputeData data,ComputeMcagConfigSyncService configurationSync) {
        this(clients, config, scope, totp, data, configurationSync, new ComputeMerchantRepository(data));
    }
    public ComputeMerchantService(ObjectProvider<ComputeMcagClient> clients,ComputeMcagProperties config,ComputeAgentScope scope,TotpService totp,ComputeData data) {this(clients,config,scope,totp,data,null);}

    private record Query(String operation, Set<String> fields, boolean pages, boolean cursor) { }
    private static Query query(String operation, boolean pages, boolean cursor, String... fields) {
        return new Query(operation, Set.of(fields), pages, cursor);
    }
    private static final Map<String, Query> QUERIES = Map.ofEntries(
            Map.entry("wallets", query("merchant-wallets/query", false, false, "network_id")),
            Map.entry("destinations", query("collection-destinations/query", false, false, "network_id")),
            Map.entry("fee-account", query("service-fee/account/query", false, false)),
            Map.entry("fee-ledger", query("service-fee/ledger/query", true, false, "entry_type", "asset_id", "resource_id", "exclude_topups", "start_time", "end_time")),
            Map.entry("bills", query("merchant-bills/query", true, false, "network_id", "asset_id", "wallet_id", "tx_hash", "direction", "bill_type", "status", "address", "start_time", "end_time")),
            Map.entry("batches", query("collection-batches/query", true, true, "batch_id", "customer_id", "asset_id", "network_id", "purpose", "status", "sweep_status", "reason_code", "address", "tx_hash", "start_time", "end_time", "view", "receipt_after_id")),
            Map.entry("sweeps", query("sweeps/query", true, true, "batch_id", "asset_id", "network_id", "status", "reason_code", "tx_hash", "address", "start_time", "end_time")),
            Map.entry("resources", query("resource-orders/query", true, false, "request_id", "operation_id", "provider_order_id", "status", "reason_code", "network_id", "address", "resource_type", "purpose", "start_time", "end_time")),
            Map.entry("notifications", query("notifications/query", true, false)),
            Map.entry("attempts", query("notifications/attempts/query", false, false, "delivery_id")),
            Map.entry("rechecks", query("deposit-rechecks/query", false, true, "recheck_id", "merchant_recheck_id", "asset_id", "network_id", "customer_id")));
    private static final Map<String,String> PAGE_IDS=Map.of("batches","batch_id","sweeps","operation_id","bills","bill_id",
            "fee-ledger","entry_id","resources","request_id","notifications","delivery_id");

    public Map<String, Object> context() {
        // 此接口只返回入口可用性；代理账号不请求任何全商户资料。
        boolean global = scope.isGlobalScope();
        return Map.of("global", global, "enabled", global && config.isEnabled(),
                "environment", "LIVE", "platformManaged", true);
    }

    /** 筛选只需公开币网选项，不借用连接设置权限或透传资金策略。 */
    public Map<String,Object> filterOptions() {
        scope.requireGlobalScope();
        return ComputeMerchantView.filterOptions(client().request("assets/query",Map.of()));
    }

    /** 仅查询当前商户绑定账号的已保存快照，不触发供应商同步和资源采购。 */
    public Map<String,Object> energyAccount() {
        scope.requireGlobalScope();
        return ComputeMerchantView.energyAccount(client().request("energy-account/query",Map.of()));
    }

    public Map<String, Object> query(String resource, Map<String, String> input) {
        scope.requireGlobalScope();
        Query query = QUERIES.get(resource);
        if (query == null) throw invalid();
        String reason=input.get("reason_code");
        if(reason!=null&&(reason.length()>256||reason.chars().anyMatch(Character::isISOControl)))throw invalid();
        Map<String, Object> body = new LinkedHashMap<>();
        for (String key : query.fields()) optional(body, input, key);
        // 旧调用的 before_id 保留原游标语义；明确传页码的归集列表使用服务端计数分页。
        if (query.pages() && (!query.cursor() || (input.containsKey("page") && !input.containsKey("before_id")))) {
            body.put("page", integer(input.getOrDefault("page", "1"), 1, 100000));
        }
        if (query.pages() || query.cursor()) body.put("limit", integer(input.getOrDefault("limit", "20"), 1, 100));
        if (resource.equals("bills")) {
            body.remove("limit");
            body.put("page_size", integer(input.getOrDefault("page_size", input.getOrDefault("limit", "20")), 1, 100));
        }
        if (query.cursor()) optional(body, input, "before_id");
        if (resource.equals("batches")) batchPage(body, input);
        if (resource.equals("attempts") && !body.containsKey("delivery_id")) throw invalid();
        if (resource.equals("resources")) {
            // 资源采购已切换到当前请求与步骤；不能让旧网关忽略模式后继续返回历史登记单。
            if (input.containsKey("energy_order_id")) throw invalid();
            body.put("view", "CURRENT");
            if (body.containsKey("request_id")) { body.remove("page"); body.remove("limit"); }
        }
        if(Set.of("wallets","destinations","fee-account").contains(resource))body.put("view","summary");
        Map<String, Object> result = readPage(resource, query, body);
        if (resource.equals("resources") && (result == null || !"CURRENT".equals(result.get("view"))
                || !(result.get("items") instanceof List<?>)
                || (body.containsKey("request_id") && !(result.get("request") instanceof Map<?, ?>))))
            throw new BusinessException("server.compute.payment.invalidResponse");
        if(Set.of("wallets","destinations").contains(resource))return ComputeMerchantView.rows(result,true);
        if("fee-account".equals(resource))return ComputeMerchantView.account(result);
        return Set.of("batches", "rechecks").contains(resource) ? withUsernames(result) : result;
    }

    /** 大页按真实偏移读取有界的十条小页，避免单次商户报文超限；任一片异常不返回半页。 */
    private Map<String,Object> readPage(String resource,Query query,Map<String,Object> body) {
        String sizeField=resource.equals("bills")?"page_size":"limit";
        int size=body.get(sizeField) instanceof Integer value?value:0;
        if(!PAGE_IDS.containsKey(resource)||!body.containsKey("page")||body.containsKey("before_id")||size<=20)
            return client().request(query.operation(),body);
        int page=(Integer)body.get("page"),chunkSize=10;
        long offset=(long)(page-1)*size,firstPage=offset/chunkSize+1;
        int skip=(int)(offset%chunkSize),maxRemotePage=resource.equals("resources")?1000000:100000;
        if(firstPage>maxRemotePage)throw invalid();
        ComputeMcagClient gateway=client();
        List<Map<String,Object>> combined=new ArrayList<>();
        Set<String> ids=new HashSet<>();
        Map<String,Object> result=null;
        long total=-1;int wanted=0,fragments=1;
        for(int fragment=0;fragment<fragments;fragment++) {
            int remotePage=Math.toIntExact(firstPage+fragment);
            var request=new LinkedHashMap<>(body);request.put("page",remotePage);request.put(sizeField,chunkSize);
            var response=gateway.request(query.operation(),request);
            if(response==null||!(response.get("items") instanceof List<?> items)
                    || resource.equals("resources")&&!"CURRENT".equals(response.get("view")))throw invalidPage();
            long currentTotal=pageNumber(response.get("total"));
            if(currentTotal<0 || response.containsKey("page")&&pageNumber(response.get("page"))!=remotePage)throw invalidPage();
            for(String field:List.of("limit","page_size"))
                if(response.containsKey(field)&&pageNumber(response.get(field))!=chunkSize)throw invalidPage();
            if(fragment==0) {
                total=currentTotal;result=new LinkedHashMap<>(response);
                wanted=(int)Math.min(size,Math.max(0,total-offset));
                // 任意21..100的合法size也按原偏移裁切，最多11片；空页仍核验一次权威total。
                fragments=Math.max(1,(skip+wanted+chunkSize-1)/chunkSize);
                if(firstPage+fragments-1>maxRemotePage)throw invalid();
            } else if(currentTotal!=total)throw invalidPage();
            int expected=(int)Math.min(chunkSize,Math.max(0,total-(long)(remotePage-1)*chunkSize));
            if(items.size()!=expected)throw invalidPage();
            for(Object value:items) {
                if(!(value instanceof Map<?,?> row)||!(row.get(PAGE_IDS.get(resource)) instanceof String id)
                        ||id.isBlank()||!ids.add(id))throw invalidPage();
                var copy=new LinkedHashMap<String,Object>();
                for(var entry:row.entrySet()) {
                    if(!(entry.getKey() instanceof String key))throw invalidPage();
                    copy.put(key,entry.getValue());
                }
                combined.add(copy);
            }
        }
        var items=wanted==0?List.<Map<String,Object>>of():new ArrayList<>(combined.subList(skip,skip+wanted));
        result.put("items",items);result.put("page",page);result.put(sizeField,size);
        for(String field:List.of("limit","page_size"))if(result.containsKey(field))result.put(field,size);
        boolean hasMore=offset+wanted<total;
        if(result.containsKey("has_more"))result.put("has_more",hasMore);
        if(result.containsKey("next_cursor"))result.put("next_cursor",hasMore&&!items.isEmpty()?items.get(items.size()-1).get(PAGE_IDS.get(resource)):null);
        return result;
    }

    private static long pageNumber(Object value) {
        if(!(value instanceof Number)&&!(value instanceof String))throw invalidPage();
        try { return new java.math.BigDecimal(value.toString()).longValueExact(); }
        catch(NumberFormatException|ArithmeticException failure) {throw invalidPage();}
    }
    private static BusinessException invalidPage() {return new BusinessException("server.compute.payment.invalidResponse");}

    /** 后台列表只取摘要，详情每次代理一页；资金补偿另行完整核验，此处不自动合并批次收款明细。 */
    private static void batchPage(Map<String, Object> body, Map<String, String> input) {
        if (body.containsKey("view") && !Set.of("summary", "receipts").contains(body.get("view"))) throw invalid();
        if (!body.containsKey("batch_id")) {
            if (body.containsKey("receipt_after_id") || input.containsKey("receipt_limit") || input.containsKey("expected_resource_version")) throw invalid();
            body.put("view", "summary");
            return;
        }
        body.remove("limit");
        body.remove("page");
        body.remove("before_id");
        body.put("view", "receipts");
        body.put("receipt_limit", integer(input.getOrDefault("receipt_limit", "20"), 1, 50));
        if (input.containsKey("expected_resource_version")) {
            try {
                long version = Long.parseLong(input.get("expected_resource_version"));
                if (version < 1) throw invalid();
                body.put("expected_resource_version", version);
            } catch (NumberFormatException failure) { throw invalid(); }
        }
        if (body.containsKey("receipt_after_id") && !body.containsKey("expected_resource_version")) throw invalid();
    }

    /** 刷新Crypto已跟踪的账本结果，不发起节点余额核查，也不更新用户钱包。 */
    public Map<String, Object> refresh(String kind, String reference) {
        scope.requireGlobalScope();
        String operation, field;
        if ("wallet".equals(kind)) { operation = "merchant-wallets/refresh"; field = "wallet_id"; }
        else if ("destination".equals(kind)) { operation = "collection-destinations/refresh"; field = "network_id"; }
        else throw invalid();
        return ComputeMerchantView.address(client().request(operation, Map.of(field, text(reference),"view","summary")),true);
    }

    /** 当前商户协议纯查询服务费公开地址及余额，不再申请或改变资金去向。 */
    public Map<String, Object> feeAddresses() {
        scope.requireGlobalScope();
        var response=client().request("service-fee/addresses/query", Map.of("view","summary"));
        if (!(response.get("items") instanceof List<?> rows)) throw new BusinessException("server.compute.payment.invalidResponse");
        for (var raw:rows) {
            if (!(raw instanceof Map<?,?> row) || !"SERVICE_FEE".equals(row.get("purpose"))
                    || !Objects.equals(config.getMerchantId(),row.get("merchant_id")) || !Objects.equals(config.getEnvironment(),row.get("environment")))
                throw new BusinessException("server.compute.payment.invalidResponse");
        }
        return ComputeMerchantView.rows(response,false);
    }

    public Map<String,Object> refreshFeeAddress(String addressId) {
        scope.requireGlobalScope();
        return ComputeMerchantView.address(client().request("service-fee/addresses/refresh",Map.of("address_id",text(addressId),"view","summary")),false);
    }

    /** 连接配置仅提供非敏感状态，不向浏览器下发任何密钥内容或服务器文件路径。 */
    public Map<String, Object> connection() {
        scope.requireGlobalScope();
        var info = new LinkedHashMap<String, Object>();
        info.put("enabled", config.isEnabled()); info.put("environment", config.getEnvironment());
        info.put("baseUrl", publicEndpoint(config.getBaseUrl())); info.put("merchantId", config.getMerchantId());
        info.put("credentialId", config.getCredentialId());
        var client = clients.getIfAvailable();
        info.put("keysReady", client != null && config.isEnabled());
        info.put("managedExternally", true);
        return info;
    }

    /** 即使停用配置尚未通过客户端校验，也不展示误填在网址中的口令和查询参数。 */
    private String publicEndpoint(String value) {
        if (value == null) return "";
        try {
            var uri = java.net.URI.create(value);
            if (uri.getHost() == null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) return "";
            return new java.net.URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (IllegalArgumentException | java.net.URISyntaxException error) { return ""; }
    }

    public Map<String, Object> checkConnection() {
        scope.requireGlobalScope();
        var response = client().request("assets/query", Map.of());
        if (!(response.get("items") instanceof List<?> assets)) throw new BusinessException("server.compute.payment.invalidResponse");
        // assets:read 成功只能证明基础通信；首页钱包和积分查询还需要 balances:read。
        String balances = "READY";
        try {
            var account = client().request("service-fee/account/query", Map.of("view","summary"));
            for(String field:List.of("available","reserved")) {
                String value=Objects.toString(account.get(field),"");
                if(!value.matches("[0-9]{1,30}(\\.[0-9]{1,18})?"))throw new BusinessException("server.compute.payment.invalidResponse");
            }
        } catch (BusinessException failure) {
            balances = "server.compute.payment.accessDenied".equals(failure.getMessage()) ? "DENIED" : "FAILED";
        }
        return Map.of("connected", true, "checkedAt", Instant.now().toString(), "assetCount", assets.size(),
                "balanceAccess", balances, "ready", "READY".equals(balances));
    }

    public Map<String, Object> addressQr(String address) {
        scope.requireGlobalScope();
        try {
            var matrix = new com.google.zxing.qrcode.QRCodeWriter().encode(text(address), com.google.zxing.BarcodeFormat.QR_CODE, 280, 280);
            var output = new java.io.ByteArrayOutputStream();
            com.google.zxing.client.j2se.MatrixToImageWriter.writeToStream(matrix, "PNG", output);
            return Map.of("image", "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray()));
        } catch (com.google.zxing.WriterException | java.io.IOException error) { throw invalid(); }
    }

    public Map<String, Object> recheck(Map<String, String> input) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (String field : List.of("merchant_recheck_id", "asset_id", "tx_hash", "address_id")) body.put(field, text(input.get(field)));
        optional(body, input, "network_id"); optional(body, input, "transfer_locator");
        // 使用网关已登记地址定位，不允许手工填写会员数据库编号或把线索金额当作实际入账金额。
        requireSuperTotp(input.get("code"));
        return client().request("deposit-rechecks/create", body);
    }

    /** 使用当前商户已授权的真实币网，不让浏览器指定商户或拼装任意网关操作。 */
    public Map<String, Object> depositRecheckOptions() {
        requireSuper();
        return Map.of("merchantId", config.getMerchantId(), "environment", config.getEnvironment(), "assets", depositRecheckAssets());
    }

    public Map<String, Object> depositRecheck(DepositHashRecheckRequest input) {
        requireSuper();
        Map<String,Object> body;
        try { body=depositRecheckBody(input); }
        catch (BusinessException failure) {
            // 创建请求尚未发出，允许首次失败的表单修正；浏览器不能仅凭 HTTP 错误猜测是否已经受理。
            throw new BusinessException(failure.getCode(),Map.of("recheckSubmission","NOT_SUBMITTED"),failure.getMessage());
        }
        String requestId=body.get("merchant_recheck_id").toString(),assetId=body.get("asset_id").toString();
        String networkId=body.get("network_id").toString(),hash=body.get("tx_hash").toString();
        // 与 Crypto 充值订单的按 Hash 补查共用持久任务；此处不直接写充值、余额或账本。
        Map<String,Object> result;
        try { result=client().request("deposit-rechecks/create",body); }
        catch (ComputeMcagRejectedException failure) {
            String key=switch (failure.getGatewayCode()) {
                case "ADDRESS_NOT_FOUND", "ADDRESS_MISMATCH", "ADDRESS_NOT_OWNED" -> "server.compute.v47Recheck.addressMismatch";
                case "ADDRESS_NOT_READY" -> "server.compute.v47Recheck.addressNotReady";
                case "ASSET_DISABLED", "ASSET_NETWORK_MISMATCH" -> "server.compute.v47Recheck.assetUnavailable";
                case "INVALID_TRANSACTION_HASH" -> "server.compute.v47Recheck.invalidHash";
                case "INVALID_PURPOSE" -> "server.compute.v47Recheck.invalidPurpose";
                default -> null;
            };
            if ("IDEMPOTENCY_CONFLICT".equals(failure.getGatewayCode())) throw new BusinessException("server.compute.requestConflict");
            if (key==null) throw failure;
            throw new BusinessException(400,Map.of("recheckSubmission","NOT_SUBMITTED"),key);
        }
        if (result==null || !config.getMerchantId().equals(result.get("merchant_id")) || !config.getEnvironment().equals(result.get("environment"))
                || !requestId.equals(result.get("merchant_recheck_id")) || !assetId.equals(result.get("asset_id"))
                || !networkId.equals(result.get("network_id")) || !hash.equalsIgnoreCase(Objects.toString(result.get("tx_hash"),""))
                || !Set.of("PENDING","PROCESSING","RETRY","RESOLVED","REJECTED","NOT_FOUND").contains(Objects.toString(result.get("status"),"")))
            throw new BusinessException("server.compute.payment.invalidResponse");
        var response=new LinkedHashMap<String,Object>();
        response.put("recheckId",remoteText(result,"recheck_id",128));response.put("requestId",requestId);response.put("status",result.get("status"));
        for (var pair:Map.of("resultCode","result_code","nextRetryAt","next_retry_at","resolvedAt","resolved_at").entrySet())
            if (result.get(pair.getValue())!=null) response.put(pair.getKey(),result.get(pair.getValue()));
        return response;
    }

    private Map<String,Object> depositRecheckBody(DepositHashRecheckRequest input) {
        String requestId=bounded(input.requestId(),128),assetId=bounded(input.assetId(),128),networkId=bounded(input.networkId(),128);
        String hash=bounded(input.txHash(),66),address=bounded(input.address(),200);
        String locator=input.transferLocator()==null||input.transferLocator().isBlank()?null:bounded(input.transferLocator(),80);
        if (!requestId.matches("[A-Za-z0-9_-]{1,128}")) throw invalid();
        requireSuperTotp(input.code());
        var asset=depositRecheckAssets().stream().filter(row->assetId.equals(row.get("assetId")) && networkId.equals(row.get("networkId")))
                .findFirst().orElseThrow(()->new BusinessException("server.compute.v47Recheck.assetUnavailable"));
        if (!("EVM".equals(asset.get("family"))?hash.matches("0[xX][0-9a-fA-F]{64}"):hash.matches("[0-9a-fA-F]{64}")))
            throw new BusinessException("server.compute.v47Recheck.invalidHash");
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("merchant_recheck_id",requestId);body.put("asset_id",assetId);body.put("network_id",networkId);
        body.put("tx_hash",hash.toLowerCase(Locale.ROOT));body.put("address",address);
        if (locator!=null) body.put("transfer_locator",locator);
        return body;
    }

    private List<Map<String,Object>> depositRecheckAssets() {
        if (!"LIVE".equals(config.getEnvironment())) throw invalid();
        var result=client().request("assets/query",Map.of());
        if (result==null || !(result.get("items") instanceof List<?>)) throw new BusinessException("server.compute.payment.invalidResponse");
        var assets=new ArrayList<Map<String,Object>>();
        var identifiers=new HashSet<String>();
        for (var raw:(List<?>)result.get("items")) {
            if (!(raw instanceof Map<?,?> row)) throw new BusinessException("server.compute.payment.invalidResponse");
            if (!Boolean.TRUE.equals(row.get("deposit_enabled"))) continue;
            String assetId=remoteText(row,"asset_id",128),networkId=remoteText(row,"network_id",128),symbol=remoteText(row,"symbol",32);
            String family=remoteText(row,"family",32);
            if (!identifiers.add(assetId) || !Set.of("TRON","EVM","BITCOIN").contains(family)) throw new BusinessException("server.compute.payment.invalidResponse");
            String networkName=row.get("network_name")==null?networkId:remoteText(row,"network_name",128);
            assets.add(Map.of("assetId",assetId,"networkId",networkId,"networkName",networkName,"family",family,"symbol",symbol,"displayName",symbol));
        }
        return assets;
    }

    private static String bounded(String value,int maximum) {
        if (value==null || value.isBlank() || value.length()>maximum || value.chars().anyMatch(Character::isISOControl)) throw invalid();
        return value.trim();
    }

    private static String remoteText(Map<?,?> value,String key,int maximum) {
        if (!(value.get(key) instanceof String text) || text.isBlank() || text.length()>maximum || text.chars().anyMatch(Character::isISOControl))
            throw new BusinessException("server.compute.payment.invalidResponse");
        return text;
    }

    public Map<String, Object> synchronization() {
        scope.requireGlobalScope();
        var snapshots = repository.synchronizationMerchantSnapshotRows(config.getMerchantId(), config.getEnvironment());
        Map<String, Object> assets = Map.of();
        for (var row : snapshots) {
            Object value = row.remove("snapshotJson");
            Map<String, Object> snapshot = value == null ? Map.of() : data.jsonMap(Objects.toString(value));
            row.put("snapshot", snapshot);
            if ("assets".equals(row.get("resource"))) assets = snapshot;
        }
        List<Map<String, Object>> mappings = new ArrayList<>();
        boolean syncedAssets = assets.get("items") instanceof List<?>;
        List<?> remoteAssets = assets.get("items") instanceof List<?> items ? items : List.of();
        for (var channel : new ComputeMcagChannelService(data,config).listChannels()) {
            Map<?, ?> remote = remoteAssets.stream().filter(Map.class::isInstance).map(Map.class::cast)
                    .filter(item -> Objects.equals(item.get("asset_id"), channel.getAssetId())).findFirst().orElse(null);
            String state = !syncedAssets ? "NOT_SYNCED" : remote == null ? "MISSING" :
                    Objects.equals(remote.get("network_id"), channel.getNetworkId()) && Objects.equals(remote.get("symbol"), channel.getCurrency()) ? "MATCHED" : "MISMATCH";
            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("currency", channel.getCurrency()); mapping.put("network", channel.getNetwork());
            mapping.put("asset_id", channel.getAssetId()); mapping.put("network_id", channel.getNetworkId()); mapping.put("status", state);
            if (remote != null) { mapping.put("deposit_enabled", remote.get("deposit_enabled")); mapping.put("payout_enabled", remote.get("payout_enabled")); }
            mappings.add(mapping);
        }
        return Map.of("snapshots", snapshots, "mappings", mappings, "enabled", config.isEnabled(), "environment", config.getEnvironment());
    }

    private Map<String, Object> withUsernames(Map<String, Object> result) {
        if (!(result.get("items") instanceof List<?> items)) return result;
        Set<String> ids = new HashSet<>();
        for (Object value : items) if (value instanceof Map<?, ?> row) { String id = customerId(row); if (id != null) ids.add(id); }
        Map<String, String> names = new HashMap<>();
        if (!ids.isEmpty()) displayNames.merchantCustomerNames(config.getMerchantId(), config.getEnvironment(), ids)
                .forEach(row -> names.put((String) row.get("customerId"), (String) row.get("username")));
        List<Map<String, Object>> records = new ArrayList<>();
        for (Object value : items) if (value instanceof Map<?, ?> row) {
            Map<String, Object> copy = new LinkedHashMap<>(); row.forEach((key, field) -> copy.put(Objects.toString(key), field));
            copy.remove("username");
            String id = customerId(row); if (id != null && names.containsKey(id)) copy.put("username", names.get(id));
            records.add(copy);
        }
        Map<String, Object> response = new LinkedHashMap<>(result); response.put("items", records); return response;
    }
    private String customerId(Map<?, ?> row) {
        if (row.containsKey("merchant_id") && !Objects.equals(config.getMerchantId(), row.get("merchant_id"))
                || row.containsKey("environment") && !Objects.equals(config.getEnvironment(), row.get("environment"))) return null;
        return row.get("customer_id") instanceof String value && !value.isBlank() && value.length() <= 128 ? value : null;
    }

    /** 同步只拉取远端当前配置快照；冲突留给管理员处理，不覆写本地费率、映射或平台钱包。 */
    public Map<String, Object> synchronize(String resource) {
        scope.requireGlobalScope();
        if("assets".equals(resource)&&configurationSync!=null)return configurationSync.synchronize();
        String operation = switch (resource) { case "assets" -> "assets/query"; case "policies" -> "merchant-policies/query"; default -> throw invalid(); };
        // 未配置网关时不建立成功状态。
        ComputeMcagClient client = client();
        repository.synchronizeMerchantSnapshotWrite(config.getMerchantId(), config.getEnvironment(), resource);
        try {
            Map<String, Object> result = client.request(operation, Map.of());
            if (!(result.get("items") instanceof List<?>)) throw new BusinessException("server.compute.payment.invalidResponse");
            repository.synchronizeMerchantSnapshotWrite2(data.json(result), config.getMerchantId(), config.getEnvironment(), resource);
            return Map.of("resource", resource, "syncedAt", Instant.now().toString(), "status", "SUCCESS");
        } catch (BusinessException failure) {
            repository.synchronizeMerchantSnapshotWrite3("server.compute.merchant.syncFailed", config.getMerchantId(), config.getEnvironment(), resource);
            throw failure;
        }
    }

    /** 既有网关恢复发单和撤销入口也逐次使用本校验，不能凭旧菜单权限绕过资金二次验证。 */
    public void requireSuperTotp(String code) {
        requireSuper();
        if (code == null || !code.matches("[0-9]{6}") || !totp.verify(scope.currentAdminUid(), code, true))
            throw new BusinessException(403, "server.auth.totp.codeInvalid");
    }
    private void requireSuper() {
        scope.requireGlobalScope();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities().stream().noneMatch(a -> "super".equals(a.getAuthority())))
            throw new BusinessException(403, "server.compute.merchant.superRequired");
    }
    private ComputeMcagClient client() {
        var client = clients.getIfAvailable();
        if (!config.isEnabled() || client == null) throw new BusinessException(503, "server.compute.gateway.notConfigured");
        return client;
    }
    private static void optional(Map<String, Object> body, Map<String, String> input, String key) {
        String value = input.get(key); if (value != null && !value.isBlank()) body.put(key, text(value));
    }
    private static String text(String value) { if (value == null || value.isBlank() || value.length() > 256) throw invalid(); return value.trim(); }
    private static int integer(String value, int min, int max) {
        try { int parsed = Integer.parseInt(value); if (parsed < min || parsed > max) throw invalid(); return parsed; }
        catch (NumberFormatException failure) { throw invalid(); }
    }
    private static BusinessException invalid() { return new BusinessException("server.compute.request.invalid"); }
}
