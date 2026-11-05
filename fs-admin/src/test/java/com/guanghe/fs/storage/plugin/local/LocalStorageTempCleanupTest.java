package com.guanghe.fs.storage.plugin.local;

import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分片临时目录清理自验：合并后目录必须真的消失，兜底扫描只删无主且超龄的目录
 */
class LocalStorageTempCleanupTest {

    private static final byte[] CHUNK = {1, 2, 3};

    @TempDir
    Path base;

    private LocalStorageOperationService storage;
    private Path tempRoot;

    @BeforeEach
    void setUp() {
        storage = new LocalStorageOperationService(StorageConfig.builder()
                .configId("temp-cleanup-test")
                .platformIdentifier("Local")
                .enabled(true)
                .properties(Map.of("basePath", base.toString()))
                .build());
        tempRoot = base.resolve("temp");
    }

    @AfterEach
    void tearDown() throws IOException {
        storage.close();
    }

    @Test
    void 合并完成后临时目录不存在() throws IOException {
        String objectKey = "tempfix/merged.bin";
        String uploadId = storage.initiateMultipartUpload(objectKey, "application/octet-stream");
        List<Map<String, Object>> partETags = new ArrayList<>();
        for (int partNumber = 2; partNumber >= 0; partNumber--) {
            storage.uploadPart(objectKey, uploadId, partNumber, CHUNK.length, new ByteArrayInputStream(CHUNK));
            partETags.add(new HashMap<>(Map.of("partNumber", partNumber)));
        }
        assertTrue(Files.isDirectory(tempRoot.resolve(uploadId)), "合并前分片目录应存在");

        storage.completeMultipartUpload(objectKey, uploadId, partETags);

        assertFalse(Files.exists(tempRoot.resolve(uploadId)), "合并后分片目录必须已消失");
        assertArrayEquals(new byte[]{1, 2, 3, 1, 2, 3, 1, 2, 3}, Files.readAllBytes(base.resolve(objectKey)));
    }

    @Test
    void 兜底扫描只删除无主且超龄的目录() throws IOException {
        Path live = newTempTaskDir("live-upload");
        Path fresh = newTempTaskDir("fresh-upload");
        Path orphan = newTempTaskDir("orphan-upload");
        backdate(orphan, Duration.ofDays(1));

        int deleted = storage.cleanupOrphanTempDirs(Set.of("live-upload"), Duration.ofHours(1));

        assertEquals(1, deleted);
        assertTrue(Files.isDirectory(live), "任务表仍有记录的分片会话不能被清理");
        assertTrue(Files.isDirectory(fresh), "最后修改未超龄的目录可能仍在上传，本轮跳过");
        assertFalse(Files.isDirectory(orphan), "超龄且无主的目录连同其中的分片文件一起删除");
    }

    @Test
    void 任务行删除前按uploadId清理残留目录() throws IOException {
        Path residual = newTempTaskDir("residual-upload");

        assertTrue(storage.cleanupResidualTempDir("residual-upload"));
        assertFalse(Files.isDirectory(residual));
        assertTrue(storage.cleanupResidualTempDir("not-exists-upload"), "目录本就不存在视为已清理");
    }

    private Path newTempTaskDir(String uploadId) throws IOException {
        Path dir = tempRoot.resolve(uploadId);
        Files.createDirectories(dir);
        for (int partNumber = 0; partNumber < 3; partNumber++) {
            Files.write(dir.resolve(String.valueOf(partNumber)), CHUNK);
        }
        return dir;
    }

    private static void backdate(Path dir, Duration age) throws IOException {
        FileTime old = FileTime.from(Instant.now().minus(age));
        try (var children = Files.list(dir)) {
            for (Path child : children.toList()) {
                Files.setLastModifiedTime(child, old);
            }
        }
        Files.setLastModifiedTime(dir, old);
    }
}
