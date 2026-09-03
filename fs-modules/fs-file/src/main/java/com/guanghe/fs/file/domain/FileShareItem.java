package com.guanghe.fs.file.domain;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 分享文件关联实体类
 *
 * @Author: guangheUlti
 * @Date: 2025/10/29 15:13
 */
@Data
@Table("file_share_items")
public class FileShareItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分享ID
     */
    @Id(keyType = KeyType.None)
    private String shareId;

    /**
     * 文件/文件夹ID
     */
    @Id(keyType = KeyType.None)
    private String fileId;


    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
