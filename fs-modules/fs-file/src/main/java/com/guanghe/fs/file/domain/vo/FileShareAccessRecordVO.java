package com.guanghe.fs.file.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.guanghe.fs.file.domain.FileShareAccessRecord;
import com.guanghe.fs.framework.common.utils.DateUtils;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AutoMapper(target = FileShareAccessRecord.class)
public class FileShareAccessRecordVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 自增ID
     */
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
    @JsonFormat(pattern = DateUtils.DATE_TIME_PATTERN)
    private LocalDateTime accessTime;
}
