package com.guanghe.fs.file.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 上传同名冲突项：目标目录中已存在、与待上传文件同名的文件
 */
@Data
public class FileConflictVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待上传的文件名（前端用来与原文件对应）
     */
    private String fileName;

    /**
     * 已存在文件的 ID
     */
    private String fileId;

    /**
     * 已存在文件的大小
     */
    private Long size;

    /**
     * 已存在文件的上传时间
     */
    private LocalDateTime uploadTime;

    /**
     * 同名项是否为目录；目录没有大小与上传时间
     */
    private Boolean isDir;
}
