package com.guanghe.fs.file.domain.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 移动文件DTO
 *
 * @Author: guangheUlti
 * @Date: 2025/5/13 8:41
 */
@Data
public class MoveFileCmd {

    private String dirId;

    @NotEmpty(message = "请选择要移动的文件")
    private List<String> fileIds;
}
