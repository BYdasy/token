package org.token.exception;

import lombok.Getter;

/**
 * 业务异常
 *
 * 只用于主动抛出的业务错误，例如：
 * 用户不存在、密码错误、余额不足、参数不合法等。
 *
 * 不要用它包装系统异常。
 */
@Getter
public class BusinessException extends RuntimeException {

    /**
     * 业务错误码
     */
    private final Integer code;

    /**
     * 附加数据
     */
    private final Object data;

    public BusinessException(String message) {
        super(message);
        this.code = -1;
        this.data = "";
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
        this.data = "";
    }

    public BusinessException(Integer code, Object data, String message) {
        super(message);
        this.code = code;
        this.data = data;
    }
}