package org.token.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TelegramBotRecoveryTest {
    private static final String TOKEN = "123456:TEST_only";
    private static final String BASE = "https://api.telegram.org/bot" + TOKEN + "/";
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final TelegramBotClient client = new TelegramBotClient(builder.build());

    private void expectIdentity() {
        server.expect(requestTo(BASE + "getMe")).andRespond(withSuccess(
                "{\"ok\":true,\"result\":{\"id\":123456,\"is_bot\":true,\"username\":\"test_bot\"}}", MediaType.APPLICATION_JSON));
    }

    private void expectNoWebhook() {
        server.expect(requestTo(BASE + "getWebhookInfo")).andRespond(withSuccess(
                "{\"ok\":true,\"result\":{\"url\":\"\"}}", MediaType.APPLICATION_JSON));
    }

    @Test
    void validatesIdentityAndWebhookOnceBeforeSuccessfulPolls() {
        expectIdentity(); expectNoWebhook();
        server.expect(requestTo(BASE + "getUpdates?offset=0&timeout=1"))
                .andRespond(withSuccess("{\"ok\":true,\"result\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "getUpdates?offset=4&timeout=1"))
                .andRespond(withSuccess("{\"ok\":true,\"result\":[]}", MediaType.APPLICATION_JSON));
        client.getUpdates(TOKEN, 0);
        client.getUpdates(TOKEN, 4);
        assertEquals("READY", client.pollingStatus(TOKEN).status());
        assertEquals("test_bot", client.pollingStatus(TOKEN).botUsername());
        assertNotNull(client.pollingStatus(TOKEN).lastSuccessAt());
        server.verify();
    }

    @Test
    void existingWebhookStopsPollingAndIsNeverDeleted() {
        expectIdentity();
        server.expect(requestTo(BASE + "getWebhookInfo")).andRespond(withSuccess(
                "{\"ok\":true,\"result\":{\"url\":\"https://elsewhere.example/hook\"}}", MediaType.APPLICATION_JSON));
        TelegramApiException failure = assertThrows(TelegramApiException.class, () -> client.getUpdates(TOKEN, 0));
        assertEquals("WEBHOOK", failure.status());
        assertThrows(TelegramApiException.class, () -> client.getUpdates(TOKEN, 0));
        server.verify();
    }

    @Test
    void invalidTokenIsSanitizedAndBackedOff() {
        server.expect(requestTo(BASE + "getMe")).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body("{\"ok\":false,\"error_code\":401}"));
        TelegramApiException failure = assertThrows(TelegramApiException.class, () -> client.inspectPolling(TOKEN));
        assertEquals("INVALID_TOKEN", failure.status());
        assertFalse(failure.toString().contains(TOKEN));
        assertNull(failure.getCause());
        assertThrows(TelegramApiException.class, () -> client.inspectPolling(TOKEN));
        server.verify();
    }

    @Test
    void rateLimitHonorsRetryAfterAndDoesNotImmediatelyRetry() {
        expectIdentity(); expectNoWebhook();
        server.expect(requestTo(BASE + "getUpdates?offset=0&timeout=1")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON).body("{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":120}}"));
        TelegramApiException failure = assertThrows(TelegramApiException.class, () -> client.getUpdates(TOKEN, 0));
        assertEquals("RATE_LIMITED", failure.status());
        assertEquals(120, failure.retryAfterSeconds());
        assertThrows(TelegramApiException.class, () -> client.getUpdates(TOKEN, 0));
        server.verify();
    }

    @Test
    void otherConsumerConflictDoesNotRetryContinuously() {
        expectIdentity(); expectNoWebhook();
        server.expect(requestTo(BASE + "getUpdates?offset=0&timeout=1")).andRespond(withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON).body("{\"ok\":false,\"error_code\":409}"));
        assertEquals("CONFLICT", assertThrows(TelegramApiException.class, () -> client.getUpdates(TOKEN, 0)).status());
        assertThrows(TelegramApiException.class, () -> client.getUpdates(TOKEN, 0));
        server.verify();
    }

    @Test
    void networkFailureHasSafeMessageAndManualRecoveryCanCheckAgain() {
        server.expect(requestTo(BASE + "getMe")).andRespond(withException(new java.io.IOException("secret URI " + TOKEN)));
        expectIdentity(); expectNoWebhook();
        TelegramApiException failure = assertThrows(TelegramApiException.class, () -> client.inspectPolling(TOKEN));
        assertEquals("NETWORK_ERROR", failure.status());
        assertFalse(failure.toString().contains(TOKEN));
        client.resetPolling(TOKEN);
        assertEquals("READY", client.inspectPolling(TOKEN).status());
        server.verify();
    }
}
