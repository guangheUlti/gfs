package com.guanghe.fs.storage.plugin.local;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.guanghe.fs.framework.common.exception.StorageConfigException;
import com.guanghe.fs.framework.common.exception.StorageOperationException;
import com.guanghe.fs.storage.plugin.core.AbstractStorageOperationService;
import com.guanghe.fs.storage.plugin.core.annotation.StoragePlugin;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import com.guanghe.fs.storage.plugin.core.crypto.StreamCipherSupport;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 本地存储插件实现
 *
 * @Author: guangheUlti
 * @Date: 2024/10/26 17:00
 */
@Slf4j
@StoragePlugin(
    identifier = "Local",
    name = "本地存储",
    description = "系统内置的本地磁盘存储；也支持添加多个不同根目录的本地存储实例。",
    icon = "icon-bendicunchu1",
    isDefault = true,
    schemaResource = "classpath:schema/local-schema.json"
)
public class LocalStorageOperationService extends AbstractStorageOperationService {

    private String basePath;

    /** 落盘加密口令（encryptionEnabled=true 时非空） */
    private String encryptionSecret;

    @SuppressWarnings("unused")
    public LocalStorageOperationService() {
        super();
    }

    @SuppressWarnings("unused")
    public LocalStorageOperationService(StorageConfig config) {
        super(config);
    }

    @Override
    protected void validateConfig(StorageConfig config) {
        String basePath = config.getRequiredProperty("basePath", String.class);

        if (basePath == null || basePath.trim().isEmpty()) {
            throw new StorageConfigException("Local 存储配置错误：basePath 不能为空");
        }
        validateEncryption(config);
    }

    /**
     * 校验加密配置：开启时口令必填，未开启时忽略口令
     */
    static void validateEncryption(StorageConfig config) {
        boolean enabled = Boolean.parseBoolean(
                String.valueOf(config.getProperty("encryptionEnabled", Object.class, Boolean.FALSE)));
        if (enabled) {
            String secret = config.getProperty("encryptionSecret", String.class);
            if (secret == null || secret.trim().isEmpty()) {
                throw new StorageConfigException("Local 存储配置错误：开启落盘加密后必须设置密钥（encryptionSecret）");
            }
        }
    }

    @Override
    protected void initialize(StorageConfig config) {
        String rawBasePath = config.getRequiredProperty("basePath", String.class);
        this.basePath = normalizePath(rawBasePath, File.separator, "basePath 不能为空");
        boolean encryptionEnabled = Boolean.parseBoolean(
                String.valueOf(config.getProperty("encryptionEnabled", Object.class, Boolean.FALSE)));
        this.encryptionSecret = encryptionEnabled
                ? config.getRequiredProperty("encryptionSecret", String.class)
                : null;
        // 创建存储目录
        File baseDir = new File(this.basePath);
        if (!baseDir.exists()) {
            if (!baseDir.mkdirs()) {
                throw new StorageOperationException("无法创建存储目录: " + this.basePath);
            }
            log.info("创建存储目录: {}", this.basePath);
        }

        log.info("{} Local 存储初始化完成: basePath={}, encryption={}",
                getLogPrefix(), this.basePath, encryptionEnabled ? "AES-CTR 开启" : "关闭");
    }

    /**
     * 规范化路径（去除末尾分隔符）
     */
    private String normalizePath(String path, String separator, String errorMsg) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException(errorMsg);
        }
        String trimmed = path.trim();
        return trimmed.endsWith(separator)
                ? trimmed.substring(0, trimmed.length() - separator.length())
                : trimmed;
    }

    /**
     * 解析完整文件路径
     */
    private String resolveFullPath(String objectKey) {
        String normalizedObjectKey = objectKey.startsWith("/") || objectKey.startsWith("\\")
                ? objectKey.substring(1)
                : objectKey;
        return basePath + File.separator + normalizedObjectKey;
    }

    /**
     * 加密是否开启
     */
    private boolean encryptionEnabled() {
        return encryptionSecret != null;
    }

    @Override
    public boolean isEncryptionEnabled() {
        ensureNotPrototype();
        return encryptionEnabled();
    }

    @Override
    public void uploadFile(InputStream inputStream, String objectKey) {
        ensureNotPrototype();
        try {
            String fullPath = resolveFullPath(objectKey);
            File targetFile = new File(fullPath);

            File parentDir = targetFile.getParentFile();
            if (parentDir != null) {
                // 并发上传同一天目录时 mkdirs 会因目录已被其他线程创建而误报失败，createDirectories 幂等
                Files.createDirectories(parentDir.toPath());
            }

            try (OutputStream fos = encryptionEnabled()
                    ? StreamCipherSupport.encryptingOutputStream(new FileOutputStream(targetFile), encryptionSecret)
                    : new FileOutputStream(targetFile)) {
                inputStream.transferTo(fos);
            }

            log.debug("{} 文件上传成功: objectKey={}", getLogPrefix(), objectKey);
        } catch (IOException e) {
            throw new StorageOperationException("文件上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream downloadFile(String objectKey) {
        ensureNotPrototype();

        try {
            String fullPath = resolveFullPath(objectKey);
            File file = new File(fullPath);

            if (!file.exists()) {
                throw new StorageOperationException("文件不存在: " + objectKey);
            }

            InputStream raw = new FileInputStream(file);
            return encryptionEnabled() && StreamCipherSupport.isEncryptedFile(file.toPath())
                    ? StreamCipherSupport.decryptingInputStream(raw, encryptionSecret)
                    : raw;
        } catch (IOException e) {
            throw new StorageOperationException("文件下载失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream downloadFileRange(String objectKey, long startByte, long endByte) {
        ensureNotPrototype();

        try {
            String fullPath = resolveFullPath(objectKey);
            File file = new File(fullPath);

            if (!file.exists()) {
                throw new StorageOperationException("文件不存在: " + objectKey);
            }

            if (startByte < 0 || endByte < startByte) {
                throw new StorageOperationException("无效的字节范围: startByte=" + startByte + ", endByte=" + endByte);
            }

            if (startByte >= file.length()) {
                throw new StorageOperationException("起始字节超出文件大小: startByte=" + startByte + ", fileSize=" + file.length());
            }

            // 加密文件：换算为密文偏移（头 12 字节 + CTR 密文与明文等长），按块对齐后解密
            if (encryptionEnabled() && StreamCipherSupport.isEncryptedFile(file.toPath())) {
                return openEncryptedRange(file, startByte, endByte);
            }

            // 使用 RandomAccessFile 定位到起始字节
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            raf.seek(startByte);
            
            // 计算需要读取的字节数
            long length = Math.min(endByte - startByte + 1, file.length() - startByte);
            
            log.debug("{} Range读取文件: objectKey={}, startByte={}, endByte={}, length={}", 
                    getLogPrefix(), objectKey, startByte, endByte, length);
            
            // 返回限制长度的 InputStream
            return new BoundedFileInputStream(raf, length);
        } catch (IOException e) {
            throw new StorageOperationException("Range读取文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 加密文件的 Range 读取：头 12 字节 + 密文区按明文偏移定位解密
     */
    private InputStream openEncryptedRange(File file, long startByte, long endByte) throws IOException {
        long fileSize = file.length();
        if (fileSize <= StreamCipherSupport.HEADER_LENGTH) {
            return new ByteArrayInputStream(new byte[0]);
        }
        byte[] header = new byte[StreamCipherSupport.HEADER_LENGTH];
        try (DataInputStream headerIn = new DataInputStream(new BufferedInputStream(new FileInputStream(file), StreamCipherSupport.HEADER_LENGTH))) {
            headerIn.readFully(header);
        }
        long cipherLength = fileSize - StreamCipherSupport.HEADER_LENGTH;
        long plainStart = Math.min(startByte, cipherLength);
        long plainEndIncl = Math.min(endByte, cipherLength - 1);
        if (plainEndIncl < plainStart) {
            return new ByteArrayInputStream(new byte[0]);
        }
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        raf.seek(StreamCipherSupport.HEADER_LENGTH + plainStart);            long length = plainEndIncl - plainStart + 1;
        javax.crypto.Cipher cipher = StreamCipherSupport.newCipher(encryptionSecret, header, plainStart);
        return new CipherRangeInputStream(raf, length, cipher);
    }

    @Override
    public void deleteFile(String objectKey) {
        ensureNotPrototype();

        try {
            String fullPath = resolveFullPath(objectKey);
            File file = new File(fullPath);

            if (!file.exists()) {
                log.debug("{} 文件不存在，视为删除成功: objectKey={}", getLogPrefix(), objectKey);
            }

            boolean deleted = file.delete();
            if (!deleted) {
                log.error("{} 文件删除失败: objectKey={}", getLogPrefix(), objectKey);
                throw new StorageOperationException("文件删除失败: " + objectKey);
            }

            log.debug("{} 文件删除成功: objectKey={}", getLogPrefix(), objectKey);
        } catch (SecurityException e) {
            log.error("{} 文件删除失败（权限不足）: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("文件删除失败（权限不足）: " + objectKey, e);
        }
    }

    @Override
    public void rename(String objectKey, String newFileName) {

    }

    @Override
    public String getFileUrl(String objectKey, Integer expireSeconds) {
        ensureNotPrototype();
        String normalizedObjectKey = objectKey.startsWith("/")
                ? objectKey.substring(1)
                : objectKey;

        // Local 存储不做公开外链：文件访问一律走带鉴权的接口，这里仅返回相对 key
        return "/" + normalizedObjectKey;
    }

    @Override
    public InputStream getFileStream(String objectKey) {
        ensureNotPrototype();
        try {
            String fullPath = resolveFullPath(objectKey);
            File file = new File(fullPath);
            // 检查文件是否存在
            if (!file.exists()) {
                log.error("{} 文件不存在: objectKey={}, fullPath={}",
                        getLogPrefix(), objectKey, fullPath);
                throw new StorageOperationException("文件不存在: " + objectKey);
            }
            // 检查是否为文件（不是目录）
            if (!file.isFile()) {
                log.error("{} 路径不是文件: objectKey={}, fullPath={}",
                        getLogPrefix(), objectKey, fullPath);
                throw new StorageOperationException("路径不是文件: " + objectKey);
            }
            // 检查文件是否可读
            if (!file.canRead()) {
                log.error("{} 文件不可读: objectKey={}, fullPath={}",
                        getLogPrefix(), objectKey, fullPath);
                throw new StorageOperationException("文件不可读: " + objectKey);
            }
            log.debug("{} 获取文件流: objectKey={}, fileSize={} bytes",
                    getLogPrefix(), objectKey, file.length());
            InputStream raw = new FileInputStream(file);
            return encryptionEnabled() && StreamCipherSupport.isEncryptedFile(file.toPath())
                    ? StreamCipherSupport.decryptingInputStream(raw, encryptionSecret)
                    : raw;
        } catch (FileNotFoundException e) {
            log.error("{} 文件不存在: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("文件不存在: " + objectKey, e);
        } catch (SecurityException e) {
            log.error("{} 文件访问权限不足: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("文件访问权限不足: " + objectKey, e);
        } catch (Exception e) {
            log.error("{} 获取文件流失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("获取文件流失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isFileExist(String objectKey) {
        ensureNotPrototype();
        String fullPath = resolveFullPath(objectKey);
        Path path = Paths.get(fullPath).normalize();
        return Files.exists(path);
    }

    @Override
    public String initiateMultipartUpload(String objectKey, String mimeType) {
        ensureNotPrototype();
        try {
            // 生成唯一uploadId
            String uploadId = IdUtil.simpleUUID();
            //创建临时目录
            String tempDir = getTempDir(uploadId);
            File tempDirFile = new File(tempDir);
            if (!tempDirFile.exists()) {
                if (!tempDirFile.mkdirs()) {
                    throw new StorageOperationException("无法创建分片上传临时目录: " + tempDir);
                }
            }
            log.info("本地存储分片上传初始化成功: objectKey={}, uploadId={}, 存储目录={}", objectKey, uploadId, tempDir);
            return uploadId;
        } catch (Exception e) {
            throw new StorageOperationException("分片初始化失败: " + e.getMessage(), e);
        }

    }

    @Override
    public String uploadPart(String objectKey, String uploadId, int partNumber, long partSize, InputStream partInputStream) {
        ensureNotPrototype();

        try {
            // 构建分片文件路径
            String tempDir = getTempDir(uploadId);
            String partFilePath = tempDir + partNumber;

            // 确保临时目录存在
            File tempDirFile = new File(tempDir);
            if (!tempDirFile.exists()) {
                if (!tempDirFile.mkdirs()) {
                    throw new IOException("无法创建临时目录: " + tempDir);
                }
            }

            // 写入分片文件
            try (FileOutputStream fos = new FileOutputStream(partFilePath)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = partInputStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }

            // 生成分片标识（使用文件大小和修改时间）
            File partFile = new File(partFilePath);
            String etag = partFile.length() + "_" + partFile.lastModified();

            log.debug("本地存储分片上传成功: objectKey={}, partNumber={}, etag={}", objectKey, partNumber, etag);
            return etag;

        } catch (IOException e) {
            log.error("本地存储分片上传失败, objectKey={}, partNumber={}: {}", objectKey, partNumber, e.getMessage(), e);
            throw new StorageOperationException("本地存储分片上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Set<Integer> listParts(String objectKey, String uploadId) {
        ensureNotPrototype();

        Set<Integer> uploadedParts = new HashSet<>();
        String tempDir = getTempDir(uploadId);
        File dir = new File(tempDir);
        if (!dir.exists() || !dir.isDirectory()) {
            log.debug("{} 临时目录不存在: uploadId={}, tempDir={}",
                    getLogPrefix(), uploadId, tempDir);
            return uploadedParts;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                try {
                    // 文件名即为分片号
                    int partNumber = Integer.parseInt(file.getName());
                    uploadedParts.add(partNumber);
                } catch (NumberFormatException e) {
                    log.warn("{} 无效的分片文件名: {}", getLogPrefix(), file.getName());
                }
            }
        }
        log.debug("{} 已上传分片列表: uploadId={}, parts={}",
                getLogPrefix(), uploadId, uploadedParts);
        return uploadedParts;
    }

    @Override
    public void completeMultipartUpload(String objectKey, String uploadId, List<Map<String, Object>> partETags) {
        ensureNotPrototype();

        try {
            // 构建最终文件路径
            String fullPath = resolveFullPath(objectKey);
            File targetFile = new File(fullPath);

            // 确保父目录存在
            File parentDir = targetFile.getParentFile();
            if (parentDir != null) {
                // 并发合并同一天目录时 mkdirs 会因目录已被其他线程创建而误报失败，createDirectories 幂等
                Files.createDirectories(parentDir.toPath());
            }

            // 合并分片文件（开启加密时边合并边加密，分片临时文件保持明文）
            String tempDir = getTempDir(uploadId);
            // 未加密走 channel transferTo 零拷贝；加密时以流包装输出，分片照旧 transferTo 到缓冲再写
            OutputStream fos = encryptionEnabled()
                    ? StreamCipherSupport.encryptingOutputStream(new FileOutputStream(targetFile), encryptionSecret)
                    : new FileOutputStream(targetFile);
            FileChannel outChannel = encryptionEnabled() ? null : ((FileOutputStream) fos).getChannel();
            try (OutputStream fosCloseable = fos) {

                // 按分片号排序
                partETags.sort((a, b) -> {
                    int partNumA = (int) a.get("partNumber");
                    int partNumB = (int) b.get("partNumber");
                    return Integer.compare(partNumA, partNumB);
                });
                // 依次读取并合并分片
                for (Map<String, Object> partInfo : partETags) {
                    int partNumber = (int) partInfo.get("partNumber");
                    String partFilePath = tempDir + partNumber; // 使用分片号作为文件名
                    File partFile = new File(partFilePath);
                    if (!partFile.exists()) {
                        throw new StorageOperationException("分片文件不存在: " + partFilePath);
                    }
                    if (encryptionEnabled()) {
                        // 加密路径不能用 channel transferTo（需要经过 cipher），走流拷贝
                        try (InputStream fis = new BufferedInputStream(new FileInputStream(partFile), 65536)) {
                            fis.transferTo(fos);
                        }
                    } else {
                        try (FileInputStream fis = new FileInputStream(partFile);
                             FileChannel inChannel = fis.getChannel()) {
                            inChannel.transferTo(0, inChannel.size(), outChannel);
                        }
                    }
                }
            }

            // 清理临时目录
            FileUtil.del(tempDir);

            log.info("本地存储分片上传完成: objectKey={}, uploadId={}", objectKey, uploadId);
//            return getFileUrl(objectKey, null);

        } catch (IOException e) {
            log.error("本地存储分片上传完成失败, objectKey={}: {}", objectKey, e.getMessage(), e);
            throw new StorageOperationException("本地存储分片上传完成失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void abortMultipartUpload(String objectKey, String uploadId) {
        ensureNotPrototype();

        try {
            // 清理临时目录
            String tempDir = getTempDir(uploadId);
            FileUtil.del(tempDir);
            log.info("本地存储分片上传已中止: objectKey={}, uploadId={}", objectKey, uploadId);

        } catch (Exception e) {
            log.error("中止本地存储分片上传失败, objectKey={}: {}", objectKey, e.getMessage(), e);
            throw new StorageOperationException("中止本地存储分片上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Long getAvailableSpace() {
        if (basePath == null || basePath.isBlank()) {
            return null;
        }
        File baseDir = new File(basePath);
        // 目录不存在时 getUsableSpace() 同样返回 0，无法与「磁盘已满」区分，故按容量不可知处理
        if (!baseDir.exists()) {
            return null;
        }
        return baseDir.getUsableSpace();
    }

    /**
     * 获取临时目录路径
     */
    private String getTempDir(String uploadId) {
        return basePath + File.separator + "temp" + File.separator + uploadId + File.separator;
    }

    /**
     * 限制读取长度的 InputStream 包装类
     * 用于实现 Range 读取功能
     */
    private static class BoundedFileInputStream extends InputStream {
        private final RandomAccessFile raf;
        private long remaining;

        public BoundedFileInputStream(RandomAccessFile raf, long length) {
            this.raf = raf;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int result = raf.read();
            if (result != -1) {
                remaining--;
            }
            return result;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int toRead = (int) Math.min(len, remaining);
            int bytesRead = raf.read(b, off, toRead);
            if (bytesRead > 0) {
                remaining -= bytesRead;
            }
            return bytesRead;
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(remaining, Integer.MAX_VALUE);
        }
    }

    /**
     * 加密文件 Range 读取流：从 RandomAccessFile 读密文并用定位好的 CTR cipher 解密，
     * 读完指定长度后自动关闭底层文件句柄
     */
    private static class CipherRangeInputStream extends InputStream {
        private final RandomAccessFile raf;
        private long remaining;
        private final javax.crypto.Cipher cipher;
        private final byte[] single = new byte[1];

        CipherRangeInputStream(RandomAccessFile raf, long length, javax.crypto.Cipher cipher) {
            this.raf = raf;
            this.remaining = length;
            this.cipher = cipher;
        }

        @Override
        public int read() throws IOException {
            int n = read(single, 0, 1);
            return n == -1 ? -1 : (single[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                close();
                return -1;
            }
            int toRead = (int) Math.min(len, remaining);
            int bytesRead = raf.read(b, off, toRead);
            if (bytesRead <= 0) {
                close();
                return -1;
            }
            remaining -= bytesRead;
            byte[] dec = cipher.update(b, off, bytesRead);
            if (dec != null && dec != b) {
                System.arraycopy(dec, 0, b, off, dec.length);
            }
            return bytesRead;
        }

        @Override
        public void close() throws IOException {
            remaining = 0;
            raf.close();
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(remaining, Integer.MAX_VALUE);
        }
    }
}
