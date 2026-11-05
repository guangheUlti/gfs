package com.guanghe.fs.storage.plugin.core.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 分片临时目录清理工具
 * <p>
 * 删除后必须回查目录是否真的消失：Windows 上子文件刚被其它线程写完关闭时，父目录会短暂处于
 * 不可移除状态，此时 Hutool {@code FileUtil.del} 会走递归兜底并丢弃兜底的结果、照样返回 true，
 * 所以它的返回值不能当作删除成功的依据。
 *
 * @Author: guangheUlti
 * @Date: 2026/11/04
 */
public final class TempDirCleaner {

    /** 退避重试次数与首次间隔：实测子文件句柄释放造成的窗口在数百毫秒内 */
    private static final int ATTEMPTS = 4;
    private static final long FIRST_BACKOFF_MS = 200L;

    private TempDirCleaner() {
    }

    /**
     * 递归删除目录，失败后退避重试
     *
     * @return 目录是否确实已不存在
     */
    public static boolean deleteWithRetry(Path dir) {
        long backoff = FIRST_BACKOFF_MS;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                deleteTree(dir);
            } catch (IOException e) {
                // 交给下一轮重试，最终仍删不掉时由调用方告警
            }
            if (Files.notExists(dir)) {
                return true;
            }
            if (attempt == ATTEMPTS) {
                break;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            backoff *= 2;
        }
        return Files.notExists(dir);
    }

    /**
     * 自底向上删除目录及其内容；目录不存在时视为已完成
     */
    public static void deleteTree(Path dir) throws IOException {
        if (Files.notExists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            List<Path> descending = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : descending) {
                Files.deleteIfExists(path);
            }
        }
    }
}
