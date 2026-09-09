package com.guanghe.fs.storage.plugin.core.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 目录条目（挂载式存储同步器用）
 * 描述一层目录列举结果中的单个文件或子目录
 *
 * @Author: guangheUlti
 * @Date: 2026/09/09
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StorageObjectEntry {

    /**
     * 相对键（posix '/' 分隔、无前导 '/'，相对挂载根）
     */
    private String key;

    /**
     * 是否目录
     */
    private boolean isDir;

    /**
     * 文件大小（字节），目录为 null
     */
    private Long size;

    /**
     * 最后修改时间（毫秒时间戳），取不到为 null
     */
    private Long lastModified;
}
