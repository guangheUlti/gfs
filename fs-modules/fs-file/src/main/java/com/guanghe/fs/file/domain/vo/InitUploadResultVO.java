package com.guanghe.fs.file.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 初始化上传结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InitUploadResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 当前存储是否支持按内容去重（秒传）。
     * <p>
     * 挂载式存储（引用计数按 platform+objectKey 记账）与落盘加密存储（密文每次不同）
     * 都不满足去重前提，前端拿到 false 时不必在上传前计算整文件 MD5，直接上传即可，
     * 省掉一次全量哈希与一次查重往返。
     */
    private Boolean dedupeSupported;
}
