package com.guanghe.fs.file.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class InitUploadCmd {

    @NotBlank(message = "文件名不能为空")
    private String fileName;

    @NotNull(message = "文件大小不能为空")
    private Long fileSize;

    private String parentId;

    @NotNull(message = "分片总数不能为空")
    private Integer totalChunks;

    @NotNull(message = "分片大小不能为空")
    private Long chunkSize;

    @NotBlank(message = "MIME类型不能为空")
    private String mimeType;
}
