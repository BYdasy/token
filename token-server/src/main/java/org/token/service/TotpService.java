package org.token.service;

import org.token.model.response.TotpSetupView;
import org.token.model.response.TotpStatusView;

public interface TotpService {
    TotpStatusView status(Integer uid);
    TotpSetupView setup(Integer uid, String username);
    void confirm(Integer uid, String code);
    boolean verify(Integer uid, String code, boolean preventReplay);
    void disable(Integer uid, String code);
    void setRequired(Integer uid, boolean required);
    void reset(Integer uid);
}
