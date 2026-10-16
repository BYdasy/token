package org.token.service.compute;

import org.junit.jupiter.api.Tag;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.token.exception.BusinessException;
import org.token.config.compute.ComputeMcagProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 本机随机密钥和 HTTP 桩验证生产 JOSE 协议，不使用用户真实凭证或发送链上交易。 */
@Tag("regression")
class ComputeMcagClientTest {
    @TempDir Path directory;
    private static RSAKey merchantSigning,merchantEncryption,platformSigning,platformEncryption;
    ComputeMcagClient client;
    HttpServer server;
    String mode="OK";
    boolean requestVerified;
    final java.util.concurrent.atomic.AtomicLong clockNanos = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
    final java.util.concurrent.atomic.AtomicInteger requestCount = new java.util.concurrent.atomic.AtomicInteger();
    final CountDownLatch releaseBody=new CountDownLatch(1);
    final java.util.concurrent.atomic.AtomicReference<Runnable> clockReadHook = new java.util.concurrent.atomic.AtomicReference<>();
    // JWK 不在用例中修改，每个测试类仅生成一次；请求、服务端、目录及响应模式仍逐用例隔离。
    @BeforeAll static void createTestKeys() throws Exception {
        merchantSigning=new RSAKeyGenerator(2048).keyID("merchant-sig-test").generate();
        merchantEncryption=new RSAKeyGenerator(2048).keyID("merchant-enc-test").generate();
        platformSigning=new RSAKeyGenerator(2048).keyID("platform-sig-test").generate();
        platformEncryption=new RSAKeyGenerator(2048).keyID("platform-enc-test").generate();
    }
    @BeforeEach void setup() throws Exception {
        var config=new ComputeMcagProperties();config.setEnabled(true);config.setMerchantId("mch_test");config.setCredentialId("cred_test");
        config.setSigningKeyFile(write("sig",merchantSigning));config.setDecryptionKeyFile(write("enc",merchantEncryption));
        config.setPlatformSigningKeyFile(write("psig",platformSigning.toPublicJWK()));config.setPlatformEncryptionKeyFile(write("penc",platformEncryption.toPublicJWK()));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/assets/query", exchange->{
            try {
                requestCount.incrementAndGet();
                JWEObject request=JWEObject.parse(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.US_ASCII));
                assertEquals(JWEAlgorithm.RSA_OAEP_256,request.getHeader().getAlgorithm());assertEquals(EncryptionMethod.A256GCM,request.getHeader().getEncryptionMethod());
                request.decrypt(new RSADecrypter(platformEncryption)); SignedJWT signed=SignedJWT.parse(request.getPayload().toString());
                assertTrue(signed.verify(new RSASSAVerifier(merchantSigning.toPublicJWK())));
                var inbound=signed.getJWTClaimsSet(); assertEquals("/v1/assets/query",inbound.getStringClaim("http_path"));assertEquals("REQUEST",inbound.getStringClaim("message_type"));
                assertEquals("mch_test",inbound.getIssuer());assertEquals(List.of("mcag-api"),inbound.getAudience());requestVerified=true;
                if (mode.startsWith("RATE_LIMIT")) {
                    byte[] limited = "RATE_LIMIT_OVERSIZED".equals(mode) ? new byte[200000] : "{\"error\":{\"code\":\"QUOTE_STALE\"}}".getBytes(StandardCharsets.US_ASCII);
                    exchange.getResponseHeaders().add("Content-Type", "text/plain");
                    exchange.getResponseHeaders().add("Retry-After", "75");
                    exchange.sendResponseHeaders(429, limited.length);
                    if ("RATE_LIMIT_SLOW".equals(mode)) { exchange.getResponseBody().flush(); releaseBody.await(10,TimeUnit.SECONDS); }
                    exchange.getResponseBody().write(limited); return;
                }
                boolean error=mode.startsWith("ERROR_");
                String errorCode=switch(mode){case "ERROR_IDEMPOTENCY_CONFLICT"->"IDEMPOTENCY_CONFLICT";case "ERROR_QUOTE_NOT_FOUND"->"QUOTE_NOT_FOUND";case "ERROR_INVALID_CODE"->"invalid error code";default->"QUOTE_STALE";};
                Map<String,Object> payload=error?Map.of("error",Map.of("code",errorCode,"message","第三方内部详情不得进入用户响应")):Map.of("items",List.of());
                var claims=response("RESPONSE","/v1/assets/query",inbound.getJWTID(),payload);
                boolean plain="PLAIN_AUTH_ERROR".equals(mode);
                byte[] body="OVERSIZED".equals(mode)?new byte[131073]:plain?"{\"error\":{\"code\":\"QUOTE_STALE\"}}".getBytes(StandardCharsets.US_ASCII):seal(claims).getBytes(StandardCharsets.US_ASCII);
                int http=plain?403:!error||"ERROR_HTTP_200".equals(mode)?200:errorCode.contains("NOT_FOUND")?404:errorCode.contains("CONFLICT")?409:422;
                exchange.getResponseHeaders().add("Content-Type",plain?"application/json":"application/jose");exchange.sendResponseHeaders(http,body.length);
                if("TRUNCATED_BODY".equals(mode)){exchange.getResponseBody().write(body,0,10);return;}
                if("SLOW".equals(mode)) {exchange.getResponseBody().flush();releaseBody.await(25,TimeUnit.SECONDS);}
                exchange.getResponseBody().write(body);
            } catch(Exception exception) { try {exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){} }
            finally { exchange.close(); }
        });server.createContext("/v1/payouts/create", exchange -> { requestCount.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });server.start();config.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());client=new ComputeMcagClient(config,()-> {
            long observed=clockNanos.get();
            var hook=clockReadHook.getAndSet(null);
            if(hook!=null)hook.run();
            return observed;
        });
    }
    @AfterEach void stop() {releaseBody.countDown();if(server!=null)server.stop(0);}
    String write(String name,RSAKey key)throws Exception {var path=directory.resolve(name+".jwk");Files.writeString(path,key.toJSONString());return path.toString();}
    JWTClaimsSet response(String type,String path,String reply,Map<String,Object> body) {
        return new JWTClaimsSet.Builder().issuer("mcag").audience("AUD".equals(mode)?"other":"mch_test")
                .claim("merchant_id","mch_test").claim("environment","LIVE").claim("credential_id","cred_test").claim("kid",merchantSigning.getKeyID())
                .jwtID(UUID.randomUUID().toString()).issueTime(Date.from(Instant.now().minusSeconds("EXPIRED".equals(mode)?400:0)))
                .expirationTime(Date.from(Instant.now().plusSeconds("EXPIRED".equals(mode)?-100:120)))
                .claim("message_type",type).claim("http_path",Set.of("PATH","ERROR_WRONG_PATH").contains(mode)?"/wrong":path)
                .claim("in_reply_to","REPLY".equals(mode)?"wrong":reply).claim("body",body).build();
    }
    String seal(JWTClaimsSet claims)throws Exception {
        var signed=new SignedJWT(new JWSHeader.Builder("ALGORITHM".equals(mode)?JWSAlgorithm.RS512:JWSAlgorithm.RS256).keyID(platformSigning.getKeyID()).build(),claims);
        signed.sign(new RSASSASigner(platformSigning));
        var encrypted=new JWEObject(new JWEHeader.Builder(JWEAlgorithm.RSA_OAEP_256,EncryptionMethod.A256GCM).keyID(merchantEncryption.getKeyID()).contentType("JWT").build(),new Payload(signed.serialize()));
        encrypted.encrypt(new RSAEncrypter(merchantEncryption.toPublicJWK()));return encrypted.serialize();
    }
    @Test void roundTripVerifiesRequestAndResponse() {assertEquals(List.of(),client.request("assets/query",Map.of()).get("items"));assertTrue(requestVerified);}
    @Test void wrongAudiencePathReplyAlgorithmAndExpiredResponseAreRejected() {
        for(String invalid:List.of("AUD","PATH","REPLY","ALGORITHM","EXPIRED")) {mode=invalid;assertThrows(BusinessException.class,()->client.request("assets/query",Map.of()),invalid);}
    }
    @Test void callbackBindsActualPathAndEventId() throws Exception {
        var event=Map.<String,Object>of("event_id","evt_test");
        var compact=seal(response("EVENT","/api/compute/callbacks/deposit","evt_test",event)).getBytes(StandardCharsets.US_ASCII);
        assertEquals(event,client.event(compact,"/api/compute/callbacks/deposit"));
        assertThrows(BusinessException.class,()->client.event(compact,"/api/compute/callbacks/payout"));
        mode="REPLY";var bad=seal(response("EVENT","/api/compute/callbacks/deposit","evt_test",event)).getBytes(StandardCharsets.US_ASCII);
        assertThrows(BusinessException.class,()->client.event(bad,"/api/compute/callbacks/deposit"));
    }
    @Test void tamperedEnvelopeIsRejected() throws Exception {
        String compact=seal(response("EVENT","/callback","evt_test",Map.of("event_id","evt_test")));
        int position=compact.lastIndexOf('.')+2;char replacement=compact.charAt(position)=='A'?'B':'A';
        byte[] tampered=(compact.substring(0,position)+replacement+compact.substring(position+1)).getBytes(StandardCharsets.US_ASCII);
        assertThrows(BusinessException.class,()->client.event(tampered,"/callback"));
    }
    @Test void oversizedResponseIsRejected() {mode="OVERSIZED";assertThrows(BusinessException.class,()->client.request("assets/query",Map.of()));}
    @Test void verifiedCryptoBusinessErrorsPreserveOnlySafeErrorCode() {
        for(String code:List.of("QUOTE_STALE","IDEMPOTENCY_CONFLICT","QUOTE_NOT_FOUND")) {
            mode="ERROR_"+code;
            var failure=assertThrows(ComputeMcagRejectedException.class,()->client.request("assets/query",Map.of()),code);
            assertEquals(code,failure.getGatewayCode());assertEquals(502,failure.getCode());
            assertEquals("server.compute.payment.rejected",failure.getMessage());
        }
    }
    @Test void encryptedErrorBodyCannotBeMistakenForSuccessEvenWithHttp200() {
        mode="ERROR_HTTP_200";
        assertEquals("QUOTE_STALE",assertThrows(ComputeMcagRejectedException.class,()->client.request("assets/query",Map.of())).getGatewayCode());
    }
    @Test void unverifiedOrMalformedErrorMustNotBecomeConclusiveBusinessRejection() {
        for(String invalid:List.of("ERROR_WRONG_PATH","ERROR_INVALID_CODE")) {
            mode=invalid;var failure=assertThrows(BusinessException.class,()->client.request("assets/query",Map.of()),invalid);
            assertFalse(failure instanceof ComputeMcagRejectedException,invalid);
            assertEquals("server.compute.payment.invalidResponse",failure.getMessage());
        }
    }
    @Test void unsignedAccessDenialIsDiagnosticAndNeverConclusiveBusinessRejection() {
        // 未签名正文故意伪造 QUOTE_STALE，不能让提款清除报价或释放冻结资金。
        mode="PLAIN_AUTH_ERROR";
        var failure=assertThrows(BusinessException.class,()->client.request("assets/query",Map.of()));
        assertFalse(failure instanceof ComputeMcagRejectedException);
        assertEquals(502,failure.getCode());
        assertEquals("server.compute.payment.accessDenied",failure.getMessage());
        assertEquals(Map.of("gatewayHttpStatus",403),failure.getData());
        var diagnostic=assertInstanceOf(ComputeMcagRequestException.class,failure);
        assertEquals("RESPONSE_HEADERS",diagnostic.requestPhase());assertEquals(403,diagnostic.httpStatus());
    }
    @Test void invalidSignedResponseIdentifiesVerificationPhaseWithoutTrustingItsBody() {
        mode="ERROR_WRONG_PATH";
        var failure=assertThrows(ComputeMcagRequestException.class,()->client.request("assets/query",Map.of()));
        assertEquals("server.compute.payment.invalidResponse",failure.getMessage());
        assertEquals("RESPONSE_JOSE",failure.requestPhase());assertEquals(422,failure.httpStatus());
        assertEquals("",failure.getData());
    }
    @Test void connectionFailureIsUnavailableInsteadOfAnInvalidOrderResponse() {
        server.stop(0);
        var failure=assertThrows(ComputeMcagRequestException.class,()->client.request("assets/query",Map.of()));
        assertEquals("server.compute.payment.unavailable",failure.getMessage());
        assertEquals("HTTP_TRANSPORT",failure.requestPhase());assertEquals(0,failure.httpStatus());
        assertEquals(ComputeMcagRequestException.TransportFailure.CONNECT_FAILED,failure.transportFailure());
        assertTrue(failure.elapsedMillis()>=0);
        assertEquals("",failure.getData());
    }
    @Test void interruptedResponseBodyKeepsReceivedStatusAndDoesNotBecomeTrustedRejection() {
        mode="TRUNCATED_BODY";
        var failure=assertThrows(ComputeMcagRequestException.class,()->client.request("assets/query",Map.of()));
        assertEquals(200,failure.httpStatus());assertEquals("RESPONSE_BODY",failure.requestPhase());
        assertTrue(Set.of(ComputeMcagRequestException.TransportFailure.IO_FAILURE,
                ComputeMcagRequestException.TransportFailure.CONNECTION_CLOSED).contains(failure.transportFailure()));
        assertEquals("server.compute.payment.unavailable",failure.getMessage());assertEquals("",failure.getData());
        assertEquals(1,requestCount.get(),"诊断不自行重发请求");
    }
    @Test void rateLimitIsTransportOnlyAndCooldownPreventsQueriesAndWritesWithoutSleeping() {
        mode="RATE_LIMIT";
        var limited=assertThrows(BusinessException.class,()->client.request("assets/query",Map.of()));
        assertFalse(limited instanceof ComputeMcagRejectedException);
        assertEquals(503,limited.getCode());
        assertEquals(Map.of("gatewayHttpStatus",429,"retryAfterSeconds",60),limited.getData());
        assertEquals(1,requestCount.get());
        mode="OK";
        assertThrows(BusinessException.class,()->client.request("assets/query",Map.of()));
        assertThrows(BusinessException.class,()->client.request("payouts/create",Map.of("merchant_order_id","same-order")));
        assertEquals(1,requestCount.get());
        clockNanos.addAndGet(TimeUnit.SECONDS.toNanos(61));
        assertEquals(List.of(),client.request("assets/query",Map.of()).get("items"));
        assertEquals(2,requestCount.get());
    }
    @Test void rateLimitCoolingStartsBeforeAStalledResponseBodyCompletes() {
        mode="RATE_LIMIT_SLOW";
        assertTimeoutPreemptively(Duration.ofSeconds(4),()-> {
            var failure=assertThrows(BusinessException.class,()->client.request("assets/query",Map.of()));
            assertEquals(503,failure.getCode());assertEquals("server.compute.payment.rateLimited",failure.getMessage());
        });
        assertThrows(BusinessException.class,()->client.request("payouts/create",Map.of("merchant_order_id","same-order")));
        assertEquals(1,requestCount.get());
    }
    @Test void rateLimitCoolingDoesNotDependOnResponseBodySize() {
        mode="RATE_LIMIT_OVERSIZED";
        var failure=assertThrows(BusinessException.class,()->client.request("assets/query",Map.of()));
        assertEquals(503,failure.getCode());assertEquals("server.compute.payment.rateLimited",failure.getMessage());
        assertThrows(BusinessException.class,()->client.request("assets/query",Map.of()));
        assertEquals(1,requestCount.get());
    }
    @Test void expiringCooldownCannotClearAConcurrentNewCooldown() throws Exception {
        mode="RATE_LIMIT";
        assertThrows(BusinessException.class,()->client.request("assets/query",Map.of()));
        clockNanos.addAndGet(TimeUnit.SECONDS.toNanos(61));
        var observedExpired=new CountDownLatch(1);var resumeExpired=new CountDownLatch(1);
        clockReadHook.set(()-> {
            observedExpired.countDown();
            try { if(!resumeExpired.await(5,TimeUnit.SECONDS))throw new AssertionError("旧冷却检查未恢复"); }
            catch(InterruptedException failure) { Thread.currentThread().interrupt();throw new AssertionError(failure); }
        });
        var executor=Executors.newSingleThreadExecutor();
        try {
            var waiting=executor.submit(()->assertThrows(BusinessException.class,()->client.request("payouts/create",Map.of("merchant_order_id","same-order"))));
            assertTrue(observedExpired.await(5,TimeUnit.SECONDS));
            assertEquals(503,assertThrows(BusinessException.class,()->client.request("assets/query",Map.of())).getCode());
            resumeExpired.countDown();
            assertEquals("server.compute.payment.rateLimited",waiting.get(5,TimeUnit.SECONDS).getMessage());
            assertEquals(2,requestCount.get(),"过期观察者不能穿过另一请求的新冷却发送出款");
        } finally { resumeExpired.countDown();executor.shutdownNow(); }
    }
    @Test void retryAfterHeaderHasBoundedDurationAndInvalidFallback() {
        var now=Instant.parse("2026-09-21T00:00:00Z");
        assertEquals(1,ComputeMcagClient.retryAfterSeconds("0",now));
        assertEquals(60,ComputeMcagClient.retryAfterSeconds("9999999999",now));
        assertEquals(5,ComputeMcagClient.retryAfterSeconds("invalid",now));
        assertEquals(30,ComputeMcagClient.retryAfterSeconds(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(30).atZone(ZoneOffset.UTC)),now));
    }
    @Test void oversizedOutgoingEnvelopeIsRejectedBeforeAnyRequest() {
        var failure=assertThrows(BusinessException.class,()->client.request("assets/query",Map.of("extra","x".repeat(140000))));
        assertEquals(400,failure.getCode()); assertEquals(0,requestCount.get());
    }
    @Test void stalledBodyHasTotalTimeout() {
        mode="SLOW";
        assertTimeout(Duration.ofSeconds(20),()-> {
            var failure=assertThrows(ComputeMcagRequestException.class,()->client.request("assets/query",Map.of()));
            assertEquals(504,failure.getCode());assertEquals(200,failure.httpStatus());
            assertEquals("RESPONSE_BODY",failure.requestPhase());
            assertEquals(ComputeMcagRequestException.TransportFailure.BODY_TIMEOUT,failure.transportFailure());
            assertTrue(failure.elapsedMillis()>=0);assertEquals("",failure.getData());
        });
    }
}
