package org.token.model.request.compute;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.token.exception.BusinessException;

/** 后台新增用户使用现有账号字段与数字权限，不接收初始余额或会话参数。 */
public record ComputeMemberCreateRequest(
        @NotBlank(message = "server.compute.account.invalid") @Size(min=3,max = 40, message = "server.compute.account.invalid") String username,
        @Email(message = "server.compute.account.invalid") @Size(max = 120, message = "server.compute.account.invalid") String email,
        @Size(max = 24, message = "server.compute.account.invalid") String phone,
        @NotBlank(message = "server.compute.v8.passwordRule") @Size(min = 6, max = 64, message = "server.compute.v8.passwordRule") String password,
        @Size(max = 32, message = "server.compute.invite.invalid") String inviteCode,
        Boolean enabled,
        Boolean isTest) {
    public ComputeMemberCreateRequest(String username, String email, String phone, String password, String inviteCode, Boolean enabled) {
        this(username, email, phone, password, inviteCode, enabled, false);
    }
    @JsonAnySetter
    public void rejectUnknown(String key, Object value) { throw new BusinessException("server.compute.invalidInput"); }
}
