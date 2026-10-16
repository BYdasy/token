package org.token.model.request.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AdminLoginRequest {
    @NotBlank(message = "server.username.required")
    private String username;
    @NotBlank(message = "server.password.required")
    private String password;
    /** 账号要求验证器登录时填写的六位动态验证码。 */
    private String otpCode;
}
