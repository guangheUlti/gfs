package com.guanghe.fs.storage.plugin.sftp;

import com.guanghe.fs.framework.common.exception.StorageConfigException;
import com.guanghe.fs.framework.common.exception.StorageOperationException;
import com.guanghe.fs.storage.plugin.core.IHostKeyTrustService;
import com.guanghe.fs.storage.plugin.core.annotation.StoragePlugin;
import com.guanghe.fs.storage.plugin.core.chunk.AbstractTempChunkStorageService;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import com.guanghe.fs.storage.plugin.core.dto.HostKeyInfo;
import com.guanghe.fs.storage.plugin.core.model.StorageObjectEntry;
import com.guanghe.fs.storage.plugin.sftp.config.SftpConfig;
import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.FileMode;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts;
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
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
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
        description = "通过 SFTP 协议挂载远程服务器目录，支持密码与私钥两种认证方式，可直接浏览服务器上已有文件。",
        icon = "icon-bendicunchu1",
        schemaResource = "classpath:schema/sftp-schema.json"
)
public class SftpStorageOperationService extends AbstractTempChunkStorageService implements IHostKeyTrustService {

    /** 未显式配置时的工作目录 known_hosts（运行时生成，随部署目录走） */
    private static final String MANAGED_KNOWN_HOSTS = "storage/known_hosts";

    private static final int HOST_KEY_TIMEOUT_MILLIS = 8000;

    private static final Object KNOWN_HOSTS_LOCK = new Object();

    private static final String AUTH_PASSWORD = "password";

    private static final String AUTH_KEY = "key";

    /** 目录列举连接的复用空闲上限：挂载扫描连续列举成百上千个目录，复用连接避免每次 SSH 握手 */
    private static final long LIST_CLIENT_IDLE_MILLIS = 60_000;

    private final Object listClientLock = new Object();

    private String host;
    private int port;
    private String username;
    private String authType;
    private String password;
    private String privateKeyPath;
    private String knownHostsPath;
    /** 挂载根路径（初始化时 canonicalize 后的绝对路径，无末尾斜杠） */
    private String rootPath;
    /** 目录列举专用连接（仅 listObjects 使用，按空闲时间回收） */
    private SSHClient listClient;
    private long listClientLastUsedAt;

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
        requireHost(cfg);
        if (isBlank(cfg.getSftpUsername())) {
            throw new StorageConfigException("SFTP 配置错误：用户名不能为空");
        }
        if (isBlank(cfg.getSftpRootPath())) {
            throw new StorageConfigException("SFTP 配置错误：挂载根路径不能为空");
        }
        if (AUTH_KEY.equals(resolveAuthType(cfg))) {
            if (isBlank(cfg.getSftpPrivateKeyPath())) {
                throw new StorageConfigException("SFTP 配置错误：认证方式为私钥时，私钥路径不能为空");
            }
        } else if (isBlank(cfg.getSftpPassword())) {
            throw new StorageConfigException("SFTP 配置错误：认证方式为密码时，密码不能为空");
        }
        portOf(cfg);
    }

    @Override
    protected void initialize(StorageConfig config) {
        // 真实建连（P3 连接测试的落点）：TCP + SSH 握手 + 认证 + 验证 SFTP 子系统
        SftpConfig cfg = readConfig(config);
        this.host = cfg.getSftpHost().trim();
        this.port = portOf(cfg);
        this.username = cfg.getSftpUsername().trim();
        this.authType = resolveAuthType(cfg);
        this.password = cfg.getSftpPassword();
        this.privateKeyPath = cfg.getSftpPrivateKeyPath();
        this.knownHostsPath = resolveKnownHostsPath(cfg).toString();

        SSHClient sshClient = buildClient();
        try {
            connectAndAuth(sshClient);
            try (SFTPClient sftp = sshClient.newSFTPClient()) {
                sftp.stat(".");
                this.rootPath = resolveRootPath(cfg, sftp);
            }
            log.info("{} SFTP 连接测试通过: {}:{}，挂载根路径: {}", getLogPrefix(), host, port, rootPath);
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
        if (Files.isRegularFile(Paths.get(knownHostsPath))) {
            try {
                sshClient.loadKnownHosts(new File(knownHostsPath));
                return sshClient;
            } catch (IOException e) {
                log.warn("{} 加载 known_hosts 失败，改用宽松校验: {}", getLogPrefix(), e.getMessage());
            }
        } else {
            log.warn("{} known_hosts 文件不存在，改用宽松校验（可在存储配置中点击「获取主机指纹」完成信任）: {}",
                    getLogPrefix(), knownHostsPath);
        }
        sshClient.addHostKeyVerifier(new PromiscuousVerifier());
        return sshClient;
    }

    private void connectAndAuth(SSHClient sshClient) throws IOException {
        sshClient.connect(host, port);
        try {
            if (AUTH_KEY.equals(authType)) {
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

    /** 认证方式：password / key；旧配置未写该字段时沿用私钥优先的原行为 */
    private static String resolveAuthType(SftpConfig cfg) {
        String type = isBlank(cfg.getSftpAuthType())
                ? ""
                : cfg.getSftpAuthType().trim().toLowerCase(Locale.ROOT);
        if (type.isEmpty()) {
            return isBlank(cfg.getSftpPrivateKeyPath()) ? AUTH_PASSWORD : AUTH_KEY;
        }
        if (!AUTH_PASSWORD.equals(type) && !AUTH_KEY.equals(type)) {
            throw new StorageConfigException("SFTP 配置错误：不支持的认证方式: " + cfg.getSftpAuthType());
        }
        return type;
    }

    /** 解析并校验挂载根路径：相对路径按登录用户主目录解析，返回无末尾斜杠的绝对路径 */
    private static String resolveRootPath(SftpConfig cfg, SFTPClient sftp) throws IOException {
        String raw = cfg.getSftpRootPath().trim();
        String canonical;
        try {
            canonical = sftp.canonicalize(raw);
        } catch (IOException e) {
            throw new StorageConfigException("SFTP 配置错误：挂载根路径不可用: " + raw);
        }
        if (isBlank(canonical)) {
            throw new StorageConfigException("SFTP 配置错误：挂载根路径解析为空: " + raw);
        }
        if (canonical.length() > 1 && canonical.endsWith("/")) {
            canonical = canonical.substring(0, canonical.length() - 1);
        }
        FileAttributes attr = sftp.statExistence(canonical);
        if (attr == null) {
            throw new StorageConfigException("SFTP 配置错误：挂载根路径不存在: " + canonical);
        }
        if (attr.getType() != FileMode.Type.DIRECTORY) {
            throw new StorageConfigException("SFTP 配置错误：挂载根路径不是目录: " + canonical);
        }
        return canonical;
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
        if (key.isEmpty()) {
            return rootPath;
        }
        return rootPath.endsWith("/") ? rootPath + key : rootPath + "/" + key;
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
    public boolean isMountMode() {
        // 远程目录树镜像：文件页通过挂载点 + 目录索引浏览服务器已有文件，与 SMB/本地挂载一致
        return true;
    }

    @Override
    public boolean isDirectAccess() {
        // SFTP 为无状态远程：浏览与操作全部直连服务器，不在本地索引任何条目
        return false;
    }

    @Override
    public boolean isStatelessRemote() {
        // 不落库：目录与文件全部以远端服务器为唯一真相源，支持根路径 "/" 即时浏览
        return true;
    }

    @Override
    public List<StorageObjectEntry> listObjects(String dirKey) {
        ensureNotPrototype();
        String path = sftpPath(dirKey);
        String parentKey = normalizeKey(dirKey);
        synchronized (listClientLock) {
            // 连接可能被服务端回收：首次失败重建连接重试一次
            for (int attempt = 0; ; attempt++) {
                try {
                    SSHClient client = obtainListClient();
                    try (SFTPClient sftp = client.newSFTPClient()) {
                        List<StorageObjectEntry> entries = new ArrayList<>();
                        for (RemoteResourceInfo info : sftp.ls(path)) {
                            String name = info.getName();
                            // 挂载扫描以真实目录为真相源，"."/".." 不参与索引，否则会递归死循环
                            if (name == null || ".".equals(name) || "..".equals(name)) {
                                continue;
                            }
                            // symlink 一律跳过（防循环）
                            if (info.getAttributes().getType() == FileMode.Type.SYMLINK) {
                                log.debug("{} 跳过 symlink: {}", getLogPrefix(), info.getPath());
                                continue;
                            }
                            boolean isDir = info.isDirectory();
                            Long mtime = info.getAttributes().getMtime() * 1000L;
                            String key = parentKey.isEmpty() ? name : parentKey + "/" + name;
                            entries.add(new StorageObjectEntry(
                                    key,
                                    isDir,
                                    isDir ? null : info.getAttributes().getSize(),
                                    mtime
                            ));
                        }
                        listClientLastUsedAt = System.currentTimeMillis();
                        return entries;
                    }
                } catch (Exception e) {
                    closeListClient();
                    if (attempt == 0) {
                        continue;
                    }
                    throw new StorageOperationException("SFTP 列举目录失败: " + rootMessage(e), e);
                }
            }
        }
    }

    private SSHClient obtainListClient() throws IOException {
        long now = System.currentTimeMillis();
        if (listClient != null && now - listClientLastUsedAt > LIST_CLIENT_IDLE_MILLIS) {
            closeListClient();
        }
        if (listClient == null) {
            SSHClient client = buildClient();
            connectAndAuth(client);
            listClient = client;
        }
        return listClient;
    }

    private void closeListClient() {
        SSHClient client = listClient;
        listClient = null;
        disconnectQuietly(client);
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
            String name = info.getName();
            if (name == null || ".".equals(name) || "..".equals(name)) {
                continue;
            }
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
        // 业务操作每次独立建连；仅回收目录列举的复用连接
        synchronized (listClientLock) {
            closeListClient();
        }
    }

    private static void disconnectQuietly(SSHClient sshClient) {
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

    @Override
    public HostKeyInfo probeHostKey(StorageConfig config) {
        SftpConfig cfg = readConfig(config);
        String probeHost = requireHost(cfg);
        int probePort = portOf(cfg);
        Path knownHosts = resolveKnownHostsPath(cfg);
        PublicKey key = captureHostKey(probeHost, probePort);
        return buildHostKeyInfo(probeHost, probePort, key, knownHosts);
    }

    @Override
    public HostKeyInfo trustHostKey(StorageConfig config, String expectedFingerprint) {
        SftpConfig cfg = readConfig(config);
        String trustHost = requireHost(cfg);
        int trustPort = portOf(cfg);
        Path knownHosts = resolveKnownHostsPath(cfg);
        PublicKey key = captureHostKey(trustHost, trustPort);
        String actualFingerprint = fingerprintOf(key);
        if (isBlank(expectedFingerprint) || !actualFingerprint.equals(expectedFingerprint.trim())) {
            throw new StorageOperationException("服务器返回的主机密钥与确认的指纹不一致，已拒绝写入 "
                    + "（确认值: " + expectedFingerprint + ", 实际值: " + actualFingerprint + "）");
        }
        writeKnownHost(knownHosts, trustHost, trustPort, key);
        return buildHostKeyInfo(trustHost, trustPort, key, knownHosts);
    }

    private static PublicKey captureHostKey(String host, int port) {
        HostKeyCapturingVerifier verifier = new HostKeyCapturingVerifier();
        SSHClient sshClient = new SSHClient();
        sshClient.setConnectTimeout(HOST_KEY_TIMEOUT_MILLIS);
        sshClient.setTimeout(HOST_KEY_TIMEOUT_MILLIS);
        sshClient.addHostKeyVerifier(verifier);
        try {
            sshClient.connect(host, port);
            PublicKey key = verifier.getKey();
            if (key == null) {
                throw new StorageOperationException("SFTP 主机密钥获取失败：服务器未返回主机密钥");
            }
            return key;
        } catch (IOException e) {
            throw new StorageOperationException("SFTP 主机密钥获取失败: " + rootMessage(e), e);
        } finally {
            disconnectQuietly(sshClient);
        }
    }

    private static HostKeyInfo buildHostKeyInfo(String host, int port, PublicKey key, Path knownHosts) {
        KeyType type = KeyType.fromKey(key);
        String keyType = type == KeyType.UNKNOWN ? key.getAlgorithm() : type.toString();
        return HostKeyInfo.builder()
                .host(host)
                .port(port)
                .keyType(keyType)
                .fingerprint(fingerprintOf(key))
                .knownHostsPath(knownHosts.toString())
                .trusted(isTrusted(knownHosts, knownHostsHost(host, port), type, key))
                .build();
    }

    private static boolean isTrusted(Path knownHosts, String hostPart, KeyType type, PublicKey key) {
        if (type == KeyType.UNKNOWN || !Files.isRegularFile(knownHosts)) {
            return false;
        }
        try {
            OpenSSHKnownHosts knownHostsFile = new OpenSSHKnownHosts(knownHosts.toFile());
            for (OpenSSHKnownHosts.KnownHostEntry entry : knownHostsFile.entries()) {
                if (entry.appliesTo(type, hostPart) && entry.verify(key)) {
                    return true;
                }
            }
        } catch (IOException e) {
            log.warn("读取 known_hosts 失败: {}", e.getMessage());
        }
        return false;
    }

    private static void writeKnownHost(Path knownHosts, String host, int port, PublicKey key) {
        KeyType type = KeyType.fromKey(key);
        if (type == KeyType.UNKNOWN) {
            throw new StorageOperationException("不支持的 SFTP 主机密钥类型: " + key.getAlgorithm());
        }
        String hostPart = knownHostsHost(host, port);
        synchronized (KNOWN_HOSTS_LOCK) {
            try {
                Path parent = knownHosts.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                OpenSSHKnownHosts knownHostsFile = new OpenSSHKnownHosts(knownHosts.toFile());
                boolean otherKeyExists = false;
                for (OpenSSHKnownHosts.KnownHostEntry entry : knownHostsFile.entries()) {
                    if (!entry.appliesTo(type, hostPart)) {
                        continue;
                    }
                    if (entry.verify(key)) {
                        return;
                    }
                    otherKeyExists = true;
                }
                if (otherKeyExists) {
                    throw new StorageOperationException("known_hosts 中已存在该主机的其它密钥，"
                            + "拒绝自动覆盖，请人工核对后清理: " + knownHosts);
                }
                knownHostsFile.write(new OpenSSHKnownHosts.HostEntry(null, hostPart, type, key));
                log.info("SFTP 主机密钥已写入 known_hosts: host={}, type={}, file={}",
                        hostPart, type, knownHosts);
            } catch (IOException e) {
                throw new StorageOperationException("写入 known_hosts 失败: " + rootMessage(e), e);
            }
        }
    }

    private static String fingerprintOf(PublicKey key) {
        try {
            byte[] encoded = new Buffer.PlainBuffer().putPublicKey(key).getCompactData();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(encoded);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new StorageOperationException("计算主机密钥指纹失败: " + rootMessage(e), e);
        }
    }

    /** 与 sshj 的 known_hosts 匹配规则保持一致：主机名小写，非 22 端口写成 [host]:port */
    private static String knownHostsHost(String host, int port) {
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        return port == 22 ? normalized : "[" + normalized + "]:" + port;
    }

    private static Path resolveKnownHostsPath(SftpConfig cfg) {
        if (!isBlank(cfg.getSftpKnownHostsPath())) {
            return Paths.get(cfg.getSftpKnownHostsPath().trim()).toAbsolutePath().normalize();
        }
        return Paths.get(MANAGED_KNOWN_HOSTS).toAbsolutePath().normalize();
    }

    private static String requireHost(SftpConfig cfg) {
        if (isBlank(cfg.getSftpHost())) {
            throw new StorageConfigException("SFTP 配置错误：服务器地址不能为空");
        }
        return cfg.getSftpHost().trim();
    }

    private static int portOf(SftpConfig cfg) {
        if (isBlank(cfg.getSftpPort())) {
            return 22;
        }
        try {
            int p = Integer.parseInt(cfg.getSftpPort().trim());
            if (p <= 0 || p > 65535) {
                throw new StorageConfigException("SFTP 配置错误：端口必须在 1-65535 之间");
            }
            return p;
        } catch (NumberFormatException e) {
            throw new StorageConfigException("SFTP 配置错误：端口必须是数字");
        }
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

        /** 只捕获服务器主机密钥、不做校验的 verifier（用于指纹探测与确认写入） */
    private static final class HostKeyCapturingVerifier implements HostKeyVerifier {

        private volatile PublicKey key;

        @Override
        public boolean verify(String hostname, int port, PublicKey key) {
            this.key = key;
            return true;
        }

        @Override
        public List<String> findExistingAlgorithms(String hostname, int port) {
            return Collections.emptyList();
        }

        PublicKey getKey() {
            return key;
        }
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
