package org.token.model.response;

/** 绑定验证器所需数据；二维码使用 data URL，前端可直接放入 img src。 */
public record TotpSetupView(
        String secret,
        String otpauthUri,
        String qrCodeDataUrl
) {
}
