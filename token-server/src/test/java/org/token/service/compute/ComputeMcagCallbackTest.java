package org.token.service.compute;

import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.exception.BusinessException;
import org.token.controller.callback.ComputeGatewayController;
import org.token.service.compute.ComputeAgentScope;
import org.token.service.compute.ComputePaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用独立 MVC 和支付桩验证回调确认边界，不启动服务、访问数据库或发送支付请求。 */
class ComputeMcagCallbackTest {
    private final ComputePaymentGateway gateway = mock(ComputePaymentGateway.class);
    private final ResultFactory results = mock(ResultFactory.class);
    private MockMvc mvc;
    private static final byte[] BODY = "opaque-test-jose-envelope".getBytes(StandardCharsets.US_ASCII);

    @BeforeEach void setup() {
        when(results.success()).thenReturn(new Result(0, null, "成功"));
        when(results.failure(anyInt(), anyString())).thenAnswer(call -> new Result(call.getArgument(0), null, call.getArgument(1)));
        mvc = MockMvcBuilders.standaloneSetup(new ComputeGatewayController(gateway, results)).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"deposit", "payout"})
    void successfulCallbackPassesOriginalBodyAndActualUriBeforeAcknowledgement(String kind) throws Exception {
        String path = "/api/compute/callbacks/" + kind;
        mvc.perform(post(path).contentType("application/jose; charset=US-ASCII")
                        .header("X-MCAG-Delivery-Id", "delivery-test").content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        var calls = inOrder(gateway, results);
        if (kind.startsWith("deposit")) calls.verify(gateway).acceptDepositCallback(eq(BODY), anyMap(), eq(path));
        else calls.verify(gateway).acceptPayoutCallback(eq(BODY), anyMap(), eq(path));
        calls.verify(results).success();
        verify(gateway, never()).acceptDepositCallback(any(), anyMap());
        verify(gateway, never()).acceptPayoutCallback(any(), anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"deposit-exception", "payout-exception", "event", "generic", "deposit/extra"})
    void removedOrGenericCallbackRoutesAreNotMapped(String kind) throws Exception {
        mvc.perform(post("/api/compute/callbacks/" + kind).contentType("application/jose").content(BODY))
                .andExpect(status().isNotFound());
        verifyNoInteractions(gateway);
        verify(results, never()).success();
    }

    @ParameterizedTest
    @ValueSource(strings = {"deposit", "payout"})
    void signatureOrAuthoritativeLookupFailureNeverAcknowledgesDelivery(String kind) throws Exception {
        String path = "/api/compute/callbacks/" + kind;
        var failure = new BusinessException(502, "server.compute.payment.invalidResponse");
        if (kind.equals("deposit")) doThrow(failure).when(gateway).acceptDepositCallback(any(), anyMap(), eq(path));
        else doThrow(failure).when(gateway).acceptPayoutCallback(any(), anyMap(), eq(path));
        mvc.perform(post(path).contentType("application/jose").content(BODY))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value(502));
        verify(results, never()).success();
    }

    @Test void authoritativeLookupTimeoutRemainsRetryable() throws Exception {
        doThrow(new BusinessException(504, "server.compute.payment.timeout")).when(gateway).acceptDepositCallback(any(), anyMap(), anyString());
        mvc.perform(post("/api/compute/callbacks/deposit").contentType("application/jose").content(BODY))
                .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value(504));
        verify(results, never()).success();
    }

    @Test void ordinaryBusinessCodeStillUsesFailureHttpStatus() throws Exception {
        doThrow(new BusinessException("server.compute.request.invalid")).when(gateway).acceptPayoutCallback(any(), anyMap(), anyString());
        mvc.perform(post("/api/compute/callbacks/payout").contentType("application/jose").content(BODY))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value(422));
        verify(results, never()).success();
    }

    @Test void persistenceFailureDoesNotLeakDetailsOrAcknowledgeDelivery() throws Exception {
        doThrow(new IllegalStateException("数据库内部错误，不得返回给调用方")).when(gateway).acceptPayoutCallback(any(), anyMap(), anyString());
        mvc.perform(post("/api/compute/callbacks/payout").contentType("application/jose").content(BODY))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("server.compute.payment.unavailable"));
        verify(results, never()).success();
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "application/json", "application/jose+json", "application/josex"})
    void wrongContentTypeIsRejectedBeforeGateway(String contentType) throws Exception {
        mvc.perform(post("/api/compute/callbacks/deposit").contentType(contentType).content(BODY))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(gateway);
        verify(results, never()).success();
    }

    @Test void missingContentTypeIsRejectedBeforeGateway() throws Exception {
        mvc.perform(post("/api/compute/callbacks/payout").content(BODY)).andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(gateway);
    }

    @Test void oversizedBodyIsRejectedBeforeGateway() throws Exception {
        mvc.perform(post("/api/compute/callbacks/payout").contentType("application/jose").content(new byte[131_073]))
                .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(gateway);
        verify(results, never()).success();
    }

    @Test void maximumBodySizeCanReachEnvelopeVerification() throws Exception {
        byte[] body = new byte[131_072];
        mvc.perform(post("/api/compute/callbacks/deposit").contentType("application/jose").content(body))
                .andExpect(status().isOk());
        verify(gateway).acceptDepositCallback(eq(body), anyMap(), eq("/api/compute/callbacks/deposit"));
    }
}
