package com.guanghe.fs.file.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 初始化下载命令
 * 
 * @author guangheUlti
 */
@Data
public class InitDownloadCmd {

    /**
     * 文件ID
     */
    @NotBlank(message = "文件ID不能为空")
    private String fileId;

    /**
     * 分片大小（可选，默认使用系统配置）
     */
    private Long chunkSize;
}
