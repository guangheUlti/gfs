package com.guanghe.fs.file.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CheckUploadCmd {

    @NotBlank(message = "taskId不能为空")
    private String taskId;

    /**
     * 文件内容 MD5。
     * <p>
     * 允许为空：挂载式/落盘加密存储不支持按内容去重，前端会在 init 拿到
     * dedupeSupported=false 时跳过计算直接上传，此时服务端也不做查重。
     */
    private String fileMd5;

    private String fileName;
}
