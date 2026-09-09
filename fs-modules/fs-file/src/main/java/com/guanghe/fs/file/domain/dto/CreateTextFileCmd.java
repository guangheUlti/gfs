package com.guanghe.fs.file.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 新建文本文件DTO
 */
@Data
public class CreateTextFileCmd {

    @Schema(title = "文件名（不含后缀，若误带后缀会被去除）")
    @NotBlank(message = "文件名不能为空")
    private String fileName;

    @Schema(title = "文件后缀（不含点，须为可在线编辑类型，缺省 txt）")
    private String suffix;

    @Schema(title = "父目录ID")
    private String parentId;

    @Schema(title = "初始文本内容")
    private String content;
}
