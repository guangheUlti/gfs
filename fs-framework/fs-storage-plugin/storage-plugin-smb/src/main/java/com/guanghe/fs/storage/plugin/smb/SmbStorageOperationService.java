package com.guanghe.fs.storage.plugin.smb;

import com.guanghe.fs.framework.common.exception.StorageConfigException;
import com.guanghe.fs.framework.common.exception.StorageOperationException;
import com.guanghe.fs.storage.plugin.core.annotation.StoragePlugin;
import com.guanghe.fs.storage.plugin.core.chunk.AbstractTempChunkStorageService;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import com.guanghe.fs.storage.plugin.core.model.StorageObjectEntry;
import com.guanghe.fs.storage.plugin.smb.config.SmbConfig;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.protocol.commons.EnumWithValue;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * SMB/CIFS 网络共享存储插件（基于 smbj）
 * <p>
 * 纯对象式存储（同 Local/Minio 模式）：分片先落本地 temp，complete 时合并写远程。
 * SMBClient 实例线程安全可跨线程共享。连接模型：实例内维持一条常驻 Connection/Session/DiskShare
 * （NAS 普遍限制单账号会话数，若每次操作新建会话则只增不销，堆满后 NAS 以 STATUS_REQUEST_NOT_ACCEPTED 拒绝认证），
 * 连接失效时自动整链重建，close() 时统一释放。
 *
 * @Author: guangheUlti
 * @Date: 2026/09/09
 */
@Slf4j
@StoragePlugin(
        identifier = "Smb",
        name = "SMB网络存储",
        description = "通过 SMB/CIFS 协议接入局域网共享目录（Windows 共享、Samba 等），适合家庭 NAS 与内网文件服务器场景。",
        icon = "icon-bendicunchu1",
        schemaResource = "classpath:schema/smb-schema.json"
)
public class SmbStorageOperationService extends AbstractTempChunkStorageService {

    private SMBClient client;
    /** 常驻连接/会话/共享：首次使用建立，失效自动重建，close 时统一释放 */
    private Connection connection;
    private Session session;
    private DiskShare diskShare;
    private String host;
    private int port;
    private String domain;
    private String share;
    private String username;
    private String password;
    private String tempRoot;

    public SmbStorageOperationService() {
        super();
    }

    public SmbStorageOperationService(StorageConfig config) {
        super(config);
    }

    @Override
    protected String getTempRoot() {
        return tempRoot;
    }

    @Override
    protected void validateConfig(StorageConfig config) {
        SmbConfig cfg = readConfig(config);
        if (isBlank(cfg.getSmbHost())) {
            throw new StorageConfigException("SMB 配置错误：服务器地址不能为空");
        }
        if (isBlank(cfg.getSmbShare())) {
            throw new StorageConfigException("SMB 配置错误：共享名不能为空");
        }
        if (isBlank(cfg.getSmbUsername())) {
            throw new StorageConfigException("SMB 配置错误：用户名不能为空");
        }
        if (isBlank(cfg.getSmbPassword())) {
            throw new StorageConfigException("SMB 配置错误：密码不能为空");
        }
        String port = cfg.getSmbPort();
        if (!isBlank(port)) {
            try {
                int p = Integer.parseInt(port.trim());
                if (p <= 0 || p > 65535) {
                    throw new StorageConfigException("SMB 配置错误：端口必须在 1-65535 之间");
                }
            } catch (NumberFormatException e) {
                throw new StorageConfigException("SMB 配置错误：端口必须是数字");
            }
        }
    }

    @Override
    protected void initialize(StorageConfig config) {
        // 真实建连（P3 连接测试的落点）：TCP 连接 + SMB 会话认证 + 打开共享
        SmbConfig cfg = readConfig(config);
        this.host = cfg.getSmbHost().trim();
        this.port = isBlank(cfg.getSmbPort()) ? 445 : Integer.parseInt(cfg.getSmbPort().trim());
        this.domain = isBlank(cfg.getSmbDomain()) ? null : cfg.getSmbDomain().trim();
        this.share = cfg.getSmbShare().trim();
        this.username = cfg.getSmbUsername().trim();
        this.password = cfg.getSmbPassword();
        this.tempRoot = resolveTempRoot(cfg.getTempPath(), "smb");

        this.client = new SMBClient();
        try {
            // 建立并保持常驻连接/会话/共享（即连通性验证，后续操作直接复用）
            openShare();
            log.info("{} SMB 连接建立成功（常驻会话）: {}:{}/{}", getLogPrefix(), host, port, share);
        } catch (Exception e) {
            closeQuietly();
            throw new StorageConfigException("SMB 连接失败: " + rootMessage(e));
        }
    }

    private Session authenticate(Connection connection) throws IOException {
        AuthenticationContext authContext = domain != null
                ? new AuthenticationContext(username, password.toCharArray(), domain)
                : new AuthenticationContext(username, password.toCharArray(), "");
        return connection.authenticate(authContext);
    }

    /**
     * 获取常驻共享：已连接直接复用；失效/断开则在锁内整链重建 Connection→Session→Share。
     * 所有短操作（列举/上传/删除/重命名等）共用这一条连接，不再每次操作新建会话。
     */
    private DiskShare openShare() {
        // synchronized 而非 ReentrantLock：插件实例可能由框架以不执行字段初始化器的方式创建
        synchronized (this) {
            if (diskShare != null) {
                try {
                    if (diskShare.isConnected()) {
                        return diskShare;
                    }
                } catch (Exception ignored) {
                }
                log.warn("{} SMB 常驻连接失效，重建连接", getLogPrefix());
                closeShareTree();
            }
            try {
                if (client == null) {
                    client = new SMBClient();
                }
                connection = client.connect(host, port);
                session = authenticate(connection);
                diskShare = (DiskShare) session.connectShare(share);
                return diskShare;
            } catch (Exception e) {
                closeShareTree();
                throw new StorageOperationException("SMB 连接失败: " + rootMessage(e), e);
            }
        }
    }

    /**
     * 兼容占位：常驻模型下操作结束不再关闭共享（实例 close/重连时统一释放）。
     */
    private void closeShare(DiskShare ignored) {
        // no-op
    }

    /** 释放常驻连接/会话/共享整链（幂等） */
    private void closeShareTree() {
        diskShare = null;
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
            session = null;
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
            }
            connection = null;
        }
    }

    /** 将 posix objectKey 转为 SMB 路径（'\\' 分隔、无前导 '/'），空 key 表示共享根 */
    private String smbPath(String objectKey) {
        String key = normalizeKey(objectKey);
        return key.replace('/', '\\');
    }

    @Override
    public void uploadFile(InputStream inputStream, String objectKey) {
        ensureNotPrototype();
        DiskShare diskShare = openShare();
        try {
            String path = smbPath(objectKey);
            ensureParentDir(diskShare, path);
            try (com.hierynomus.smbj.share.File remoteFile = diskShare.openFile(
                    path,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    EnumSet.of(SMB2CreateOptions.FILE_SYNCHRONOUS_IO_ALERT));
                 OutputStream os = remoteFile.getOutputStream()) {
                inputStream.transferTo(os);
            }
            log.debug("{} 文件上传成功: objectKey={}", getLogPrefix(), objectKey);
        } catch (StorageOperationException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} 文件上传失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("SMB 文件上传失败: " + rootMessage(e), e);
        } finally {
            closeShare(diskShare);
        }
    }

    @Override
    public InputStream downloadFile(String objectKey) {
        ensureNotPrototype();
        return downloadFileRange(objectKey, 0, Long.MAX_VALUE - 1);
    }

    @Override
    public InputStream downloadFileRange(String objectKey, long startByte, long endByte) {
        ensureNotPrototype();
        if (startByte < 0 || endByte < startByte) {
            throw new StorageOperationException("无效的字节范围: startByte=" + startByte + ", endByte=" + endByte);
        }
        DiskShare diskShare = openShare();
        try {
            String path = smbPath(objectKey);
            if (!diskShare.fileExists(path)) {
                closeShare(diskShare);
                throw new StorageOperationException("文件不存在: " + objectKey);
            }
            long length = endByte - startByte + 1;
            com.hierynomus.smbj.share.File remoteFile = diskShare.openFile(
                    path,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            );
            // getInputStream() 从文件头开始读；流关闭时关闭远程文件句柄（常驻共享不受影响）
            return new SmbRangeInputStream(remoteFile,
                    remoteFile.getInputStream(), startByte, length);
        } catch (StorageOperationException e) {
            closeShare(diskShare);
            throw e;
        } catch (Exception e) {
            closeShare(diskShare);
            log.error("{} Range读取文件失败: objectKey={}, start={}, end={}",
                    getLogPrefix(), objectKey, startByte, endByte, e);
            throw new StorageOperationException("SMB 读取文件失败: " + rootMessage(e), e);
        }
    }

    @Override
    public void deleteFile(String objectKey) {
        ensureNotPrototype();
        DiskShare diskShare = openShare();
        try {
            String path = smbPath(objectKey);
            if (diskShare.fileExists(path)) {
                diskShare.rm(path);
            } else {
                log.debug("{} 文件不存在，视为删除成功: objectKey={}", getLogPrefix(), objectKey);
            }
        } catch (Exception e) {
            log.error("{} 文件删除失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("SMB 文件删除失败: " + rootMessage(e), e);
        } finally {
            closeShare(diskShare);
        }
    }

    @Override
    public void rename(String objectKey, String destObjectKey) {
        ensureNotPrototype();
        DiskShare diskShare = openShare();
        try {
            String source = smbPath(objectKey);
            String target = smbPath(destObjectKey);
            ensureParentDir(diskShare, target);
            // 文件/目录通吃：目录用 FILE_DIRECTORY_FILE 打开
            boolean isDir = diskShare.folderExists(source);
            com.hierynomus.smbj.share.File file = diskShare.openFile(
                    source,
                    EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES),
                    EnumSet.of(isDir
                            ? FileAttributes.FILE_ATTRIBUTE_DIRECTORY
                            : FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(isDir
                            ? SMB2CreateOptions.FILE_DIRECTORY_FILE
                            : SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            );
            try {
                // rename(newName, replaceIfExist)：newName 为文件名字面量
                file.rename(smbFileName(target), true);
            } finally {
                file.close();
            }
            log.debug("{} 重命名成功: {} -> {}", getLogPrefix(), objectKey, destObjectKey);
        } catch (Exception e) {
            log.error("{} 重命名失败: {} -> {}", getLogPrefix(), objectKey, destObjectKey, e);
            throw new StorageOperationException("SMB 重命名失败: " + rootMessage(e), e);
        } finally {
            closeShare(diskShare);
        }
    }

    @Override
    public String getFileUrl(String objectKey, Integer expireSeconds) {
        throw new StorageOperationException("SMB 存储不支持生成公网直链");
    }

    @Override
    public InputStream getFileStream(String objectKey) {
        return downloadFile(objectKey);
    }

    @Override
    public boolean isFileExist(String objectKey) {
        ensureNotPrototype();
        DiskShare diskShare = openShare();
        try {
            String path = smbPath(objectKey);
            return diskShare.fileExists(path) || diskShare.folderExists(path);
        } catch (Exception e) {
            log.error("{} 检查文件存在失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("SMB 检查文件存在失败: " + rootMessage(e), e);
        } finally {
            closeShare(diskShare);
        }
    }

    @Override
    public void mkdirDirectory(String dirKey) {
        ensureNotPrototype();
        DiskShare diskShare = openShare();
        try {
            smbCreateDirs(diskShare, smbPath(dirKey));
        } catch (StorageOperationException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} 创建目录失败: dirKey={}", getLogPrefix(), dirKey, e);
            throw new StorageOperationException("SMB 创建目录失败: " + rootMessage(e), e);
        } finally {
            closeShare(diskShare);
        }
    }

    @Override
    public boolean isMountMode() {
        // SMB 共享是目录树镜像（共享名即挂载根），与 LocalMount 同为挂载式存储：
        // 文件列表走挂载点懒创建 + MountScanService 同步，而非上传式 object_key 索引
        return true;
    }

    @Override
    public List<StorageObjectEntry> listObjects(String dirKey) {
        ensureNotPrototype();
        DiskShare diskShare = openShare();
        try {
            String dir = smbPath(dirKey);
            List<StorageObjectEntry> entries = new ArrayList<>();
            // list(path) 只返回该目录一层内的条目，文件名仅为条目名，需自行拼接相对键
            List<FileIdBothDirectoryInformation> list = dir.isEmpty()
                    ? diskShare.list("")
                    : diskShare.list(dir);
            for (FileIdBothDirectoryInformation info : list) {
                String name = info.getFileName();
                // 过滤 "." / ".."
                if (name == null || name.equals(".") || name.equals("..")) {
                    continue;
                }
                boolean isDir = EnumWithValue.EnumUtils.isSet(
                        info.getFileAttributes(), FileAttributes.FILE_ATTRIBUTE_DIRECTORY);
                String childKey = dir.isEmpty() ? name : dir.replace('\\', '/') + "/" + name;
                Long size = isDir ? null : info.getEndOfFile();
                Long lastModified = info.getLastWriteTime() == null
                        ? null
                        : info.getLastWriteTime().toEpochMillis();
                entries.add(new StorageObjectEntry(childKey, isDir, size, lastModified));
            }
            return entries;
        } catch (Exception e) {
            log.error("{} 列举目录失败: dirKey={}", getLogPrefix(), dirKey, e);
            throw new StorageOperationException("SMB 列举目录失败: " + rootMessage(e), e);
        } finally {
            closeShare(diskShare);
        }
    }

    @Override
    public void deleteDirectory(String dirKey) {
        ensureNotPrototype();
        DiskShare diskShare = openShare();
        try {
            String path = smbPath(dirKey);
            // rmdir(file=true) 递归删除；目录不存在时幂等成功
            if (diskShare.folderExists(path)) {
                diskShare.rmdir(path, true);
            }
        } catch (Exception e) {
            log.error("{} 删除目录失败: dirKey={}", getLogPrefix(), dirKey, e);
            throw new StorageOperationException("SMB 删除目录失败: " + rootMessage(e), e);
        } finally {
            closeShare(diskShare);
        }
    }

    @Override
    public void writeMerged(java.nio.file.Path mergedFile, String objectKey) {
        // complete 阶段：将合并后的本地临时文件写入远程
        ensureNotPrototype();
        DiskShare diskShare = openShare();
        try {
            String path = smbPath(objectKey);
            ensureParentDir(diskShare, path);
            try (com.hierynomus.smbj.share.File remoteFile = diskShare.openFile(
                    path,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    EnumSet.of(SMB2CreateOptions.FILE_SYNCHRONOUS_IO_ALERT));
                 OutputStream os = remoteFile.getOutputStream();
                 InputStream in = java.nio.file.Files.newInputStream(mergedFile)) {
                in.transferTo(os);
            }
        } catch (Exception e) {
            log.error("{} 合并文件写入远程失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("SMB 合并文件写入失败: " + rootMessage(e), e);
        } finally {
            closeShare(diskShare);
        }
    }

    @Override
    public Long getAvailableSpace() {
        ensureNotPrototype();
        DiskShare diskShare = openShare();
        try {
            // ShareInfo = FSCTL_QUERY_FS_SIZE_INFORMATION，返回共享盘容量信息
            return diskShare.getShareInformation().getCallerFreeSpace();
        } catch (Exception e) {
            log.warn("{} 获取剩余容量失败: {}", getLogPrefix(), e.getMessage());
            return null;
        } finally {
            closeShare(diskShare);
        }
    }

    @Override
    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        closeShareTree();
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("{} 关闭 SMB 客户端失败: {}", getLogPrefix(), e.getMessage());
            }
            client = null;
        }
    }

    /** 确保目标文件的父目录存在（逐级创建，并发下以判存兜底） */
    private void ensureParentDir(DiskShare diskShare, String path) {
        int idx = path.lastIndexOf('\\');
        if (idx <= 0) {
            return; // 根下直接放文件
        }
        smbCreateDirs(diskShare, path.substring(0, idx));
    }

    /** 逐级创建目录链（mkdir 对已存在目录抛异常，先判存） */
    private void smbCreateDirs(DiskShare diskShare, String path) {
        StringBuilder current = new StringBuilder();
        for (String segment : path.split("\\\\")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (current.length() > 0) {
                current.append('\\');
            }
            current.append(segment);
            String dir = current.toString();
            if (!diskShare.folderExists(dir)) {
                try {
                    diskShare.mkdir(dir);
                } catch (Exception e) {
                    // 并发下目录可能已被其它请求创建，判存兜底
                    if (!diskShare.folderExists(dir)) {
                        throw e;
                    }
                }
            }
        }
    }

    private String smbFileName(String path) {
        int idx = path.lastIndexOf('\\');
        return idx < 0 ? path : path.substring(idx + 1);
    }

    private SmbConfig readConfig(StorageConfig config) {
        try {
            return SmbConfig.toObject(config);
        } catch (Exception e) {
            throw new StorageConfigException("SMB 配置解析失败: " + e.getMessage());
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

    static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : cur.getClass().getSimpleName();
    }

    /**
     * SMB Range 读取流：从远程文件指定偏移读取指定长度，close 时仅关闭远程文件句柄
     * （共享为实例级常驻连接，不能随流关闭）
     */
    private static class SmbRangeInputStream extends InputStream {
        private final com.hierynomus.smbj.share.File remoteFile;
        private final InputStream inner;
        private long toSkip;
        private long remaining;

        SmbRangeInputStream(com.hierynomus.smbj.share.File remoteFile,
                            InputStream inner, long offset, long length) {
            this.remoteFile = remoteFile;
            this.inner = new BufferedInputStream(inner, 65536);
            this.toSkip = Math.max(0, offset);
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            skipToOffset();
            int toRead = (int) Math.min(len, remaining);
            int n = inner.read(b, off, toRead);
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        /** 首次读取时跳到起始偏移 */
        private void skipToOffset() throws IOException {
            while (toSkip > 0) {
                long n = inner.skip(toSkip);
                if (n <= 0) {
                    break;
                }
                toSkip -= n;
            }
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(remaining, Integer.MAX_VALUE);
        }

        @Override
        public void close() throws IOException {
            try {
                inner.close();
            } catch (Exception ignored) {
            }
            try {
                remoteFile.close();
            } catch (Exception ignored) {
            }
        }
    }
}
