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

    /**
     * 目标名称（可选）：仅用于无状态远程的单个文件移动。
     * 虚拟 ID 由路径派生，移动+改名必须原子完成，不能先 move 再 rename。
     */
    private String displayName;
}
