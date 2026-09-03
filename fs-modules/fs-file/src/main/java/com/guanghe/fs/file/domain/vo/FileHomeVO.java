package com.guanghe.fs.file.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class FileHomeVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Double usedStorage;

    private List<FileHomeUsedBytesVO> usedBytes;

    private List<FileVO> recentFiles;

    private String unit;
}
