package org.token.model.request.system;

import lombok.Data;

@Data
public class SystemSettingsUpdateRequest {
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
    private Boolean cacheEnabled;
    private String cacheMode;
    private Boolean cacheSettingsEnabled;
    private Boolean cacheLanguagesEnabled;
    private Boolean cacheMenusEnabled;
    private Boolean cacheRolesEnabled;
    private Boolean cacheIpWhitelistEnabled;
}
