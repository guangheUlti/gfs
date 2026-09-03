package com.guanghe.fs.file.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建分享DTO
 *
 * @Author: guangheUlti
 * @Date: 2025/5/13 8:41
 */
@Data
public class CreateShareCmd {

    /**
     * 文件ID列表（支持多个文件/文件夹）
     */
    @NotEmpty(message = "请选择要分享的文件")
    private List<String> fileIds;

    /**
     * 分享名称（可选，默认取第一个文件名）
     */
    private String shareName;

    /**
     * 有效期类型：1-7天 2-30天 3-自定义 4-永久
     */
    private Integer expireType;

    /**
     * 自定义有效期（可选）
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expireTime;

    /**
     * 权限范围: preview,download  (多个权限逗号分隔)
     */
    private String scope;

    /**
     * 是否需要提取码
     */
    private Boolean needShareCode;

    /**
     * 最大查看次数（可选）
     */
    private Integer maxViewCount;

    /**
     * 最大下载次数（可选）
     */
    private Integer maxDownloadCount;
}
