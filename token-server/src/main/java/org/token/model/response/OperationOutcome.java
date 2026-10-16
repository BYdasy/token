package org.token.model.response;

/** 业务操作结果；消息保留词条键，由 HTTP 适配层按当前请求语言生成响应。 */
public record OperationOutcome(Integer code, Object data, String messageKey) {
    public static OperationOutcome success() {
        return success(null);
    }

    public static OperationOutcome success(Object data) {
        return new OperationOutcome(0, data, "server.success");
    }

    public static OperationOutcome failure(Integer code, String messageKey) {
        return new OperationOutcome(code, null, messageKey);
    }

    public static OperationOutcome create(Integer code, Object data, String messageKey) {
        return new OperationOutcome(code, data, messageKey);
    }
}
