package com.guanghe.fs.framework.preview.core;

import com.guanghe.fs.framework.common.enums.FileTypeEnum;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PreviewContext {

    /**
     * 文件ID
     */
    private String fileId;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 文件流路径
     */
    private String streamUrl;

    /**
     * 文件大小
     */
    private Long fileSize;

    /**
     * 文件扩展名
     */
    private String extension;

    /**
     * 预览类型
     */
    private FileTypeEnum fileType;
}

