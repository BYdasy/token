package org.token.model.request.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TotpCodeRequest {
    @NotBlank(message = "server.auth.totp.codeRequired")
    private String code;
}
