package com.guanghe.fs.framework.common.exception;

import java.io.Serial;

/**
 * 存储操作异常
 *
 * @Author: guangheUlti
 * @Date: 2025/5/8 8:59
 */
public class StorageOperationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = -29314971002267476L;

    public StorageOperationException(String message) {
        super(message);
    }

    public StorageOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
