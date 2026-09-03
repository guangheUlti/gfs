package com.guanghe.fs.file.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckUploadResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否秒传
     */
    private Boolean isQuickUpload;

    /**
     * 秒传成功后的文件信息
     */
    private String fileId;

    /**
     * 任务ID
     */
    private String taskId;
    /**
     * 上传ID
     */
    private String uploadId;

    /**
     * 提示信息
     */
    private String message;
}
