package com.guanghe.fs.file.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.guanghe.fs.file.domain.FileTransferTask;
import com.guanghe.fs.file.enums.TransferTaskStatus;
import com.guanghe.fs.file.enums.TransferTaskType;
import com.guanghe.fs.framework.common.utils.DateUtils;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AutoMapper(target = FileTransferTask.class)
public class FileTransferTaskVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 任务ID
     */
    private String taskId;
    /**
     * 任务类型
     */
    private TransferTaskType taskType;
    /**
     * 用户ID
     */
    private String userId;
    /**
     * 父目录ID
     */
    private String parentId;
    /**
     * 对象key
     */
    private String objectKey;
    /**
     * 文件名
     */
    private String fileName;
    /**
     * 文件大小(字节)
     */
    private Long fileSize;

    /**
     * 文件类型(扩展名)
     */
    private String suffix;

    /**
     * 总分片数
     */
    private Integer totalChunks;
    /**
     * 已上传分片数
     */
    private Integer uploadedChunks;
    /**
     * 分片大小(默认5MB)
     */
    private Long chunkSize;
    /**
     * 存储平台配置ID
     */
    private String storagePlatformSettingId;
    /**
     * 状态: uploading-上传中, paused-已暂停, completed-已完成, failed-失败, canceled-已取消
     */
    private TransferTaskStatus status;
    /**
     * 错误信息
     */
    private String errorMsg;
    /**
     * 开始时间
     */
    @JsonFormat(pattern = DateUtils.DATE_TIME_PATTERN)
    private LocalDateTime startTime;
    /**
     * 完成时间
     */
    @JsonFormat(pattern = DateUtils.DATE_TIME_PATTERN)
    private LocalDateTime completeTime;
    
    /**
     * 进度百分比 (0-100)，整数
     */
    private Integer progress;
    
    /**
     * 上传速度 (bytes/s)
     */
    private Long speed;
    
    /**
     * 剩余时间（秒）
     */
    private Integer remainTime;
    
    /**
     * 已上传字节数
     */
    private Long uploadedSize;
    
    /**
     * 完成事件数据（仅当 SSE 推送失败时通过轮询返回）
     */
    private Object completeEventData;
}
