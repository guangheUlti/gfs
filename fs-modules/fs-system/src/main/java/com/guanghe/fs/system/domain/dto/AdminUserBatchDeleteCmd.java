package com.guanghe.fs.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 管理员批量删除账号
 * <p>
 * 一次最多删除 {@link #MAX_SIZE} 个账号：每个账号都要清理文件、分享等数据，
 * 批量越大失败回滚和超时风险越高，超出请分批操作。
 *
 * @Author: guangheUlti
 * @Date: 2026/11/2
 */
@Data
public class AdminUserBatchDeleteCmd {

    /** 单次批量上限 */
    public static final int MAX_SIZE = 50;

    @NotEmpty(message = "ids不能为空")
    @Size(max = MAX_SIZE, message = "单次最多删除 " + MAX_SIZE + " 个账号")
    private List<@NotBlank(message = "账号id不能为空") String> ids;
}
