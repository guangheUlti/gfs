package com.guanghe.fs.file.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 更新文本文件内容DTO
 */
@Data
public class UpdateTextContentCmd {

    @Schema(title = "文本内容")
    @NotNull(message = "文本内容不能为空")
    private String content;
}
