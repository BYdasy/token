package org.token.model.request.system;

import lombok.Data;

@Data
public class AccountTotpRequiredRequest {
    private Integer uid;
    private Boolean required;
}
