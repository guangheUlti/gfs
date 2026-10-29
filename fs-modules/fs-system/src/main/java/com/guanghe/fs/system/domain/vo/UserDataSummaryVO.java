package com.guanghe.fs.system.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 账号残留数据概览：删除账号前展示，供管理员判断是否彻底清除
 *
 * @Author: guangheUlti
 * @Date: 2026/10/29
 */
@Data
@Schema(description = "账号数据概览")
public class UserDataSummaryVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "文件与文件夹数量（含回收站）")
    private long fileCount;

    @Schema(description = "回收站中的文件数量")
    private long recycleCount;

    @Schema(description = "收藏数量")
    private long favoriteCount;

    @Schema(description = "分享数量")
    private long shareCount;

    @Schema(description = "文件收集数量")
    private long collectionCount;

    @Schema(description = "传输任务数量")
    private long transferTaskCount;

    @Schema(description = "OSS 访问密钥数量")
    private long ossKeyCount;

    /** 是否存在需要清理的内容数据（传输设置等纯配置不计入） */
    public boolean isHasData() {
        return fileCount > 0 || favoriteCount > 0 || shareCount > 0
                || collectionCount > 0 || transferTaskCount > 0 || ossKeyCount > 0;
    }
}
