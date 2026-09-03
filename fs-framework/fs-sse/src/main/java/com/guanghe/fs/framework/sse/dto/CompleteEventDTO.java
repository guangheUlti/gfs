package com.guanghe.fs.framework.sse.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 完成事件数据传输对象
 * 
 * @author guanghe
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompleteEventDTO {
    
    /**
     * 任务ID
     */
    private String taskId;
    
    /**
     * 文件ID
     */
    private String fileId;
    
    /**
     * 文件名
     */
    private String fileName;
    
    /**
     * 文件大小（字节）
     */
    private Long fileSize;
}
