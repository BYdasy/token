package org.token.model.request.compute;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 用户端账户输入；所有校验消息沿用服务端动态多语言。 */
public final class ComputeAuthRequests {
    private ComputeAuthRequests() { }

    public record Register(
            @Size(max = 40, message = "server.compute.account.invalid") String username,
            @Email(message = "server.compute.account.invalid") @Size(max = 120, message = "server.compute.account.invalid") String email,
            @Size(max = 24, message = "server.compute.account.invalid") String phone,
            @NotBlank(message = "server.compute.v8.passwordRule") @Size(min = 6, max = 64, message = "server.compute.v8.passwordRule") String password,
            @Size(max = 32, message = "server.compute.invite.invalid") String inviteCode) { }

    public record Login(
            @NotBlank(message = "server.compute.account.invalid") @Size(max = 120, message = "server.compute.account.invalid") String account,
            @NotBlank(message = "server.compute.password.sixCharacters") @Size(max = 64, message = "server.compute.password.sixCharacters") String password) { }

    public record Recovery(
            @Pattern(regexp = "username|email|phone", message = "server.compute.account.invalid") @NotBlank(message = "server.compute.account.invalid") String method,
            @NotBlank(message = "server.compute.account.invalid") @Size(max = 120, message = "server.compute.account.invalid") String account) { }

    public record Reset(
            @NotBlank(message = "server.compute.recovery.invalid") @Size(max = 128, message = "server.compute.recovery.invalid") String token,
            @NotBlank(message = "server.compute.v8.passwordRule") @Size(min = 6, max = 64, message = "server.compute.v8.passwordRule") String newPassword) { }

    public record Password(
            @NotBlank(message = "server.compute.password.sixCharacters") @Size(max = 64, message = "server.compute.password.sixCharacters") String oldPassword,
            @NotBlank(message = "server.compute.v8.passwordRule") @Size(min = 6, max = 64, message = "server.compute.v8.passwordRule") String newPassword) { }

    public record Profile(
            @Size(max = 40, message = "server.compute.account.invalid") String username,
            @Email(message = "server.compute.account.invalid") @Size(max = 120, message = "server.compute.account.invalid") String email,
            @Size(max = 24, message = "server.compute.account.invalid") String phone) { }
}
