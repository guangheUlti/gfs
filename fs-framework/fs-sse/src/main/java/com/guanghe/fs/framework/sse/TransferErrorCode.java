package com.guanghe.fs.framework.sse;

/**
 * 传输错误代码枚举
 * 
 * @author guanghe
 */
public enum TransferErrorCode {
    
    /**
     * 文件合并失败
     */
    MERGE_FAILED("MERGE_FAILED", "文件合并失败"),
    
    /**
     * 存储空间不足
     */
    STORAGE_FULL("STORAGE_FULL", "存储空间不足"),
    
    /**
     * 文件损坏
     */
    FILE_CORRUPTED("FILE_CORRUPTED", "文件损坏"),
    
    /**
     * 权限不足
     */
    PERMISSION_DENIED("PERMISSION_DENIED", "权限不足"),
    
    /**
     * 未知错误
     */
    UNKNOWN_ERROR("UNKNOWN_ERROR", "未知错误");
    
    private final String code;
    private final String message;
    
    TransferErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }
    
    public String getCode() {
        return code;
    }
    
    public String getMessage() {
        return message;
    }
}
