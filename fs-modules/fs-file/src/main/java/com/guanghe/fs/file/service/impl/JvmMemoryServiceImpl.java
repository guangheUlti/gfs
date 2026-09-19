package com.guanghe.fs.file.service.impl;

import cn.hutool.core.io.FileUtil;
import com.guanghe.fs.file.domain.vo.JvmMemoryVO;
import com.guanghe.fs.file.service.JvmMemoryService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 基于本地配置文件的 JVM 最大内存实现。
 * <p>
 * 配置文件与重启标记均放在启动脚本可读的位置：优先取环境变量
 * GFS_JVM_CONF，缺省回退到 {@code <user.dir>/storage} 目录，
 * 与部署包 start.bat / dev 看门狗脚本保持同一套约定。
 *
 * @Author: guangheUlti
 */
@Slf4j
@Service
public class JvmMemoryServiceImpl implements JvmMemoryService {

    /** 合法范围（MB）：过小会撑不起服务，过大可能让物理内存耗尽 */
    private static final int MIN_MB = 256;
    private static final int MAX_MB = 65536;

    private Path confPath;
    private Path restartFlagPath;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jvm-restart");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    void init() {
        Path dir = resolveConfigDir();
        confPath = dir.resolve("jvm-xmx.conf");
        restartFlagPath = dir.resolve("jvm-restart.flag");
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            log.warn("创建 JVM 内存配置目录失败: {}", dir, e);
        }
        log.debug("JVM 内存配置文件: {}", confPath);
    }

    /** 启动脚本与后端共用约定：GFS_JVM_CONF 或 <user.dir>/storage */
    private Path resolveConfigDir() {
        String env = System.getenv("GFS_JVM_CONF");
        if (env != null && !env.isBlank()) {
            return Paths.get(env).toAbsolutePath().getParent();
        }
        return Paths.get(System.getProperty("user.dir"), "storage").toAbsolutePath();
    }

    @Override
    public JvmMemoryVO getConfig() {
        JvmMemoryVO vo = new JvmMemoryVO();
        vo.setRuntimeMaxBytes(Runtime.getRuntime().maxMemory());
        vo.setConfiguredMaxMemoryMb(readConfiguredMb());
        return vo;
    }

    @Override
    public void saveConfig(Integer maxMemoryMb) {
        if (maxMemoryMb != null && (maxMemoryMb < MIN_MB || maxMemoryMb > MAX_MB)) {
            throw new IllegalArgumentException(
                    "JVM 最大内存需在 " + MIN_MB + " ~ " + MAX_MB + " MB 之间（或留空使用 JVM 默认值）");
        }
        try {
            Files.createDirectories(confPath.getParent());
            if (maxMemoryMb == null) {
                Files.deleteIfExists(confPath);
                log.info("已清除 JVM 最大内存配置，将使用 JVM 默认值（重启后生效）");
            } else {
                FileUtil.writeUtf8String(maxMemoryMb.toString(), confPath.toFile());
                log.info("已保存 JVM 最大内存配置: {} MB（重启后生效）", maxMemoryMb);
            }
        } catch (Exception e) {
            throw new IllegalStateException("保存 JVM 内存配置失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void triggerRestart() {
        try {
            Files.createDirectories(restartFlagPath.getParent());
            Files.write(restartFlagPath, List.of("1"), StandardCharsets.UTF_8);
            log.info("已写重启标记，将于 1 秒后退出并由看门狗按最新配置重新拉起: {}", restartFlagPath);
        } catch (Exception e) {
            throw new IllegalStateException("写入重启标记失败: " + e.getMessage(), e);
        }
        // 留出时间让响应 flush 给浏览器，再由看门狗接管重启
        scheduler.schedule(() -> {
            log.info("触发 JVM 退出，看门狗将自动重启");
            System.exit(0);
        }, 1, TimeUnit.SECONDS);
    }

    /** 读取配置文件中已保存的 MB 值；文件缺失/为空/非法返回 null（JVM 默认） */
    private Integer readConfiguredMb() {
        try {
            if (!Files.exists(confPath)) {
                return null;
            }
            String raw = Files.readString(confPath).trim();
            if (raw.isEmpty() || raw.matches("\\d+")) {
                return raw.isEmpty() ? null : Integer.valueOf(raw);
            }
            log.warn("JVM 内存配置文件内容非法，忽略: {}", raw);
            return null;
        } catch (Exception e) {
            log.warn("读取 JVM 内存配置失败: {}", e.getMessage());
            return null;
        }
    }
}