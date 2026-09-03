package com.guanghe.fs.file.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.guanghe.fs.file.domain.FileShare;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AutoMapper(target = FileShare.class)
public class FileShareVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分享ID
     */
    private String id;

    /**
     * 分享名称
     */
    private String shareName;

    /**
     * 提取码
     */
    private String shareCode;

    /**
     * 过期时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expireTime;

    /**
     * 权限范围: preview,download  (逗号分隔)
     */
    private String scope;

    /**
     * 是否永久有效
     */
    private Boolean isPermanent;

    /**
     * 查看次数
     */
    private Integer viewCount;

    /**
     * 下载次数
     */
    private Integer downloadCount;

    /**
     * 最大查看次数
     */
    private Integer maxViewCount;

    /**
     * 最大下载次数
     */
    private Integer maxDownloadCount;

    /**
     * 文件数量
     */
    private Long fileCount;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
