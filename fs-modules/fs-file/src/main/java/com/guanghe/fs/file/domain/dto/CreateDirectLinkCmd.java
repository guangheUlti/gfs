package com.guanghe.fs.file.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建/获取直链 DTO
 *
 * @Author: guangheUlti
 */
@Data
public class CreateDirectLinkCmd {

    /**
     * 文件ID（仅支持单个文件）
     */
    @NotBlank(message = "请选择要生成直链的文件")
    private String fileId;

    /**
     * 有效期类型：1-7天 2-30天 3-自定义 4-永久（默认 1-7天）
     */
    private Integer expireType;

    /**
     * 最大下载次数（可选）
     */
    private Integer maxDownloadCount;
}