package com.guanghe.fs.file.domain.qry;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 下载分片查询参数
 * 
 * @author guangheUlti
 */
@Data
@Schema(description = "下载分片查询参数")
public class DownloadChunkQry {

    /**
     * 任务ID
     */
    @NotBlank(message = "任务ID不能为空")
    @Schema(description = "任务ID", example = "task123")
    private String taskId;

    /**
     * 分片索引（从0开始）
     */
    @NotNull(message = "分片索引不能为空")
    @Min(value = 0, message = "分片索引不能小于0")
    @Schema(description = "分片索引（从0开始）", example = "0")
    private Integer chunkIndex;
}
