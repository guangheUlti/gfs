package com.guanghe.fs.file.enums;

import lombok.Getter;

/**
 * 下载错误代码枚举
 * 
 * @author guangheUlti
 */
@Getter
public enum DownloadErrorCode {
    /**
     * 文件不存在
     */
    FILE_NOT_FOUND("FILE_NOT_FOUND", "文件不存在"),

    /**
     * 存储读取失败
     */
    STORAGE_READ_FAILED("STORAGE_READ_FAILED", "存储读取失败"),

    /**
     * 网络超时
     */
    NETWORK_TIMEOUT("NETWORK_TIMEOUT", "网络超时"),

    /**
     * 权限不足
     */
    PERMISSION_DENIED("PERMISSION_DENIED", "权限不足"),

    /**
     * 分片索引无效
     */
    INVALID_CHUNK_INDEX("INVALID_CHUNK_INDEX", "分片索引无效"),

    /**
     * 任务不存在
     */
    TASK_NOT_FOUND("TASK_NOT_FOUND", "任务不存在"),

    /**
     * 任务状态无效
     */
    INVALID_TASK_STATUS("INVALID_TASK_STATUS", "任务状态无效");

    /**
     * 错误代码
     */
    private final String code;

    /**
     * 错误信息
     */
    private final String message;

    DownloadErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }
}
