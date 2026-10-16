package org.token.model.response;

import lombok.Builder;
import lombok.Data;

/** 前端可编辑的基础设置。 */
@Data
@Builder
public class SystemSettingsView {
    private String siteName;
    private String siteIcon;
    private String siteLogo;
    private String userSiteName;
    private String userSiteLogo;
    private String userSiteIcon;
    private String userHeroImage;
    private String userShareEntryUrl;
    private String userShareTitle;
    private String userShareDescription;
    private String userShareImage;
    private String timeZone;
    private String serverDisplayTimeZone;
    private String userTimeZoneMode;
    private String dateTimeFormat;
    private CacheStatusView cache;
}
