package com.mcag.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcag.domain.Tenant;
import com.mcag.infrastructure.Db;
import com.mcag.infrastructure.security.JoseCodec;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Component
public class MerchantAuthFilter extends OncePerRequestFilter {
    public static final String BODY = "mcag.body";
    public static final Map<String, String> SCOPES = Map.ofEntries(
            Map.entry("deposit-orders/query", "deposits:read"), Map.entry("collection-batches/query", "deposits:read"), Map.entry("sweeps/query", "deposits:read"),
            Map.entry("merchant-wallets/query", "balances:read"), Map.entry("merchant-wallets/refresh", "balances:read"),
            Map.entry("collection-destinations/query", "balances:read"), Map.entry("collection-destinations/refresh", "balances:read"),
            Map.entry("merchant-policies/query", "assets:read"), Map.entry("service-fee/account/query", "balances:read"),
            Map.entry("service-fee/ledger/query", "balances:read"), Map.entry("service-fee/addresses/query", "balances:read"), Map.entry("service-fee/addresses/refresh", "balances:read"),
            Map.entry("resource-orders/query", "events:read"), Map.entry("notifications/query", "events:read"),
            Map.entry("notifications/attempts/query", "events:read"),
            Map.entry("customer-addresses/get-or-create", "addresses:write"), Map.entry("customer-addresses/query", "balances:read"), Map.entry("payouts/create", "payouts:create"),
            Map.entry("payouts/query", "payouts:read"), Map.entry("deposit-rechecks/create", "rechecks:create"),
            Map.entry("merchant-bills/query", "balances:read"),
            Map.entry("assets/query", "assets:read"), Map.entry("exchange-rates/query", "assets:read"),
            Map.entry("payouts/quote", "payouts:quote"),
            Map.entry("deposit-rechecks/query", "rechecks:read"), Map.entry("events/query", "events:read"));
    private final Db db;
    private final JoseCodec jose;
    private final ObjectMapper json;
    private final StringRedisTemplate redis;

    public MerchantAuthFilter(Db db, JoseCodec jose, ObjectMapper json, StringRedisTemplate redis) {
        this.db = db;
        this.jose = jose;
        this.json = json;
        this.redis = redis;
    }

    static void validateClaims(JWTClaimsSet c, String path, String merchant, String environment, String credential, String kid, Instant now) throws Exception {
        if (c.getIssueTime() == null || c.getExpirationTime() == null || c.getJWTID() == null || !c.getJWTID().matches("[A-Za-z0-9_-]{16,128}"))
            throw new IllegalArgumentException("Missing temporal or JTI claims");
        Instant iat = c.getIssueTime().toInstant(), exp = c.getExpirationTime().toInstant();
        if (iat.isAfter(now.plusSeconds(30)) || iat.isBefore(now.minusSeconds(330)) || !exp.isAfter(now) || exp.isAfter(iat.plusSeconds(300)) || !exp.isAfter(iat))
            throw new IllegalArgumentException("Invalid lifetime");
        if (!merchant.equals(c.getIssuer()) || !c.getAudience().equals(List.of("mcag-api")) || !path.equals(c.getStringClaim("http_path")) || !"REQUEST".equals(c.getStringClaim("message_type")) || !kid.equals(c.getStringClaim("kid")) || !credential.equals(c.getStringClaim("credential_id")) || !environment.equals(c.getStringClaim("environment")) || c.getJSONObjectClaim("body") == null)
            throw new IllegalArgumentException("Claims binding failed");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        try {
            String operation = request.getRequestURI().substring(4), scope = SCOPES.get(operation);
            if (!"POST".equals(request.getMethod()) || scope == null) {
                reject(response, 404, "NOT_FOUND");
                return;
            }
            byte[] bytes = request.getInputStream().readNBytes(131073);
            if (bytes.length > 131072) {
                reject(response, 413, "REQUEST_TOO_LARGE");
                return;
            }
            MerchantIdentity identity;
            if (!jose.configured()) {
                reject(response, 503, "JOSE_NOT_CONFIGURED");
                return;
            }
            SignedJWT jwt = jose.decrypt(new String(bytes, StandardCharsets.UTF_8));
            JWTClaimsSet untrusted = jwt.getJWTClaimsSet();
            String merchant = untrusted.getStringClaim("merchant_id"), environment = untrusted.getStringClaim("environment"), credential = untrusted.getStringClaim("credential_id");
            if (merchant == null || credential == null || !"LIVE".equals(environment)) {
                reject(response, 401, "UNAUTHENTICATED");
                return;
            }
            var row = db.one("SELECT * FROM merchant_credentials WHERE credential_id=#{p.id} AND merchant_id=#{p.merchant} AND environment=#{p.env} AND status='ACTIVE'", Map.of("id", credential, "merchant", merchant, "env", environment));
            if (row == null) {
                reject(response, 401, "UNAUTHENTICATED");
                return;
            }
            JWTClaimsSet c = jose.verify(jwt, (String) row.get("sign_public_jwk"), (String) row.get("kid"));
            validateClaims(c, request.getRequestURI(), merchant, environment, credential, (String) row.get("kid"), Instant.now());
            Set<String> scopes = new HashSet<>(Arrays.asList(Objects.toString(row.get("scopes"), "").split("[,\\s]+")));
            if (!scopes.contains(scope)) {
                reject(response, 403, "SCOPE_DENIED");
                return;
            }
            // Authentication fails closed when replay storage is unavailable. Existing financial jobs are unaffected.
            String key = "mcag:replay:" + environment + ":" + merchant + ":REQUEST:" + c.getJWTID();
            long ttl = Math.max(1, c.getExpirationTime().toInstant().getEpochSecond() - Instant.now().getEpochSecond() + 30);
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(ttl)))) {
                reject(response, 409, "REPLAY_DETECTED");
                return;
            }
            String rate = "mcag:rate:" + environment + ":" + merchant + ":" + (Instant.now().getEpochSecond() / 60);
            Long count = redis.opsForValue().increment(rate);
            redis.expire(rate, Duration.ofSeconds(120));
            if (count != null && count > 300) {
                reject(response, 429, "RATE_LIMITED");
                return;
            }
            identity = new MerchantIdentity(new Tenant(merchant, environment), credential, (String) row.get("kid"), (String) row.get("encrypt_public_jwk"), c.getJWTID(), scopes);
            request.setAttribute(BODY, c.getJSONObjectClaim("body"));
            var merchantRecord = db.one("SELECT status FROM merchants WHERE merchant_id=#{p.id} AND environment=#{p.env}", Map.of("id", identity.tenant().merchantId(), "env", identity.tenant().environment()));
            if (merchantRecord == null || !"ACTIVE".equals(merchantRecord.get("status"))) {
                reject(response, 403, "MERCHANT_DISABLED");
                return;
            }
            var auth = new UsernamePasswordAuthenticationToken(identity, null, identity.scopes().stream().map(SimpleGrantedAuthority::new).toList());
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
            reject(response, 503, "REPLAY_STORE_UNAVAILABLE");
            return;
        } catch (Exception e) {
            reject(response, 401, "INVALID_ENVELOPE");
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        json.writeValue(response.getOutputStream(), Map.of("error", Map.of("code", code, "message", "请求认证未通过")));
    }
}
