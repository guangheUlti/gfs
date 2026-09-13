package com.guanghe.fs.file.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 系统与运行信息，用于侧边栏「存储空间」弹窗的系统信息/运行信息面板。
 * <p>
 * 所有字段均可为 null：任一项采集失败不应影响整个接口，
 * 前端对 null 统一显示为「—」。
 *
 * @Author: guangheUlti
 */
@Data
@Schema(description = "系统与运行信息")
public class SystemInfoVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "操作系统名称，如 Windows 11 / Linux (amd64)")
    private String osName;

    @Schema(description = "系统架构，如 amd64 / aarch64")
    private String osArch;

    @Schema(description = "JVM 可用处理器核心数")
    private Integer cpuCores;

    @Schema(description = "JVM 进程 CPU 使用率百分比（0-100），采集失败为 null")
    private Double processCpuLoad;

    @Schema(description = "系统整体 CPU 使用率百分比（0-100），采集失败为 null")
    private Double systemCpuLoad;

    @Schema(description = "JVM 已用堆内存字节数")
    private Long jvmUsedBytes;

    @Schema(description = "JVM 最大堆内存字节数")
    private Long jvmMaxBytes;

    @Schema(description = "JVM 已提交堆内存字节数")
    private Long jvmCommittedBytes;

    @Schema(description = "JVM 版本")
    private String javaVersion;

    @Schema(description = "服务启动时间（ISO-8601），如 2026-09-13T10:00:00")
    private String startTime;

    @Schema(description = "已运行时长文案，如 3天 4小时 12分钟")
    private String uptimeText;

    @Schema(description = "已运行毫秒数")
    private Long uptimeMillis;

    @Schema(description = "数据存储根路径（当前存储平台 basePath），不可知为 null")
    private String storagePath;

    @Schema(description = "存储平台类型，如 local / aliyun-oss，不可知为 null")
    private String storageType;

    @Schema(description = "磁盘分区信息（按挂载点聚合），采集失败为空列表")
    private List<DiskPartitionVO> disks;

    @Data
    @Schema(description = "磁盘分区信息")
    public static class DiskPartitionVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "挂载点/盘符，如 D:\\ 或 /")
        private String mountPoint;

        @Schema(description = "总空间字节数")
        private Long totalBytes;

        @Schema(description = "已用空间字节数")
        private Long usedBytes;

        @Schema(description = "剩余空间字节数")
        private Long freeBytes;
    }
}
