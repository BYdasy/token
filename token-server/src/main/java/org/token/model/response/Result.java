package org.token.model.response;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一接口返回对象
 *
 * 返回码（code）：
 *  0  表示成功
 * -1  表示普通失败
 * 其他状态码根据业务自己定义
 *
 * 数据（data）：
 *  成功时可以返回加密后的数据
 *  失败时一般返回 null 或空字符串
 *
 * 消息（message）：
 *  前端展示消息
 *  这里一般存放已经翻译后的文本
 */
@Data
@NoArgsConstructor
public class Result {

    private Object data;

    private Integer code = 0;

    private String message = "";

    public Result(Object data) {
        this.data = data;
    }

    public Result(Integer code, Object data) {
        this.code = code;
        this.data = data;
    }

    public Result(Integer code, Object data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }
}
