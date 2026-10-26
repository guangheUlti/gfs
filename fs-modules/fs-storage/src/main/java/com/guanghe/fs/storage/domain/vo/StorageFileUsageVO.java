package com.guanghe.fs.storage.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 存储配置的文件占用信息
 * <p>
 * 删除存储配置前由前端展示：有文件时提示将先删除这些文件，再删除存储配置。
 *
 * @Author: guangheUlti
 * @Date: 2026/10/26
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StorageFileUsageVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 该存储下的文件记录数（含回收站） */
    private long fileCount;

    /**
     * 删除时是否只清网盘索引。
     * <p>
     * true 为挂载式存储（本地挂载/本地入库/SMB 等，真实目录是唯一真相源）：只删索引，真实文件保留；
     * false 为 GFS 托管存储（本地/OSS/等）：连同物理文件一并永久删除。
     */
    private boolean indexOnly;
}
