package com.guanghe.fs.file.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * JVM 最大内存配置，用于存储空间弹窗的 JVM 内存设置。
 * <p>
 * 说明：-Xmx 只能在 JVM 启动时读取，运行期修改仅落盘，
 * 需配合看门狗进程重启后端后按新值启动（见 restart）。
 *
 * @Author: guangheUlti
 */
@Data
@Schema(description = "JVM 最大内存配置")
public class JvmMemoryVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "已保存在配置文件中的最大堆内存（MB）；未配置为 null，表示使用 JVM 默认值")
    private Integer configuredMaxMemoryMb;

    @Schema(description = "当前运行实例实际的最大堆内存（字节）")
    private Long runtimeMaxBytes;
}