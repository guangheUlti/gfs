package com.guanghe.fs.file.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CheckUploadCmd {

    @NotBlank(message = "taskId不能为空")
    private String taskId;

    @NotBlank(message = "fileMd5不能为空")
    private String fileMd5;

    private String fileName;
}
