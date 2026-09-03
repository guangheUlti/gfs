package com.guanghe.fs.file.domain;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 分享访问记录表
 *
 * @Author: guangheUlti
 * @Date: 2025/10/29 15:13
 */
@Data
@Table("file_share_access_record")
public class FileShareAccessRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分享ID
     */
    @Id(keyType = KeyType.Auto)
    private String id;

    /**
     * 分享ID
     */
    private String shareId;

    /**
     * 访问IP
     */
    private String accessIp;

    /**
     * 访问地址
     */
    private String accessAddress;

    /**
     * 访问浏览器
     */
    private String browser;

    /**
     * 访问操作系统
     */
    private String os;

    /**
     * 访问时间
     */
    private LocalDateTime accessTime;
}
