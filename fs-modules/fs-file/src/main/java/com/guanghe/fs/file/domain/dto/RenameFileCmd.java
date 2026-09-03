package com.guanghe.fs.file.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 重命名文件DTO
 *
 * @Author: guangheUlti
 * @Date: 2025/5/13 8:41
 */
@Data
public class RenameFileCmd {

    @NotBlank(message = "文件名称不能为空")
    private String displayName;
}
