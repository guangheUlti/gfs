package com.guanghe.fs.file.domain.dto;

import lombok.Data;

@Data
public class UploadChunkCmd {
    private String taskId;
    private Integer chunkIndex;
    private String chunkMd5;
}
