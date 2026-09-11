package com.guanghe.fs.service.sftp;

import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.service.sftp.nio.GfsFileSystemFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * SFTP 服务端持有者（Apache MINA SSHD，默认端口 9022）。
 * 主机密钥首次启动生成并落盘，重启后指纹稳定（服务端实现指南 §4.4）。
 */
@Slf4j
@Component
public class SftpServerHolder {

    private final GfsPasswordAuthenticator passwordAuthenticator;
    private final GfsFileSystemFactory fileSystemFactory;

    @Value("${fs.service.sftp.host-key-path:data/ssh-host-key}")
    private String hostKeyPath;

    private volatile SshServer server;

    public SftpServerHolder(GfsPasswordAuthenticator passwordAuthenticator,
                            GfsFileSystemFactory fileSystemFactory) {
        this.passwordAuthenticator = passwordAuthenticator;
        this.fileSystemFactory = fileSystemFactory;
    }

    public synchronized void start(int port, String bindAddress) {
        if (server != null && server.isStarted()) {
            return;
        }
        try {
            SshServer sshServer = SshServer.setUpDefaultServer();
            sshServer.setPort(port);
            if (StrUtil.isNotEmpty(bindAddress)) {
                sshServer.setHost(bindAddress);
            }
            sshServer.setKeyPairProvider(buildHostKeyProvider());
            sshServer.setPasswordAuthenticator(passwordAuthenticator);
            SftpSubsystemFactory sftpFactory = new SftpSubsystemFactory();
            // GFS 虚拟文件系统没有 POSIX 文件，必须用自定义 accessor 补齐 owner/group/permissions，
            // 否则 sshd 回退 IoUtils.getPermissions → toFile() 抛不支持，READDIR/STAT 全挂
            sftpFactory.setFileSystemAccessor(new com.guanghe.fs.service.sftp.nio.GfsSftpFileSystemAccessor());
            sshServer.setSubsystemFactories(List.of(sftpFactory));
            sshServer.setFileSystemFactory(fileSystemFactory);
            sshServer.start();
            this.server = sshServer;
            log.info("SFTP 服务已启动: {}:{}, 主机密钥={}", bindAddress, port, hostKeyPath);
        } catch (Exception e) {
            this.server = null;
            throw new IllegalStateException("SFTP 服务启动失败: " + e.getMessage(), e);
        }
    }

    public synchronized void stopIfRunning() {
        SshServer current = this.server;
        if (current != null) {
            try {
                // 管理动作要求即时生效：强停（不等会话排空）
                current.stop(true);
            } catch (Exception e) {
                log.warn("SFTP 服务停止异常", e);
            } finally {
                this.server = null;
            }
            log.info("SFTP 服务已停止");
        }
    }

    public synchronized void restart(int port, String bindAddress) {
        stopIfRunning();
        start(port, bindAddress);
    }

    public boolean isRunning() {
        SshServer current = this.server;
        return current != null && current.isStarted();
    }

    private KeyPairProvider buildHostKeyProvider() {
        SimpleGeneratorHostKeyProvider provider = new SimpleGeneratorHostKeyProvider();
        Path path = Path.of(hostKeyPath);
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
        } catch (Exception e) {
            log.warn("创建主机密钥目录失败: {}", path.getParent(), e);
        }
        provider.setPath(path);
        provider.setAlgorithm("RSA");
        provider.setKeySize(2048);
        return provider;
    }
}
