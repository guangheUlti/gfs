package com.guanghe.fs.file.domain.vo;

import lombok.Data;
import org.springframework.core.io.Resource;

@Data
public class FileDownloadVO {
    private String fileName;
    private Long fileSize;
    private Resource resource;
}
