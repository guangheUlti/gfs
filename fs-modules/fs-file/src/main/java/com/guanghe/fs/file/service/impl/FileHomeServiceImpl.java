package com.guanghe.fs.file.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.guanghe.fs.storage.plugin.boot.LocalStorageManager;
import com.mybatisflex.core.query.QueryWrapper;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.domain.qry.FileHomeUsedBytesQry;
import com.guanghe.fs.file.domain.qry.FileQry;
import com.guanghe.fs.file.domain.vo.FileHomeUsedBytesVO;
import com.guanghe.fs.file.domain.vo.FileHomeVO;
import com.guanghe.fs.file.domain.vo.FileVO;
import com.guanghe.fs.file.domain.vo.StorageCapacityVO;
import com.guanghe.fs.file.domain.vo.SystemInfoVO;
import com.guanghe.fs.file.service.FileHomeService;
import com.guanghe.fs.file.service.FileInfoService;
import com.guanghe.fs.framework.common.domain.PageResult;
import com.guanghe.fs.storage.facade.StorageServiceFacade;
import com.guanghe.fs.storage.plugin.core.IStorageOperationService;
import com.guanghe.fs.storage.plugin.core.context.StoragePlatformContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.guanghe.fs.file.domain.table.FileInfoTableDef.FILE_INFO;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileHomeServiceImpl implements FileHomeService {

    private final FileInfoService fileInfoService;

    private final StorageServiceFacade storageServiceFacade;

    private final LocalStorageManager localStorageManager;

    @Override
    public FileHomeVO getFileHomes(FileHomeUsedBytesQry qry) {
        FileHomeVO fileHomeVO = new FileHomeVO();
        Long usedStorageBytes = fileInfoService.calculateUsedStorage();
        fileHomeVO.setUsedStorage(formatValue(usedStorageBytes, qry.getUnit()));
        fileHomeVO.setUnit(unitLabel(qry.getUnit()));

        //查询用户最近使用的文件
        FileQry fileQry = new FileQry();
        fileQry.setIsRecents(Boolean.TRUE);
        PageResult<FileVO> recentFiles = fileInfoService.getList(fileQry);
        fileHomeVO.setRecentFiles(recentFiles.getData().getRecords());

        List<FileHomeUsedBytesVO> usedBytes = getFileHomeUsedBytes(qry);
        fileHomeVO.setUsedBytes(usedBytes);
        return fileHomeVO;
    }

    @Override
    public StorageCapacityVO getStorageCapacity() {
        Long used = fileInfoService.calculateUsedStorage();
        StorageCapacityVO vo = new StorageCapacityVO();
        vo.setUsedBytes(used == null ? 0L : used);

        Long available = null;
        try {
            IStorageOperationService storageService = storageServiceFacade.getCurrentStorageService();
            available = storageService == null ? null : storageService.getAvailableSpace();
        } catch (Exception e) {
            // 取不到容量不能影响页面渲染，降级为只展示已使用量
            log.warn("获取存储剩余容量失败，按容量不可知处理: {}", e.getMessage());
        }

        if (available == null) {
            vo.setCapacityKnown(false);
            return vo;
        }
        vo.setCapacityKnown(true);
        vo.setAvailableBytes(available);
        // 总量 = 剩余可写空间 + 已存文件占用
        vo.setTotalBytes(available + vo.getUsedBytes());
        return vo;
    }

    public List<FileHomeUsedBytesVO> getFileHomeUsedBytes(FileHomeUsedBytesQry qry) {
        String storageId = StoragePlatformContextHolder.getConfigId();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endTime = now.with(LocalTime.MAX);
        LocalDateTime startTime = calculateStartTime(qry.getDateType(), now);

        QueryWrapper queryWrapper = QueryWrapper.create()
                .select(FILE_INFO.UPLOAD_TIME, FILE_INFO.SIZE)
                .where(FILE_INFO.IS_DIR.eq(false))
                .and(FILE_INFO.IS_DELETED.eq(false))
                .and(FILE_INFO.UPLOAD_TIME.between(startTime, endTime));
        // 本地存储的 configId 为 null，需用 IS NULL 匹配
        if (storageId == null || storageId.isBlank()) {
            queryWrapper.and(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.isNull());
        } else {
            queryWrapper.and(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.eq(storageId));
        }

        List<FileInfo> files = fileInfoService.list(queryWrapper);
        if (CollUtil.isEmpty(files)) {
            return CollUtil.newArrayList();
        }
        // 内存聚合：按日期字符串分组求和
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        Map<String, Long> dateMap = files.stream()
                .collect(Collectors.groupingBy(
                        f -> f.getUploadTime().format(fmt),
                        Collectors.summingLong(FileInfo::getSize)
                ));

        double divisor = unitDivisor(qry.getUnit());

        // 补全日期并转换单位
        return buildFinalResult(startTime, endTime, dateMap, divisor, fmt, qry.getUnit());
    }

    private List<FileHomeUsedBytesVO> buildFinalResult(LocalDateTime start, LocalDateTime end, Map<String, Long> data, double divisor, DateTimeFormatter fmt, Integer unit) {
        List<FileHomeUsedBytesVO> result = new ArrayList<>();
        long days = ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate());
        for (int i = 0; i <= days; i++) {
            String currentDay = start.plusDays(i).format(fmt);
            long rawBytes = data.getOrDefault(currentDay, 0L);

            FileHomeUsedBytesVO vo = new FileHomeUsedBytesVO();
            vo.setDate(currentDay);

            vo.setUsedBytes(formatValue(rawBytes, unit));
            result.add(vo);
        }
        return result;
    }

    /**
     * 1=KB, 2=MB, 3=GB；null 默认 MB。
     */
    private static double unitDivisor(Integer unit) {
        return Math.pow(1024, unitToPower(unit));
    }

    private static int unitToPower(Integer unit) {
        if (unit == null) {
            return 2;
        }
        return switch (unit) {
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 3;
            default -> 2;
        };
    }

    private static String unitLabel(Integer unit) {
        if (unit == null) {
            return "MB";
        }
        return switch (unit) {
            case 1 -> "KB";
            case 2 -> "MB";
            case 3 -> "GB";
            default -> "MB";
        };
    }

    private LocalDateTime calculateStartTime(Integer dateType, LocalDateTime now) {
        if (dateType == null) return now.minusDays(30).with(LocalTime.MIN);
        return switch (dateType) {
            case 0 -> now.minusMonths(3).with(LocalTime.MIN);
            case 2 -> now.minusDays(7).with(LocalTime.MIN);
            default -> now.minusDays(30).with(LocalTime.MIN);
        };
    }

    /**
     * 统一转换字节数为对应单位的数值
     *
     * @param rawBytes 原始字节数
     * @param unit     单位类型：1=KB, 2=MB, 3=GB
     * @return 转换后的小数
     */
    private double formatValue(Long rawBytes, Integer unit) {
        if (rawBytes == null || rawBytes == 0L) {
            return 0.0;
        }
        double divisor = unitDivisor(unit);
        int scale = (unit != null && unit == 3) ? 4 : 2;
        return BigDecimal.valueOf(rawBytes)
                .divide(BigDecimal.valueOf(divisor), scale, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /**
     * 采集系统与运行信息（OS / CPU / 内存 / 运行时长 / 存储路径 / 磁盘分区）。
     * 单项采集失败只降级该字段（留 null），不影响整个接口。
     */
    @Override
    public SystemInfoVO getSystemInfo() {
        SystemInfoVO vo = new SystemInfoVO();
        Runtime runtime = Runtime.getRuntime();
        java.lang.management.OperatingSystemMXBean osBean =
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();

        vo.setOsName(osBean.getName());
        vo.setOsArch(osBean.getArch());
        vo.setCpuCores(runtime.availableProcessors());
        vo.setJavaVersion(System.getProperty("java.version"));

        // CPU 使用率：com.sun.management.OperatingSystemMXBean 提供即时采样值
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            try {
                double processLoad = sunBean.getProcessCpuLoad();
                double systemLoad = sunBean.getSystemCpuLoad();
                vo.setProcessCpuLoad(processLoad < 0 ? null : processLoad * 100);
                vo.setSystemCpuLoad(systemLoad < 0 ? null : systemLoad * 100);
            } catch (Exception e) {
                log.debug("CPU 使用率采集失败: {}", e.getMessage());
            }
        }

        // JVM 堆内存
        vo.setJvmUsedBytes(runtime.totalMemory() - runtime.freeMemory());
        vo.setJvmCommittedBytes(runtime.totalMemory());
        vo.setJvmMaxBytes(runtime.maxMemory());

        // 运行时长
        long uptimeMillis = System.currentTimeMillis() - START_TIME_MILLIS;
        vo.setUptimeMillis(uptimeMillis);
        vo.setStartTime(java.time.Instant.ofEpochMilli(START_TIME_MILLIS)
                .atZone(java.time.ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        vo.setUptimeText(formatUptime(uptimeMillis));

        // 当前存储平台类型与根路径（取不到留 null，不影响渲染）
        try {
            IStorageOperationService storageService = storageServiceFacade.getCurrentStorageService();
            if (storageService != null) {
                String configId = StoragePlatformContextHolder.getConfigId();
                if (configId == null || configId.isBlank()) {
                    vo.setStorageType("Local");
                    Object basePath = localStorageManager.getEffectiveProperties().get("basePath");
                    vo.setStoragePath(basePath == null ? null : String.valueOf(basePath));
                } else {
                    vo.setStorageType("自定义存储");
                }
            }
        } catch (Exception e) {
            log.debug("存储路径采集失败: {}", e.getMessage());
        }

        // 磁盘分区（仅本地类平台有物理磁盘概念；对象存储给不出就空列表）
        try {
            vo.setDisks(collectDiskPartitions());
        } catch (Exception e) {
            vo.setDisks(List.of());
            log.debug("磁盘分区采集失败: {}", e.getMessage());
        }
        return vo;
    }

    /** 进程启动时刻（类加载即进程启动后很快发生，误差可忽略） */
    private static final long START_TIME_MILLIS =
            java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();

    private List<SystemInfoVO.DiskPartitionVO> collectDiskPartitions() {
        List<SystemInfoVO.DiskPartitionVO> disks = new ArrayList<>();
        // 仅 Windows 有盘符概念；Linux/macOS 取文件系统根聚合
        java.io.File[] roots = java.io.File.listRoots();
        if (roots == null) {
            return disks;
        }
        for (java.io.File root : roots) {
            try {
                long total = root.getTotalSpace();
                long free = root.getFreeSpace();
                if (total <= 0) {
                    continue;
                }
                SystemInfoVO.DiskPartitionVO disk = new SystemInfoVO.DiskPartitionVO();
                disk.setMountPoint(root.getAbsolutePath());
                disk.setTotalBytes(total);
                disk.setFreeBytes(free);
                disk.setUsedBytes(total - free);
                disks.add(disk);
            } catch (Exception e) {
                log.debug("分区 {} 采集失败: {}", root, e.getMessage());
            }
        }
        return disks;
    }

    private static String formatUptime(long millis) {
        long minutes = millis / 60_000;
        long days = minutes / (60 * 24);
        long hours = (minutes % (60 * 24)) / 60;
        long mins = minutes % 60;
        if (days > 0) {
            return days + "天 " + hours + "小时 " + mins + "分钟";
        }
        if (hours > 0) {
            return hours + "小时 " + mins + "分钟";
        }
        return mins + "分钟";
    }
}
