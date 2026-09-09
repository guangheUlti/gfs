package com.guanghe.fs.storage.plugin.core.chunk;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.guanghe.fs.framework.common.exception.StorageOperationException;
import com.guanghe.fs.storage.plugin.core.AbstractStorageOperationService;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分片先落本地临时目录的公共基类
 * <p>
 * 供远程协议插件（SMB/WebDAV/SFTP/FTP）复用：分片先写入本地 temp 目录，
 * complete 时按分片号排序合并为临时文件，再由子类 {@link #writeMerged} 写入远程存储。
 * <p>
 * 注意：一律用 {@link Files#createDirectories}（幂等），禁止 exists()+mkdirs()（并发竞态）。
 *
 * @Author: guangheUlti
 * @Date: 2026/09/09
 */
@Slf4j
public abstract class AbstractTempChunkStorageService extends AbstractStorageOperationService {

    /**
     * 原型构造函数（SPI加载用）
     */
    protected AbstractTempChunkStorageService() {
        super();
    }

    /**
     * 配置化构造函数（反射工厂用）
     */
    protected AbstractTempChunkStorageService(StorageConfig config) {
        super(config);
    }

    /**
     * 分片临时根目录（建议取插件配置 tempPath，默认 {@code ${java.io.tmpdir}/gfs-storage-temp/<identifier小写>}）
     */
    protected abstract String getTempRoot();

    /**
     * 将合并后的临时文件写入远程存储（子类实现），完成后基类负责清理 temp
     *
     * @param mergedFile 合并后的本地临时文件
     * @param objectKey  目标对象键
     */
    protected abstract void writeMerged(Path mergedFile, String objectKey);

    /**
     * 解析 temp 根目录（带默认值 ${java.io.tmpdir}/gfs-storage-temp/<identifier小写>）
     */
    protected String resolveTempRoot(String tempPath, String identifierLower) {
        if (tempPath == null || tempPath.isBlank()) {
            return System.getProperty("java.io.tmpdir") + "/gfs-storage-temp/" + identifierLower;
        }
        return tempPath.trim();
    }

    /**
     * 获取分片任务临时目录（带尾部分隔符风格由调用方用 Path 处理）
     */
    private Path getTempTaskDir(String uploadId) {
        return Paths.get(getTempRoot(), uploadId);
    }

    @Override
    public String initiateMultipartUpload(String objectKey, String mimeType) {
        ensureNotPrototype();
        try {
            // 生成唯一uploadId，同时作为 temp 任务目录名
            String uploadId = IdUtil.simpleUUID();
            Path tempDir = getTempTaskDir(uploadId);
            Files.createDirectories(tempDir);
            log.info("{} 分片上传初始化成功: objectKey={}, uploadId={}, tempDir={}",
                    getLogPrefix(), objectKey, uploadId, tempDir);
            return uploadId;
        } catch (IOException e) {
            throw new StorageOperationException("分片初始化失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String uploadPart(String objectKey, String uploadId, int partNumber,
                             long partSize, InputStream partInputStream) {
        ensureNotPrototype();
        Path partFile = getTempTaskDir(uploadId).resolve("part-" + partNumber);
        try {
            Files.createDirectories(partFile.getParent());
            try (FileOutputStream fos = new FileOutputStream(partFile.toFile())) {
                partInputStream.transferTo(fos);
            }
            // 生成分片标识（文件大小和修改时间），与 Local 插件一致
            File file = partFile.toFile();
            String etag = file.length() + "_" + file.lastModified();
            log.debug("{} 分片上传成功: objectKey={}, partNumber={}, etag={}",
                    getLogPrefix(), objectKey, partNumber, etag);
            return etag;
        } catch (IOException e) {
            log.error("{} 分片上传失败: objectKey={}, partNumber={}", getLogPrefix(), objectKey, partNumber, e);
            throw new StorageOperationException("分片上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Set<Integer> listParts(String objectKey, String uploadId) {
        ensureNotPrototype();
        Set<Integer> uploadedParts = new HashSet<>();
        Path tempDir = getTempTaskDir(uploadId);
        if (!Files.isDirectory(tempDir)) {
            log.debug("{} 临时目录不存在: uploadId={}", getLogPrefix(), uploadId);
            return uploadedParts;
        }
        try (var stream = Files.list(tempDir)) {
            stream.map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("part-"))
                    .forEach(name -> {
                        try {
                            uploadedParts.add(Integer.parseInt(name.substring("part-".length())));
                        } catch (NumberFormatException e) {
                            log.warn("{} 无效的分片文件名: {}", getLogPrefix(), name);
                        }
                    });
        } catch (IOException e) {
            throw new StorageOperationException("列举分片失败: " + e.getMessage(), e);
        }
        log.debug("{} 已上传分片列表: uploadId={}, parts={}", getLogPrefix(), uploadId, uploadedParts);
        return uploadedParts;
    }

    @Override
    public void completeMultipartUpload(String objectKey, String uploadId,
                                        List<Map<String, Object>> partETags) {
        ensureNotPrototype();
        // 入参 partETags 忽略：本地分片无真实 etag，以 temp 目录中的分片文件为准
        Path tempDir = getTempTaskDir(uploadId);
        Path mergedFile = tempDir.resolve("merged-" + IdUtil.fastSimpleUUID());
        try {
            if (!Files.isDirectory(tempDir)) {
                throw new StorageOperationException("分片临时目录不存在: " + tempDir);
            }
            // 收集 temp 目录中的分片并按分片号排序
            List<Integer> partNumbers = new ArrayList<>();
            try (var stream = Files.list(tempDir)) {
                stream.map(p -> p.getFileName().toString())
                        .filter(name -> name.startsWith("part-"))
                        .forEach(name -> {
                            try {
                                partNumbers.add(Integer.parseInt(name.substring("part-".length())));
                            } catch (NumberFormatException ignored) {
                                // 非分片文件（如残留 merged-*）跳过
                            }
                        });
            }
            if (partNumbers.isEmpty()) {
                throw new StorageOperationException("没有可合并的分片文件: uploadId=" + uploadId);
            }
            partNumbers.sort(Comparator.naturalOrder());

            // 依次合并分片到临时文件
            Files.createDirectories(mergedFile.getParent());
            try (FileOutputStream fos = new FileOutputStream(mergedFile.toFile())) {
                for (Integer partNumber : partNumbers) {
                    Path partFile = tempDir.resolve("part-" + partNumber);
                    try (FileInputStream fis = new FileInputStream(partFile.toFile())) {
                        fis.transferTo(fos);
                    }
                }
            }

            // 交给子类写远程
            writeMerged(mergedFile, objectKey);

            log.info("{} 分片上传完成: objectKey={}, uploadId={}, parts={}",
                    getLogPrefix(), objectKey, uploadId, partNumbers.size());
        } catch (StorageOperationException e) {
            throw e;
        } catch (IOException e) {
            log.error("{} 分片合并失败: objectKey={}, uploadId={}", getLogPrefix(), objectKey, uploadId, e);
            throw new StorageOperationException("分片合并失败: " + e.getMessage(), e);
        } finally {
            // 无论成败都清理 temp 任务目录
            try {
                FileUtil.del(tempDir.toFile());
            } catch (Exception e) {
                log.warn("{} 清理分片临时目录失败: {}", getLogPrefix(), tempDir, e);
            }
        }
    }

    @Override
    public void abortMultipartUpload(String objectKey, String uploadId) {
        ensureNotPrototype();
        try {
            FileUtil.del(getTempTaskDir(uploadId).toFile());
            log.info("{} 分片上传已中止: objectKey={}, uploadId={}", getLogPrefix(), objectKey, uploadId);
        } catch (Exception e) {
            throw new StorageOperationException("中止分片上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        // 无需释放资源：temp 分片目录由合并/中止逻辑清理
    }
}
