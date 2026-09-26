package com.guanghe.fs.storage.plugin.sftp;

import com.guanghe.fs.framework.common.exception.StorageConfigException;
import com.guanghe.fs.framework.common.exception.StorageOperationException;
import com.guanghe.fs.storage.plugin.core.annotation.StoragePlugin;
import com.guanghe.fs.storage.plugin.core.chunk.AbstractTempChunkStorageService;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import com.guanghe.fs.storage.plugin.core.model.StorageObjectEntry;
import com.guanghe.fs.storage.plugin.sftp.config.SftpConfig;
import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.FileMode;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Stack;

/**
 * SFTP 存储插件（基于 sshj）
 * <p>
 * 纯对象式存储（同 Local/Minio 模式）：分片先落本地 temp，complete 时合并写远程。
 * SSH 连接每次操作新建（实例被缓存跨线程共享），SFTP 子会话随操作关闭。
 * <p>
 * symlink 一律跳过（防循环）；目录删除用 DFS 递归（SFTP 无递归删除命令）。
 *
 * @Author: guangheUlti
 * @Date: 2026/09/09
 */
@Slf4j
@StoragePlugin(
        identifier = "SFTP",
        name = "SFTP",
        description = "通过 SFTP 协议接入远程服务器目录，支持密码与私钥两种认证方式，适合把文件托管到自有主机。",
        icon = "icon-bendicunchu1",
        schemaResource = "classpath:schema/sftp-schema.json"
)
public class SftpStorageOperationService extends AbstractTempChunkStorageService {

    private String host;
    private int port;
    private String username;
    private String password;
    private String privateKeyPath;
    private String knownHostsPath;

    public SftpStorageOperationService() {
        super();
    }

    public SftpStorageOperationService(StorageConfig config) {
        super(config);
    }

    @Override
    protected String getTempRoot() {
        // 分片临时目录固定于运行目录 storage/temp/sftp
        return "storage/temp/sftp";
    }

    @Override
    protected void validateConfig(StorageConfig config) {
        SftpConfig cfg = readConfig(config);
        if (isBlank(cfg.getSftpHost())) {
            throw new StorageConfigException("SFTP 配置错误：服务器地址不能为空");
        }
        if (isBlank(cfg.getSftpUsername())) {
            throw new StorageConfigException("SFTP 配置错误：用户名不能为空");
        }
        if (isBlank(cfg.getSftpPassword()) && isBlank(cfg.getSftpPrivateKeyPath())) {
            throw new StorageConfigException("SFTP 配置错误：密码与私钥路径至少填写一项");
        }
        String port = cfg.getSftpPort();
        if (!isBlank(port)) {
            try {
                int p = Integer.parseInt(port.trim());
                if (p <= 0 || p > 65535) {
                    throw new StorageConfigException("SFTP 配置错误：端口必须在 1-65535 之间");
                }
            } catch (NumberFormatException e) {
                throw new StorageConfigException("SFTP 配置错误：端口必须是数字");
            }
        }
    }

    @Override
    protected void initialize(StorageConfig config) {
        // 真实建连（P3 连接测试的落点）：TCP + SSH 握手 + 认证 + 验证 SFTP 子系统
        SftpConfig cfg = readConfig(config);
        this.host = cfg.getSftpHost().trim();
        this.port = isBlank(cfg.getSftpPort()) ? 22 : Integer.parseInt(cfg.getSftpPort().trim());
        this.username = cfg.getSftpUsername().trim();
        this.password = cfg.getSftpPassword();
        this.privateKeyPath = cfg.getSftpPrivateKeyPath();
        this.knownHostsPath = cfg.getSftpKnownHostsPath();

        SSHClient sshClient = buildClient();
        try {
            connectAndAuth(sshClient);
            try (SFTPClient sftp = sshClient.newSFTPClient()) {
                sftp.stat(".");
            }
            log.info("{} SFTP 连接测试通过: {}:{}", getLogPrefix(), host, port);
        } catch (StorageConfigException e) {
            disconnectQuietly(sshClient);
            throw e;
        } catch (Exception e) {
            disconnectQuietly(sshClient);
            throw new StorageConfigException("SFTP 连接失败: " + rootMessage(e));
        } finally {
            disconnectQuietly(sshClient);
        }
    }

    /** 构建配置好的客户端（known_hosts 缺省宽松校验 + 告警） */
    private SSHClient buildClient() {
        SSHClient sshClient = new SSHClient();
        if (!isBlank(knownHostsPath) && Files.exists(Paths.get(knownHostsPath.trim()))) {
            try {
                sshClient.loadKnownHosts(new File(knownHostsPath.trim()));
                return sshClient;
            } catch (IOException e) {
                log.warn("{} 加载 known_hosts 失败，改用宽松校验: {}", getLogPrefix(), e.getMessage());
            }
        } else if (!isBlank(knownHostsPath)) {
            log.warn("{} known_hosts 文件不存在，改用宽松校验: {}", getLogPrefix(), knownHostsPath);
        } else {
            log.warn("{} 未配置 known_hosts，使用宽松主机密钥校验（仅建议内网使用）", getLogPrefix());
        }
        sshClient.addHostKeyVerifier(new PromiscuousVerifier());
        return sshClient;
    }

    private void connectAndAuth(SSHClient sshClient) throws IOException {
        sshClient.connect(host, port);
        try {
            if (!isBlank(privateKeyPath)) {
                Path keyPath = Paths.get(privateKeyPath.trim());
                if (!Files.exists(keyPath)) {
                    throw new StorageOperationException("SFTP 私钥文件不存在: " + privateKeyPath);
                }
                PKCS8KeyFile keyFile = new PKCS8KeyFile();
                keyFile.init(keyPath.toFile());
                sshClient.authPublickey(username, new KeyProvider[]{keyFile});
            } else {
                sshClient.authPassword(username, password);
            }
        } catch (Exception e) {
            throw new StorageOperationException("SFTP 认证失败: " + rootMessage(e), e);
        }
    }

    /**
     * 每次操作新建 SSH 连接与 SFTP 子会话（实例被缓存跨线程共享，用完即断）
     */
    private <T> T withSftp(SftpFunction<T> action) {
        SSHClient sshClient = buildClient();
        try {
            connectAndAuth(sshClient);
            try (SFTPClient sftp = sshClient.newSFTPClient()) {
                return action.apply(sftp);
            }
        } catch (StorageOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageOperationException("SFTP 操作失败: " + rootMessage(e), e);
        } finally {
            disconnectQuietly(sshClient);
        }
    }

    @FunctionalInterface
    private interface SftpFunction<T> {
        T apply(SFTPClient sftp) throws IOException;
    }

    private String sftpPath(String objectKey) {
        String key = normalizeKey(objectKey);
        return key.isEmpty() ? "/" : "/" + key;
    }

    @Override
    public void uploadFile(InputStream inputStream, String objectKey) {
        ensureNotPrototype();
        String path = sftpPath(objectKey);
        withSftp(sftp -> {
            sftp.mkdirs(parentOf(path));
            try (RemoteFile remote = sftp.open(path, EnumSet.of(OpenMode.CREAT, OpenMode.WRITE, OpenMode.TRUNC))) {
                // 按块读入内存再顺序写远程，避免大文件占用过多内存（分片本身有大小上限，此处仅兜底）
                byte[] buffer = new byte[65536];
                long offset = 0;
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    remote.write(offset, buffer, 0, read);
                    offset += read;
                }
            }
            log.debug("{} 文件上传成功: objectKey={}", getLogPrefix(), objectKey);
            return null;
        });
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
        String path = sftpPath(objectKey);
        SSHClient sshClient = buildClient();
        try {
            connectAndAuth(sshClient);
            SFTPClient sftp = sshClient.newSFTPClient();
            try {
                FileAttributes attr = sftp.statExistence(path);
                if (attr == null || attr.getType() == FileMode.Type.DIRECTORY) {
                    throw new StorageOperationException("文件不存在: " + objectKey);
                }
            } catch (StorageOperationException e) {
                throw e;
            }
            RemoteFile remote = sftp.open(path, EnumSet.of(OpenMode.READ));
            long length = Math.max(0, Math.min(endByte - startByte + 1, remote.length() - startByte));
            // ReadAheadRemoteFileInputStream 内部类，经共用读取方法包装；
            // 流关闭时连带关闭远程文件、SFTP 会话与 SSH 连接
            java.io.InputStream positioned = new PositionedRemoteInputStream(remote, startByte);
            return new SftpRangeInputStream(sshClient, sftp, remote,
                    new BufferedInputStream(positioned, 65536), length);
        } catch (StorageOperationException e) {
            disconnectQuietly(sshClient);
            throw e;
        } catch (Exception e) {
            disconnectQuietly(sshClient);
            log.error("{} Range读取文件失败: objectKey={}, start={}, end={}",
                    getLogPrefix(), objectKey, startByte, endByte, e);
            throw new StorageOperationException("SFTP 读取文件失败: " + rootMessage(e), e);
        }
    }

    @Override
    public void deleteFile(String objectKey) {
        ensureNotPrototype();
        String path = sftpPath(objectKey);
        withSftp(sftp -> {
            FileAttributes attr = sftp.statExistence(path);
            if (attr == null) {
                log.debug("{} 文件不存在，视为删除成功: objectKey={}", getLogPrefix(), objectKey);
                return null;
            }
            if (attr.getType() == FileMode.Type.DIRECTORY) {
                sftp.rmdir(path);
            } else {
                sftp.rm(path);
            }
            return null;
        });
    }

    @Override
    public void rename(String objectKey, String destObjectKey) {
        ensureNotPrototype();
        String source = sftpPath(objectKey);
        String target = sftpPath(destObjectKey);
        withSftp(sftp -> {
            sftp.mkdirs(parentOf(target));
            // 目录亦可（SSH_FXP_RENAME 对目录同样有效）
            sftp.rename(source, target);
            log.debug("{} 重命名成功: {} -> {}", getLogPrefix(), objectKey, destObjectKey);
            return null;
        });
    }

    @Override
    public String getFileUrl(String objectKey, Integer expireSeconds) {
        throw new StorageOperationException("SFTP 存储不支持生成公网直链");
    }

    @Override
    public InputStream getFileStream(String objectKey) {
        return downloadFile(objectKey);
    }

    @Override
    public boolean isFileExist(String objectKey) {
        ensureNotPrototype();
        String path = sftpPath(objectKey);
        return withSftp(sftp -> sftp.statExistence(path) != null);
    }

    @Override
    public void mkdirDirectory(String dirKey) {
        ensureNotPrototype();
        String path = sftpPath(dirKey);
        withSftp(sftp -> {
            sftp.mkdirs(path);
            return null;
        });
    }

    @Override
    public List<StorageObjectEntry> listObjects(String dirKey) {
        ensureNotPrototype();
        String path = sftpPath(dirKey);
        String parentKey = normalizeKey(dirKey);
        return withSftp(sftp -> {
            List<StorageObjectEntry> entries = new ArrayList<>();
            for (RemoteResourceInfo info : sftp.ls(path)) {
                // symlink 一律跳过（防循环）
                if (info.getAttributes().getType() == FileMode.Type.SYMLINK) {
                    log.debug("{} 跳过 symlink: {}", getLogPrefix(), info.getPath());
                    continue;
                }
                boolean isDir = info.isDirectory();
                Long mtime = info.getAttributes().getMtime() * 1000L;
                String key = parentKey.isEmpty() ? info.getName() : parentKey + "/" + info.getName();
                entries.add(new StorageObjectEntry(
                        key,
                        isDir,
                        isDir ? null : info.getAttributes().getSize(),
                        mtime
                ));
            }
            return entries;
        });
    }

    @Override
    public void deleteDirectory(String dirKey) {
        ensureNotPrototype();
        String path = sftpPath(dirKey);
        withSftp(sftp -> {
            FileAttributes attr = sftp.statExistence(path);
            if (attr == null) {
                return null; // 幂等
            }
            // DFS 递归删除（SFTP 无递归删除命令）
            Stack<String> dirs = new Stack<>();
            List<String> files = new ArrayList<>();
            collect(path, sftp, dirs, files);
            for (String file : files) {
                sftp.rm(file);
            }
            while (!dirs.isEmpty()) {
                sftp.rmdir(dirs.pop());
            }
            return null;
        });
    }

    private void collect(String dir, SFTPClient sftp, Stack<String> dirs, List<String> files) throws IOException {
        dirs.push(dir);
        for (RemoteResourceInfo info : sftp.ls(dir)) {
            if (info.getAttributes().getType() == FileMode.Type.SYMLINK) {
                continue;
            }
            if (info.isDirectory()) {
                collect(info.getPath(), sftp, dirs, files);
            } else {
                files.add(info.getPath());
            }
        }
    }

    @Override
    public Long getAvailableSpace() {
        // sshj 未封装 statvfs 扩展，按容量不可知处理
        return null;
    }

    @Override
    public void writeMerged(Path mergedFile, String objectKey) {
        // complete 阶段：将合并后的本地临时文件写入远程
        String path = sftpPath(objectKey);
        withSftp(sftp -> {
            sftp.mkdirs(parentOf(path));
            try (RemoteFile remote = sftp.open(path, EnumSet.of(OpenMode.CREAT, OpenMode.WRITE, OpenMode.TRUNC));
                 InputStream in = Files.newInputStream(mergedFile)) {
                byte[] buffer = new byte[65536];
                long offset = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    remote.write(offset, buffer, 0, read);
                    offset += read;
                }
            }
            return null;
        });
    }

    @Override
    public void close() {
        // 无长连接需要释放（每次操作独立建连）
    }

    private void disconnectQuietly(SSHClient sshClient) {
        if (sshClient != null) {
            try {
                sshClient.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    /** 取父目录路径（根返回 "/"） */
    private static String parentOf(String path) {
        int idx = path.lastIndexOf('/');
        if (idx <= 0) {
            return "/";
        }
        return path.substring(0, idx);
    }

    private SftpConfig readConfig(StorageConfig config) {
        try {
            return SftpConfig.toObject(config);
        } catch (Exception e) {
            throw new StorageConfigException("SFTP 配置解析失败: " + e.getMessage());
        }
    }

    static boolean isBlank(String s) {
        return s == null || s.isBlank();
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

    static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : cur.getClass().getSimpleName();
    }

    /**
     * 从远程文件指定偏移顺序读取的流（基于 RemoteFile.read(offset,...)，自带预读窗口）
     */
    private static class PositionedRemoteInputStream extends InputStream {
        private final RemoteFile remote;
        private long offset;

        PositionedRemoteInputStream(RemoteFile remote, long startOffset) {
            this.remote = remote;
            this.offset = Math.max(0, startOffset);
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = remote.read(offset, b, off, len);
            if (n > 0) {
                offset += n;
            }
            return n;
        }
    }

    /**
     * SFTP Range 读取流：close 时连带关闭远程文件、SFTP 会话与 SSH 连接
     */
    private static class SftpRangeInputStream extends InputStream {
        private final SSHClient sshClient;
        private final SFTPClient sftp;
        private final RemoteFile remote;
        private final InputStream inner;
        private long remaining;

        SftpRangeInputStream(SSHClient sshClient, SFTPClient sftp, RemoteFile remote,
                             InputStream inner, long length) {
            this.sshClient = sshClient;
            this.sftp = sftp;
            this.remote = remote;
            this.inner = inner;
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
            int toRead = (int) Math.min(len, remaining);
            int n = inner.read(b, off, toRead);
            if (n > 0) {
                remaining -= n;
            }
            return n;
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
                remote.close();
            } catch (Exception ignored) {
            }
            try {
                sftp.close();
            } catch (Exception ignored) {
            }
            try {
                sshClient.disconnect();
            } catch (Exception ignored) {
            }
        }
    }
}
