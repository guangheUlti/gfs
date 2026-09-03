package com.guanghe.fs.framework.preview.strategy.impl.archive;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArchiveFileInfo {

    /**
     * 文件名
     */
    private String name;

    /**
     * 文件路径
     */
    private String path;

    /**
     * 是否是目录
     */
    private Boolean isDirectory;

    /**
     * 文件扩展名
     */
    private String extension;
}
