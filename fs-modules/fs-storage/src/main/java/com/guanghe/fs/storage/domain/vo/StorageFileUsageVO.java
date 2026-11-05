package com.guanghe.fs.storage.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 存储配置的文件占用信息
 * <p>
 * 删除存储配置前由前端展示：告诉用户该存储下还有多少条 GFS 记录。
 * 删除本身只是卸载，这些记录会被清理，真实文件不动。
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
}
