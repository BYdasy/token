package org.token.model.request.compute;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record McagSyncRequest(
        @NotBlank(message = "server.compute.request.invalid") @Size(max = 128, message = "server.compute.request.invalid") String depositId,
        @NotBlank(message = "server.compute.request.invalid") @Size(max = 16, message = "server.compute.request.invalid") String currency,
        @NotBlank(message = "server.compute.request.invalid") @Size(max = 32, message = "server.compute.request.invalid") String network,
        @NotBlank(message = "server.compute.request.invalid") @Size(max = 16, message = "server.compute.request.invalid") String walletType) {
}
