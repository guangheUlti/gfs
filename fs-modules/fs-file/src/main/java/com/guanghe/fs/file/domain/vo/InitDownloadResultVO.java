package com.guanghe.fs.file.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Set;

/**
 * 初始化下载结果
 * 
 * @author guangheUlti
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InitDownloadResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 文件大小
     */
    private Long fileSize;

    /**
     * 总分片数
     */
    private Integer totalChunks;

    /**
     * 分片大小
     */
    private Long chunkSize;

    /**
     * 已下载的分片索引列表（用于断点续传）
     */
    private Set<Integer> downloadedChunks;
}
