package com.guanghe.fs.file.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 存储容量信息，用于侧边栏的空间使用条。
 * <p>
 * 总容量的口径为「存储底座剩余可写空间 + 本系统已存文件占用」，
 * 即把已经用掉的部分也算回总量，得到该存储底座的实际容量。
 *
 * @Author: guangheUlti
 */
@Data
@Schema(description = "存储容量信息")
public class StorageCapacityVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "已使用字节数（本系统已存文件总量）")
    private Long usedBytes;

    @Schema(description = "剩余可用字节数；容量不可知时为 null")
    private Long availableBytes;

    @Schema(description = "总容量字节数（剩余可用 + 已使用）；容量不可知时为 null")
    private Long totalBytes;

    @Schema(description = "容量是否可知。对象存储无本地磁盘概念时为 false，此时只展示已使用量")
    private Boolean capacityKnown;
}
