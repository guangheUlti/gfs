package com.guanghe.fs.storage.plugin.ftp;

import com.guanghe.fs.framework.common.exception.StorageConfigException;
import com.guanghe.fs.framework.common.exception.StorageOperationException;
import com.guanghe.fs.storage.plugin.core.annotation.StoragePlugin;
import com.guanghe.fs.storage.plugin.core.chunk.AbstractTempChunkStorageService;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import com.guanghe.fs.storage.plugin.core.model.StorageObjectEntry;
import com.guanghe.fs.storage.plugin.ftp.config.FtpConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * FTP/FTPS 存储插件（基于 commons-net）
 * <p>
 * 纯对象式存储（同 Local/Minio 模式）：分片先落本地 temp，complete 时合并写远程。
 * FTPClient 非线程安全：每次操作 connect+login，用完 logout+disconnect。
 * <p>
 * FTP 无原生"目录存在"判断，isFileExist 对目录用 listNames 命中判断。
 *
 * @Author: guangheUlti
 * @Date: 2026/09/09
 */
@Slf4j
@StoragePlugin(
        identifier = "FTP",
        name = "FTP存储",
        description = "通过 FTP/FTPS 协议接入传统文件服务器，支持被动模式与 TLS 加密（FTPS），兼容各类老牌主机面板。",
        icon = "icon-bendicunchu1",
        schemaResource = "classpath:schema/ftp-schema.json"
)
public class FtpStorageOperationService extends AbstractTempChunkStorageService {

    private String host;
    private int port;
    private String username;
    private String password;
    private boolean ftpsEnabled;
    private boolean passiveMode;
    private String controlEncoding;

    public FtpStorageOperationService() {
        super();
    }

    public FtpStorageOperationService(StorageConfig config) {
        super(config);
    }

    @Override
    protected String getTempRoot() {
        // 分片临时目录固定于运行目录 storage/temp/ftp
        return "storage/temp/ftp";
    }

    @Override
    protected void validateConfig(StorageConfig config) {
        FtpConfig cfg = readConfig(config);
        if (isBlank(cfg.getFtpHost())) {
            throw new StorageConfigException("FTP 配置错误：服务器地址不能为空");
        }
        if (isBlank(cfg.getFtpUsername())) {
            throw new StorageConfigException("FTP 配置错误：用户名不能为空");
        }
        if (isBlank(cfg.getFtpPassword())) {
            throw new StorageConfigException("FTP 配置错误：密码不能为空");
        }
        String port = cfg.getFtpPort();
        if (!isBlank(port)) {
            try {
                int p = Integer.parseInt(port.trim());
                if (p <= 0 || p > 65535) {
                    throw new StorageConfigException("FTP 配置错误：端口必须在 1-65535 之间");
                }
            } catch (NumberFormatException e) {
                throw new StorageConfigException("FTP 配置错误：端口必须是数字");
            }
        }
    }

    @Override
    protected void initialize(StorageConfig config) {
        // 真实建连（P3 连接测试的落点）：connect+login+列根目录
        FtpConfig cfg = readConfig(config);
        this.host = cfg.getFtpHost().trim();
        this.port = isBlank(cfg.getFtpPort()) ? 21 : Integer.parseInt(cfg.getFtpPort().trim());
        this.username = cfg.getFtpUsername().trim();
        this.password = cfg.getFtpPassword();
        this.ftpsEnabled = "true".equalsIgnoreCase(cfg.getFtpsEnabled());
        this.passiveMode = cfg.getPassiveMode() == null || cfg.getPassiveMode().isBlank()
                || "true".equalsIgnoreCase(cfg.getPassiveMode());
        this.controlEncoding = isBlank(cfg.getControlEncoding()) ? "UTF-8" : cfg.getControlEncoding().trim();

        FTPClient ftpClient = createClient();
        try {
            connectAndLogin(ftpClient);
            // 列根目录验证权限
            ftpClient.listNames("/");
            log.info("{} FTP 连接测试通过: {}:{} ftps={}", getLogPrefix(), host, port, ftpsEnabled);
        } catch (Exception e) {
            throw new StorageConfigException("FTP 连接失败: " + rootMessage(e));
        } finally {
            disconnectQuietly(ftpClient);
        }
    }

    /** 创建并配置客户端（FTPS 加 execPROT("P") 数据通道加密） */
    private FTPClient createClient() {
        FTPClient ftpClient = ftpsEnabled ? new FTPSClient("TLS") : new FTPClient();
        ftpClient.setControlEncoding(controlEncoding);
        return ftpClient;
    }

    private void connectAndLogin(FTPClient ftpClient) throws IOException {
        ftpClient.connect(host, port);
        int reply = ftpClient.getReplyCode();
        if (!FTPReply.isPositiveCompletion(reply)) {
            ftpClient.disconnect();
            throw new StorageOperationException("FTP 服务器拒绝连接: " + reply);
        }
        if (!ftpClient.login(username, password)) {
            throw new StorageOperationException("FTP 登录失败：用户名或密码错误");
        }
        if (passiveMode) {
            ftpClient.enterLocalPassiveMode();
        }
        ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
        if (ftpsEnabled) {
            try {
                ((FTPSClient) ftpClient).execPROT("P");
            } catch (Exception e) {
                log.debug("{} FTPS execPROT 失败（服务器可能不支持）: {}", getLogPrefix(), e.getMessage());
            }
        }
    }

    /** 每次操作新建连接（实例被缓存跨线程共享，用完即断） */
    private <T> T withClient(FtpFunction<T> action) {
        FTPClient ftpClient = createClient();
        try {
            connectAndLogin(ftpClient);
            return action.apply(ftpClient);
        } catch (StorageOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageOperationException("FTP 操作失败: " + rootMessage(e), e);
        } finally {
            disconnectQuietly(ftpClient);
        }
    }

    @FunctionalInterface
    private interface FtpFunction<T> {
        T apply(FTPClient ftpClient) throws IOException;
    }

    private String ftpPath(String objectKey) {
        String key = normalizeKey(objectKey);
        return "/" + key;
    }

    /** 确保远程父目录存在（逐级 makeDirectory） */
    private void ensureRemoteDir(FTPClient ftpClient, String path) throws IOException {
        int idx = path.lastIndexOf('/');
        if (idx <= 0) {
            return;
        }
        String parent = path.substring(0, idx);
        StringBuilder current = new StringBuilder();
        for (String segment : parent.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (current.length() > 0) {
                current.append('/');
            }
            current.append(segment);
            String dir = current.toString();
            // makeDirectory 对已存在目录返回 false，判存兜底
            if (!directoryExists(ftpClient, dir)) {
                ftpClient.makeDirectory(dir);
            }
        }
    }

    /** 目录是否存在（changeWorkingDirectory 探测，探测后回原目录） */
    private boolean directoryExists(FTPClient ftpClient, String dir) throws IOException {
        String original = ftpClient.printWorkingDirectory();
        boolean exists = ftpClient.changeWorkingDirectory(dir);
        if (original != null) {
            ftpClient.changeWorkingDirectory(original);
        }
        return exists;
    }

    @Override
    public void uploadFile(InputStream inputStream, String objectKey) {
        ensureNotPrototype();
        String path = ftpPath(objectKey);
        withClient(ftpClient -> {
            ensureRemoteDir(ftpClient, path);
            try (InputStream in = inputStream) {
                boolean ok = ftpClient.storeFile(path, in);
                if (!ok) {
                    throw new StorageOperationException("FTP 上传失败: " + ftpClient.getReplyString());
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
        String path = ftpPath(objectKey);
        FTPClient ftpClient = createClient();
        try {
            connectAndLogin(ftpClient);
            // REST 命令：后续 retrieveFileStream 从该偏移开始返回数据
            ftpClient.setRestartOffset(startByte);
            InputStream raw = ftpClient.retrieveFileStream(path);
            if (raw == null) {
                throw new StorageOperationException("文件不存在: " + objectKey);
            }
            long length = endByte - startByte + 1;
            // 流关闭时补 completePendingCommand 并断开连接
            return new FtpRangeInputStream(ftpClient, raw, length);
        } catch (StorageOperationException e) {
            disconnectQuietly(ftpClient);
            throw e;
        } catch (Exception e) {
            disconnectQuietly(ftpClient);
            log.error("{} Range读取文件失败: objectKey={}, start={}, end={}",
                    getLogPrefix(), objectKey, startByte, endByte, e);
            throw new StorageOperationException("FTP 读取文件失败: " + rootMessage(e), e);
        }
    }

    @Override
    public void deleteFile(String objectKey) {
        ensureNotPrototype();
        String path = ftpPath(objectKey);
        withClient(ftpClient -> {
            // 文件删除；目录场景用 rmd（挂载分支不会调用此处删目录，双保险）
            if (!ftpClient.deleteFile(path)) {
                // 可能是目录或不存在：尝试删除目录；仍失败则看是否本就不存在
                if (!ftpClient.removeDirectory(path) && listNamesHit(ftpClient, path)) {
                    throw new StorageOperationException("FTP 删除失败: " + ftpClient.getReplyString());
                }
            }
            log.debug("{} 删除成功: objectKey={}", getLogPrefix(), objectKey);
            return null;
        });
    }

    private boolean listNamesHit(FTPClient ftpClient, String path) throws IOException {
        String parent = path.substring(0, path.lastIndexOf('/') + 1);
        String name = path.substring(path.lastIndexOf('/') + 1);
        String[] names = ftpClient.listNames(parent);
        if (names == null) {
            return false;
        }
        for (String n : names) {
            if (n.equals(path) || n.equals(name) || n.equals(parent + name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void rename(String objectKey, String destObjectKey) {
        ensureNotPrototype();
        String source = ftpPath(objectKey);
        String target = ftpPath(destObjectKey);
        withClient(ftpClient -> {
            ensureRemoteDir(ftpClient, target);
            // 全路径 rename，目录亦可
            if (!ftpClient.rename(source, target)) {
                throw new StorageOperationException("FTP 重命名失败: " + ftpClient.getReplyString());
            }
            log.debug("{} 重命名成功: {} -> {}", getLogPrefix(), objectKey, destObjectKey);
            return null;
        });
    }

    @Override
    public String getFileUrl(String objectKey, Integer expireSeconds) {
        throw new StorageOperationException("FTP 存储不支持生成公网直链");
    }

    @Override
    public InputStream getFileStream(String objectKey) {
        return downloadFile(objectKey);
    }

    @Override
    public boolean isFileExist(String objectKey) {
        ensureNotPrototype();
        String path = ftpPath(objectKey);
        return withClient(ftpClient -> {
            // FTP 无原生"目录存在"判断：文件用 mlist/listFiles 判，目录用 cwd 探测
            FTPFile[] files = mlistFileSafe(ftpClient, path);
            if (files != null && files.length > 0 && files[0] != null && files[0].isValid()) {
                return true;
            }
            return directoryExists(ftpClient, path);
        });
    }

    @Override
    public void mkdirDirectory(String dirKey) {
        ensureNotPrototype();
        String path = ftpPath(dirKey);
        withClient(ftpClient -> {
            StringBuilder current = new StringBuilder();
            for (String segment : path.split("/")) {
                if (segment.isEmpty()) {
                    continue;
                }
                if (current.length() > 0) {
                    current.append('/');
                }
                current.append(segment);
                String dir = current.toString();
                if (!directoryExists(ftpClient, dir)) {
                    if (!ftpClient.makeDirectory(dir)) {
                        // 并发下目录可能已被其它请求创建
                        if (!directoryExists(ftpClient, dir)) {
                            throw new StorageOperationException("FTP 创建目录失败: " + ftpClient.getReplyString());
                        }
                    }
                }
            }
            return null;
        });
    }

    @Override
    public List<StorageObjectEntry> listObjects(String dirKey) {
        ensureNotPrototype();
        String path = ftpPath(dirKey);
        String parentKey = normalizeKey(dirKey);
        return withClient(ftpClient -> {
            List<StorageObjectEntry> entries = new ArrayList<>();
            // 优先 MLSD（带 mtime）；失败退 LIST（mtime=null，接受）
            List<FTPFile> files;
            try {
                files = List.of(ftpClient.mlistDir(path));
            } catch (Exception e) {
                FTPFile[] listed = ftpClient.listFiles(path);
                files = listed == null ? List.of() : List.of(listed);
            }
            for (FTPFile file : files) {
                String name = file.getName();
                if (name == null || name.equals(".") || name.equals("..")) {
                    continue;
                }
                boolean isDir = file.isDirectory();
                String key = parentKey.isEmpty() ? name : parentKey + "/" + name;
                Long lastModified = file.getTimestamp() == null ? null : file.getTimestamp().getTimeInMillis();
                entries.add(new StorageObjectEntry(
                        key,
                        isDir,
                        isDir ? null : file.getSize(),
                        lastModified
                ));
            }
            return entries;
        });
    }

    @Override
    public void deleteDirectory(String dirKey) {
        ensureNotPrototype();
        String path = ftpPath(dirKey);
        withClient(ftpClient -> {
            // commons-net 无递归删除：先递归删文件与子目录
            deleteTree(ftpClient, path);
            return null;
        });
    }

    private void deleteTree(FTPClient ftpClient, String path) throws IOException {
        // 深度优先删除
        FTPFile[] files = mlistDirSafe(ftpClient, path);
        if (files == null) {
            files = ftpClient.listFiles(path);
        }
        if (files == null) {
            return;
        }
        for (FTPFile file : files) {
            String name = file.getName();
            if (name == null || name.equals(".") || name.equals("..")) {
                continue;
            }
            String child = path.endsWith("/") ? path + name : path + "/" + name;
            if (file.isDirectory()) {
                deleteTree(ftpClient, child);
            } else {
                ftpClient.deleteFile(child);
            }
        }
        ftpClient.removeDirectory(path);
    }

    private FTPFile[] mlistDirSafe(FTPClient ftpClient, String path) {
        try {
            return ftpClient.mlistDir(path);
        } catch (Exception e) {
            return null;
        }
    }

    private FTPFile[] mlistFileSafe(FTPClient ftpClient, String path) {
        try {
            FTPFile file = ftpClient.mlistFile(path);
            return file == null ? null : new FTPFile[]{file};
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Long getAvailableSpace() {
        // FTP 无标准容量命令（SITE AVAIL 等非通用），按容量不可知处理
        return null;
    }

    @Override
    public void writeMerged(Path mergedFile, String objectKey) {
        // complete 阶段：将合并后的本地临时文件写入远程
        String path = ftpPath(objectKey);
        withClient(ftpClient -> {
            ensureRemoteDir(ftpClient, path);
            try (InputStream in = Files.newInputStream(mergedFile)) {
                boolean ok = ftpClient.storeFile(path, in);
                if (!ok) {
                    throw new StorageOperationException("FTP 上传合并文件失败: " + ftpClient.getReplyString());
                }
            }
            return null;
        });
    }

    @Override
    public void close() {
        // 无长连接需要释放（每次操作独立建连）
    }

    private void disconnectQuietly(FTPClient ftpClient) {
        if (ftpClient == null) {
            return;
        }
        try {
            if (ftpClient.isConnected()) {
                ftpClient.logout();
            }
        } catch (Exception ignored) {
        }
        try {
            if (ftpClient.isConnected()) {
                ftpClient.disconnect();
            }
        } catch (Exception ignored) {
        }
    }

    private FtpConfig readConfig(StorageConfig config) {
        try {
            return FtpConfig.toObject(config);
        } catch (Exception e) {
            throw new StorageConfigException("FTP 配置解析失败: " + e.getMessage());
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
     * FTP Range 读取流：读满 length 后补 completePendingCommand，close 时断开连接
     */
    private static class FtpRangeInputStream extends InputStream {
        private final FTPClient ftpClient;
        private final InputStream inner;
        private long remaining;
        private boolean commandCompleted;

        FtpRangeInputStream(FTPClient ftpClient, InputStream raw, long length) {
            this.ftpClient = ftpClient;
            this.inner = new BufferedInputStream(raw, 65536);
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
            // 读完指定长度即完成本次数据通道
            if (remaining <= 0 && !commandCompleted) {
                commandCompleted = true;
                ftpClient.completePendingCommand();
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
                // 未读满就关闭：补一次 pending command 清理（aborted 场景）
                if (!commandCompleted) {
                    commandCompleted = true;
                    try {
                        ftpClient.completePendingCommand();
                    } catch (Exception ignored) {
                    }
                }
                inner.close();
            } catch (Exception ignored) {
            }
        }
    }
}
