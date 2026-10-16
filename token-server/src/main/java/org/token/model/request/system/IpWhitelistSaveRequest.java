package org.token.model.request.system;

import lombok.Data;

@Data
public class IpWhitelistSaveRequest {
    private Long id;
    private String ipPattern;
    private String description;
    private Integer enabled;
}
