package org.token.exception;

import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.util.DisconnectedClientHelper;

import java.util.Collections;
import java.util.IdentityHashMap;

/** Spring 将所有响应不可写异常视为断连；这里只按其真实原因识别已知的客户端中断。 */
final class ServletDisconnects {
    private ServletDisconnects() { }

    static boolean isClientDisconnect(Throwable error) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        while (error instanceof AsyncRequestNotUsableException) {
            if (!seen.add(error) || error.getCause() == null) return false;
            error = error.getCause();
        }
        return DisconnectedClientHelper.isClientDisconnectedException(error);
    }
}
