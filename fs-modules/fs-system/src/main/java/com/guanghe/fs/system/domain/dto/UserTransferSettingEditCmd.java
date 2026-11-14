package com.guanghe.fs.system.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UserTransferSettingEditCmd {

    @NotNull(message = "下载速率限制不能为空")
    @Max(value = 200, message = "下载速率最大不能超过200 MB/S")
    private Integer downloadSpeedLimit;

    @NotNull(message = "上传并发数不能为空")
    @Max(value = 5, message = "上传并发数不能超过5")
    private Integer concurrentUploadQuantity;

    @NotNull(message = "下载并发数不能为空")
    @Max(value = 5, message = "下载并发数不能超过5")
    private Integer concurrentDownloadQuantity;

    @NotNull(message = "分片大小不能为空")
    @Min(value = 1024 * 1024L, message = "分片大小不能小于1MB")
    @Max(value = 1024 * 1024 * 1024L, message = "分片大小不能超过1GB")
    private Long chunkSize;

    /** 可选；兼容老客户端未传时不改动原值 */
    private Boolean autoNavigateTransfer;
}
