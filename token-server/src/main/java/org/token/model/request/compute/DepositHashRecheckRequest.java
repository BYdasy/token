package org.token.model.request.compute;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 充值补查只提供链上线索；商户身份和实际到账金额不接受客户端指定。 */
public record DepositHashRecheckRequest(
        @NotBlank(message="server.compute.request.invalid") @Pattern(regexp="[A-Za-z0-9_-]{1,128}",message="server.compute.request.invalid") String requestId,
        @NotBlank(message="server.compute.request.invalid") @Size(max=128,message="server.compute.request.invalid") String assetId,
        @NotBlank(message="server.compute.request.invalid") @Size(max=128,message="server.compute.request.invalid") String networkId,
        @NotBlank(message="server.compute.request.invalid") @Size(max=66,message="server.compute.request.invalid") String txHash,
        @NotBlank(message="server.compute.request.invalid") @Size(max=200,message="server.compute.request.invalid") String address,
        @Size(max=80,message="server.compute.request.invalid") String transferLocator,
        @NotBlank(message="server.compute.request.invalid") @Pattern(regexp="[0-9]{6}",message="server.compute.request.invalid") String code) {
}
