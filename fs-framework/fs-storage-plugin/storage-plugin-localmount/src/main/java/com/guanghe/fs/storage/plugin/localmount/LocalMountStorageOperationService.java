package com.guanghe.fs.storage.plugin.localmount;

import com.guanghe.fs.framework.common.exception.StorageConfigException;
import com.guanghe.fs.framework.common.exception.StorageOperationException;
import com.guanghe.fs.storage.plugin.core.annotation.StoragePlugin;
import com.guanghe.fs.storage.plugin.core.chunk.AbstractTempChunkStorageService;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import com.guanghe.fs.storage.plugin.core.crypto.StreamCipherSupport;
import com.guanghe.fs.storage.plugin.core.model.StorageObjectEntry;
import com.guanghe.fs.storage.plugin.localmount.config.LocalMountConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地目录挂载插件（读写）
 * <p>
 * 目录树镜像真实文件系统：DB 中文件记录的 object_key = 相对挂载根的真实相对路径（posix），
 * GFS 内写操作穿透真实 FS，外部改动靠扫描同步回 DB。
 * <p>
 * 安全红线：
 * 1. resolveFullPath 必须校验解析后的路径仍在挂载根内（防路径逃逸），Windows 反斜杠输入一律拒绝；
 * 2. 默认跳过 symlink（目录与文件都跳），防循环；
 * 3. 不参与秒传/合并后二次去重（md5 恒为 NULL），业务侧已保证。
 *
 * @Author: guangheUlti
 * @Date: 2026/09/09
 */
@Slf4j
@StoragePlugin(
        identifier = "LocalMount",
        name = "本地目录挂载",
        description = "把服务器本地真实目录挂进网盘：目录结构与真实文件系统一一对应，网盘内的增删改直接作用于真实文件，外部改动可一键重新扫描同步。",
        icon = "icon-bendicunchu1",
        schemaResource = "classpath:schema/localmount-schema.json"
)
public class LocalMountStorageOperationService extends AbstractTempChunkStorageService {

    private Path rootPath;
    private boolean followSymlinks;

    /** 落盘加密口令（encryptionEnabled=true 时非空） */
    private String encryptionSecret;

    public LocalMountStorageOperationService() {
        super();
    }

    public LocalMountStorageOperationService(StorageConfig config) {
        super(config);
    }

    @Override
    protected void validateConfig(StorageConfig config) {
        LocalMountConfig cfg = readConfig(config);
        if (cfg.getRootPath() == null || cfg.getRootPath().trim().isEmpty()) {
            throw new StorageConfigException("本地目录挂载配置错误：挂载根路径不能为空");
        }
        // Windows 反斜杠路径直接拒绝（根路径应写成正斜杠形式，如 D:/mnt-test）
        if (cfg.getRootPath().trim().contains("\\")) {
            throw new StorageConfigException("本地目录挂载配置错误：根路径请使用正斜杠（如 D:/mnt-test）");
        }
        // 开启加密时口令必填（与 Local 插件同规则）
        if (cfg.isEncryptionEnabled()) {
            String secret = cfg.getEncryptionSecret();
            if (secret == null || secret.trim().isEmpty()) {
                throw new StorageConfigException("本地目录挂载配置错误：开启落盘加密后必须设置密钥（encryptionSecret）");
            }
        }
    }

    @Override
    protected void initialize(StorageConfig config) {
        LocalMountConfig cfg = readConfig(config);
        // 真实探测挂载根（P3 连接测试的落点）：必须存在且为目录，否则拒绝保存
        Path raw = Paths.get(cfg.getRootPath().trim());
        if (!Files.isDirectory(raw, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageConfigException("本地目录挂载配置错误：根路径不存在或不是目录: " + cfg.getRootPath().trim());
        }
        try {
            // 统一使用真实路径（解析大小写/符号链接差异），便于后续逃逸校验
            this.rootPath = raw.toRealPath();
        } catch (IOException e) {
            throw new StorageConfigException("本地目录挂载配置错误：根路径无法解析: " + e.getMessage());
        }
        this.followSymlinks = "true".equalsIgnoreCase(cfg.getFollowSymlinks());
        this.encryptionSecret = cfg.isEncryptionEnabled() ? cfg.getEncryptionSecret().trim() : null;
        log.info("{} 本地目录挂载初始化完成: rootPath={}, followSymlinks={}, encryption={}",
                getLogPrefix(), rootPath, followSymlinks, encryptionSecret != null ? "AES-CTR 开启" : "关闭");
    }

    public Path getRootPath() {
        return rootPath;
    }

    /**
     * 解析相对键为真实绝对路径
     * 必须校验：normalize 后仍以 rootPath 开头（防路径逃逸）
     */
    Path resolveFullPath(String key) {
        ensureNotPrototype();
        String rel = normalizeKey(key);
        Path resolved;
        if (rel.isEmpty()) {
            resolved = rootPath;
        } else {
            // 键内不允许出现反斜杠（挂载键规范强制 posix）
            if (rel.contains("\\")) {
                throw new StorageOperationException("非法的挂载路径: " + rel);
            }
            resolved = rootPath.resolve(rel).normalize();
        }
        if (!resolved.startsWith(rootPath)) {
            throw new StorageOperationException("路径越界（超出挂载根）: " + rel);
        }
        return resolved;
    }

    @Override
    public void uploadFile(InputStream inputStream, String objectKey) {
        ensureNotPrototype();
        try {
            Path target = resolveFullPath(objectKey);
            Path parent = target.getParent();
            if (parent != null) {
                // 幂等创建，禁止 exists()+mkdirs()（并发竞态）
                Files.createDirectories(parent);
            }
            try (OutputStream os = encryptionSecret != null
                    ? StreamCipherSupport.encryptingOutputStream(Files.newOutputStream(target,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE), encryptionSecret)
                    : Files.newOutputStream(target,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                inputStream.transferTo(os);
            }
            log.debug("{} 文件上传成功: objectKey={}", getLogPrefix(), objectKey);
        } catch (IOException e) {
            log.error("{} 文件上传失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("挂载目录写入失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream downloadFile(String objectKey) {
        ensureNotPrototype();
        try {
            Path target = resolveFullPath(objectKey);
            if (!Files.isRegularFile(target)) {
                throw new StorageOperationException("文件不存在: " + objectKey);
            }
            InputStream raw = new BufferedInputStream(Files.newInputStream(target, StandardOpenOption.READ), 65536);
            return encryptionSecret != null && StreamCipherSupport.isEncryptedFile(target)
                    ? StreamCipherSupport.decryptingInputStream(raw, encryptionSecret)
                    : raw;
        } catch (IOException e) {
            log.error("{} 文件下载失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("挂载目录读取失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream downloadFileRange(String objectKey, long startByte, long endByte) {
        ensureNotPrototype();
        if (startByte < 0 || endByte < startByte) {
            throw new StorageOperationException("无效的字节范围: startByte=" + startByte + ", endByte=" + endByte);
        }
        try {
            Path target = resolveFullPath(objectKey);
            if (!Files.isRegularFile(target)) {
                throw new StorageOperationException("文件不存在: " + objectKey);
            }
            long fileSize = Files.size(target);
            if (startByte >= fileSize) {
                throw new StorageOperationException("起始字节超出文件大小: startByte=" + startByte + ", fileSize=" + fileSize);
            }
            long length = Math.min(endByte - startByte + 1, fileSize - startByte);
            // 加密文件：换算为密文偏移（头 12 字节 + CTR 密文与明文等长），按块对齐后解密
            if (encryptionSecret != null && StreamCipherSupport.isEncryptedFile(target)) {
                return openEncryptedRange(target, startByte, endByte);
            }
            RandomAccessFile raf = new RandomAccessFile(target.toFile(), "r");
            raf.seek(startByte);
            log.debug("{} Range读取文件: objectKey={}, start={}, length={}", getLogPrefix(), objectKey, startByte, length);
            return new BoundedFileInputStream(raf, length);
        } catch (IOException e) {
            log.error("{} Range读取文件失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("挂载目录读取失败: " + e.getMessage(), e);
        }
    }

    /**
     * 加密文件的 Range 读取：头 12 字节 + 密文区按明文偏移定位解密（与 Local 插件同规则）
     */
    private InputStream openEncryptedRange(Path target, long startByte, long endByte) throws IOException {
        long fileSize = Files.size(target);
        if (fileSize <= StreamCipherSupport.HEADER_LENGTH) {
            return new java.io.ByteArrayInputStream(new byte[0]);
        }
        byte[] header = new byte[StreamCipherSupport.HEADER_LENGTH];
        try (java.io.DataInputStream headerIn = new java.io.DataInputStream(
                new java.io.BufferedInputStream(Files.newInputStream(target, StandardOpenOption.READ), StreamCipherSupport.HEADER_LENGTH))) {
            headerIn.readFully(header);
        }
        long cipherLength = fileSize - StreamCipherSupport.HEADER_LENGTH;
        long plainStart = Math.min(startByte, cipherLength);
        long plainEndIncl = Math.min(endByte, cipherLength - 1);
        if (plainEndIncl < plainStart) {
            return new java.io.ByteArrayInputStream(new byte[0]);
        }
        RandomAccessFile raf = new RandomAccessFile(target.toFile(), "r");
        raf.seek(StreamCipherSupport.HEADER_LENGTH + plainStart);
        long length = plainEndIncl - plainStart + 1;
        return new CipherRangeInputStream(raf, length,
                StreamCipherSupport.newCipher(encryptionSecret, header, plainStart));
    }

    @Override
    public void deleteFile(String objectKey) {
        ensureNotPrototype();
        try {
            Path target = resolveFullPath(objectKey);
            // 幂等：不存在视为已删除
            Files.deleteIfExists(target);
            log.debug("{} 文件删除成功: objectKey={}", getLogPrefix(), objectKey);
        } catch (IOException e) {
            log.error("{} 文件删除失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("挂载目录删除失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void rename(String objectKey, String destObjectKey) {
        ensureNotPrototype();
        try {
            Path source = resolveFullPath(objectKey);
            Path target = resolveFullPath(destObjectKey);
            if (!Files.exists(source)) {
                throw new StorageOperationException("源文件不存在: " + objectKey);
            }
            Path targetParent = target.getParent();
            if (targetParent != null) {
                Files.createDirectories(targetParent);
            }
            // 不开 REPLACE_EXISTING：同名目标应报错
            Files.move(source, target);
            log.debug("{} 重命名成功: {} -> {}", getLogPrefix(), objectKey, destObjectKey);
        } catch (IOException e) {
            log.error("{} 重命名失败: {} -> {}", getLogPrefix(), objectKey, destObjectKey, e);
            throw new StorageOperationException("挂载目录重命名失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String getFileUrl(String objectKey, Integer expireSeconds) {
        throw new StorageOperationException("本地目录挂载不支持生成公网直链");
    }

    @Override
    public InputStream getFileStream(String objectKey) {
        return downloadFile(objectKey);
    }

    @Override
    public boolean isFileExist(String objectKey) {
        ensureNotPrototype();
        return Files.exists(resolveFullPath(objectKey));
    }

    @Override
    public boolean isMountMode() {
        return true;
    }

    @Override
    public boolean isEncryptionEnabled() {
        ensureNotPrototype();
        return encryptionSecret != null;
    }

    @Override
    public void mkdirDirectory(String dirKey) {
        ensureNotPrototype();
        try {
            Path target = resolveFullPath(dirKey);
            Files.createDirectories(target);
            log.debug("{} 创建目录成功: dirKey={}", getLogPrefix(), dirKey);
        } catch (IOException e) {
            log.error("{} 创建目录失败: dirKey={}", getLogPrefix(), dirKey, e);
            throw new StorageOperationException("挂载目录创建失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<StorageObjectEntry> listObjects(String dirKey) {
        ensureNotPrototype();
        List<StorageObjectEntry> entries = new ArrayList<>();
        String parentKey = normalizeKey(dirKey);
        Path dir = resolveFullPath(parentKey);
        if (!Files.isDirectory(dir)) {
            throw new StorageOperationException("目录不存在: " + parentKey);
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                boolean isSymlink = Files.isSymbolicLink(child);
                // 未开启 followSymlinks 时 symlink 一律跳过（防循环）
                if (isSymlink && !followSymlinks) {
                    continue;
                }
                String name = child.getFileName().toString();
                boolean isDir;
                long size = 0L;
                Long lastModified = null;
                try {
                    if (isSymlink) {
                        // 开启 followSymlinks 时按链接目标类型归类，不深入解析
                        isDir = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                                || Files.isDirectory(child);
                    } else {
                        isDir = Files.isDirectory(child);
                    }
                    if (!isDir) {
                        size = Files.size(child);
                    }
                    lastModified = Files.getLastModifiedTime(child).toMillis();
                } catch (IOException e) {
                    // 竞态中条目被外部删除：跳过
                    continue;
                }
                String key = parentKey.isEmpty() ? name : parentKey + "/" + name;
                entries.add(new StorageObjectEntry(key, isDir, isDir ? null : size, lastModified));
            }
        } catch (IOException e) {
            log.error("{} 列举目录失败: dirKey={}", getLogPrefix(), dirKey, e);
            throw new StorageOperationException("挂载目录列举失败: " + e.getMessage(), e);
        }
        return entries;
    }

    @Override
    public void deleteDirectory(String dirKey) {
        ensureNotPrototype();
        try {
            Path target = resolveFullPath(dirKey);
            if (!Files.exists(target)) {
                return; // 幂等
            }
            if (!Files.isDirectory(target)) {
                // 不是目录按文件删
                Files.deleteIfExists(target);
                return;
            }
            // Files.walk 逆序删（先子后父）
            try (Stream<Path> walk = Files.walk(target)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        throw new StorageOperationException("删除失败: " + p + " - " + e.getMessage(), e);
                    }
                });
            }
            log.info("{} 目录删除成功: dirKey={}", getLogPrefix(), dirKey);
        } catch (IOException e) {
            log.error("{} 删除目录失败: dirKey={}", getLogPrefix(), dirKey, e);
            throw new StorageOperationException("挂载目录删除失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Long getAvailableSpace() {
        ensureNotPrototype();
        try {
            FileStore store = Files.getFileStore(rootPath);
            // 与 Local 插件口径一致：返回剩余可写字节数
            return store.getUsableSpace();
        } catch (Exception e) {
            log.warn("{} 获取剩余容量失败: {}", getLogPrefix(), e.getMessage());
            return null;
        }
    }

    @Override
    public void close() {
        // 无需释放资源
    }

    /**
     * 分片临时根目录：分片全在挂载根之外（8.4-①，temp 目录不属于挂载子树，扫描不会看到）。
     * 与文档 8.2 "不继承 temp 基类"的偏差说明：SPI 的 5 个分片方法是抽象方法，
     * 复用基类实现既满足接口又天然满足 ①（分片在 temp）与 ②（complete 写真实路径）边界。
     */
    @Override
    protected String getTempRoot() {
        return resolveTempRoot(null, "localmount");
    }

    /**
     * 合并完成后写真实挂载路径（8.4-②：与业务侧 MountLocks 配合串行化）；
     * 开启加密时先把合并产物转为密文再落盘（分片与 merged 临时文件保持明文）
     */
    @Override
    protected void writeMerged(Path mergedFile, String objectKey) {
        Path target = resolveFullPath(objectKey);
        if (Files.isDirectory(target)) {
            throw new StorageOperationException("目标路径已是目录: " + objectKey);
        }
        try {
            Files.createDirectories(target.getParent());
            if (encryptionSecret != null) {
                try (InputStream in = new BufferedInputStream(Files.newInputStream(mergedFile, StandardOpenOption.READ), 65536);
                     OutputStream out = StreamCipherSupport.encryptingOutputStream(Files.newOutputStream(target,
                             StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE), encryptionSecret)) {
                    in.transferTo(out);
                }
            } else {
                Files.move(mergedFile, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("{} 分片合并写入真实路径完成: objectKey={}", getLogPrefix(), objectKey);
        } catch (IOException e) {
            throw new StorageOperationException("合并写入真实路径失败: " + objectKey + ", " + e.getMessage(), e);
        }
    }

    private LocalMountConfig readConfig(StorageConfig config) {
        try {
            return LocalMountConfig.toObject(config);
        } catch (Exception e) {
            throw new StorageConfigException("本地目录挂载配置解析失败: " + e.getMessage());
        }
    }

    static String normalizeKey(String objectKey) {
        String key = objectKey == null ? "" : objectKey.trim();
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        while (key.endsWith("/")) {
            key = key.substring(0, key.length() - 1);
        }
        return key;
    }

    /**
     * 限制读取长度的 InputStream 包装（对齐 Local 插件的 Range 实现方式）
     */
    private static class BoundedFileInputStream extends InputStream {
        private final RandomAccessFile raf;
        private long remaining;

        BoundedFileInputStream(RandomAccessFile raf, long length) {
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
     * 加密文件 Range 读取流（与 Local 插件同实现）：读密文并按定位好的 CTR cipher 解密，
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
