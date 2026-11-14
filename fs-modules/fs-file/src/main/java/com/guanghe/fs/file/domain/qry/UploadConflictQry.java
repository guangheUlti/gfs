package com.guanghe.fs.file.domain.qry;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 上传同名冲突检测查询
 */
@Data
public class UploadConflictQry {

    /**
     * 目标目录 ID；为空表示根目录
     */
    private String parentId;

    /**
     * 待上传的文件名集合（仅文件，不含目录相对路径）
     */
    @Size(max = 500, message = "单次最多检测500个文件")
    private List<String> fileNames;

    /**
     * 待上传的目录名集合（仅顶层目录名）
     */
    @Size(max = 500, message = "单次最多检测500个目录")
    private List<String> dirNames;
}
