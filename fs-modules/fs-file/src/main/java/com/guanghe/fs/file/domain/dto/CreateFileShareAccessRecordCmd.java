package com.guanghe.fs.file.domain.dto;

import com.guanghe.fs.file.domain.FileShareAccessRecord;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;

@Data
@AutoMapper(target = FileShareAccessRecord.class)
public class CreateFileShareAccessRecordCmd {

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
}
