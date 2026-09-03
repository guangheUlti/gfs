package com.guanghe.fs.file.domain.vo;

import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.domain.FileTransferTask;
import lombok.Data;

@Data
public class UploadInitVO {
    /**
     * 是否秒传
     */
    private Boolean instant;

    /**
     * 秒传时返回文件信息
     */
    private FileInfo fileInfo;

    /**
     * 需要上传时返回任务信息
     */
    private FileTransferTask task;

    /**
     * 提示信息
     */
    private String message;
}
