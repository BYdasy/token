package org.token.controller.callback;

import org.token.service.compute.ComputePaymentGateway;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.token.model.response.Result;
import org.token.tools.response.ResultFactory;
import org.token.exception.BusinessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 支付入口；回调通过验签及持久化后才返回成功，失败保留第三方重试能力。 */
@org.token.custom.web.ComputeApi
@RestController
@RequestMapping("/api/compute")
@RequiredArgsConstructor
public class ComputeGatewayController {
    private final ComputePaymentGateway gateway;
    private final ResultFactory results;

    @PostMapping("/callbacks/deposit")
    public ResponseEntity<Result> depositCallback(HttpServletRequest request, @RequestHeader Map<String, String> headers) {
        return callback(request, headers, true);
    }

    @PostMapping("/callbacks/payout")
    public ResponseEntity<Result> payoutCallback(HttpServletRequest request, @RequestHeader Map<String, String> headers) {
        return callback(request, headers, false);
    }

    private ResponseEntity<Result> callback(HttpServletRequest request, Map<String, String> headers, boolean deposit) {
        try {
            if (!joseContentType(request.getContentType()))
                throw new BusinessException(415, "server.compute.request.invalid");
            byte[] body = request.getInputStream().readNBytes(131_073);
            if (body.length > 131_072) throw new BusinessException(413, "server.compute.request.invalid");
            if (deposit) gateway.acceptDepositCallback(body, headers, request.getRequestURI());
            else gateway.acceptPayoutCallback(body, headers, request.getRequestURI());
            return ResponseEntity.ok(results.success());
        } catch (BusinessException failure) {
            int status = failure.getCode() >= 400 && failure.getCode() <= 599 ? failure.getCode() : 422;
            return ResponseEntity.status(status).body(results.failure(status, failure.getMessage()));
        } catch (IOException | RuntimeException failure) {
            return ResponseEntity.status(503).body(results.failure(503, "server.compute.payment.unavailable"));
        }
    }

    private static boolean joseContentType(String value) {
        if (value == null) return false;
        try {
            MediaType type = MediaType.parseMediaType(value);
            return "application".equalsIgnoreCase(type.getType()) && "jose".equalsIgnoreCase(type.getSubtype());
        } catch (IllegalArgumentException invalid) { return false; }
    }
}
