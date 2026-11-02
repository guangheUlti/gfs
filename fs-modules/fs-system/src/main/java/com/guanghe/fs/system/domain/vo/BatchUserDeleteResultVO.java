package com.guanghe.fs.system.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量删除账号结果：成功数 + 逐个失败原因
 * <p>
 * 每个账号独立事务，单个失败不影响其余账号，因此需要把失败项回传给前端提示。
 *
 * @Author: guangheUlti
 * @Date: 2026/11/2
 */
@Data
@Schema(description = "批量删除账号结果")
public class BatchUserDeleteResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "成功删除的账号数量")
    private int deletedCount;

    @Schema(description = "删除失败的账号")
    private List<Failure> failed = new ArrayList<>();

    /** 记录一个成功删除的账号 */
    public void recordDeleted() {
        this.deletedCount++;
    }

    /** 记录一个删除失败的账号 */
    public void recordFailure(String id, String username, String reason) {
        Failure failure = new Failure();
        failure.setId(id);
        failure.setUsername(username);
        failure.setReason(reason);
        this.failed.add(failure);
    }

    /** 单个失败项 */
    @Data
    @Schema(description = "批量删除失败项")
    public static class Failure implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "账号id")
        private String id;

        @Schema(description = "用户名（账号已不存在时可能为空）")
        private String username;

        @Schema(description = "失败原因")
        private String reason;
    }
}
