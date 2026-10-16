package org.token.model.request.compute;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GatewayReconciliationRequest(
        @NotBlank(message = "server.compute.request.invalid") @Size(max = 128, message = "server.compute.request.invalid") String reference,
        @NotBlank(message = "server.compute.request.invalid") @Size(max = 24, message = "server.compute.request.invalid") String kind,
        @NotBlank(message = "server.compute.request.invalid") @Size(max = 500, message = "server.compute.request.invalid") String remark,
        @NotBlank(message = "server.compute.request.invalid") @Size(max = 6, message = "server.compute.request.invalid") String code) {
}
