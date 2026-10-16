package org.token.service;

import org.token.model.entity.IpWhitelistEntry;
import org.token.model.request.system.IpWhitelistSaveRequest;

import java.util.List;

public interface IpWhitelistService {
    List<IpWhitelistEntry> listAll();
    IpWhitelistEntry create(IpWhitelistSaveRequest request, Integer operatorUid);
    IpWhitelistEntry update(IpWhitelistSaveRequest request, String currentClientIp, boolean directLocalRequest);
    void delete(Long id, String currentClientIp, boolean directLocalRequest);
    boolean isEnabled();
    void setEnabled(boolean enabled, Integer operatorUid, String currentClientIp, boolean directLocalRequest);
    boolean isAllowed(String ip);
    long activeRuleCount();
    void invalidateCache();
}
