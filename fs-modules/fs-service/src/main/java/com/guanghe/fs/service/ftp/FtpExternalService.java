package com.guanghe.fs.service.ftp;

import com.guanghe.fs.service.domain.ServiceSetting;
import com.guanghe.fs.service.spi.ExternalFileService;
import lombok.extern.slf4j.Slf4j;
import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.impl.DefaultFtpServer;
import org.apache.ftpserver.listener.ListenerFactory;
import org.springframework.stereotype.Component;

/**
 * FTP 对外服务 SPI 实现（Apache FtpServer 嵌入式，默认端口 9021）。
 * <p>
 * 被动端口范围 50000-50100：Windows 防火墙需放行；数据连接同样支持 Range 断点续传下载。
 */
@Slf4j
@Component
public class FtpExternalService implements ExternalFileService {

    /** 默认监听端口（避开 21 特权端口与 9022 SFTP） */
    static final int DEFAULT_PORT = 9021;
    /** 被动模式数据端口范围 */
    static final int PASV_MIN = 50000;
    static final int PASV_MAX = 50100;

    private final GfsFtpBridge bridge;

    private volatile DefaultFtpServer server;

    public FtpExternalService(GfsFtpBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public String type() {
        return "ftp";
    }

    @Override
    public boolean socketBased() {
        return true;
    }

    @Override
    public Integer defaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    public synchronized void apply(ServiceSetting setting) {
        int port = setting.getPort() == null ? DEFAULT_PORT : setting.getPort();
        String bindAddress = setting.getBindAddress() == null || setting.getBindAddress().isBlank()
                ? "0.0.0.0" : setting.getBindAddress().trim();
        boolean enabled = setting.getEnabled() != null && setting.getEnabled() == 1;
        if (!enabled) {
            stop();
            return;
        }
        if (server != null && isRunning()) {
            org.apache.ftpserver.listener.Listener listener = server.getListener("default");
            Integer curPort = listener == null ? null : listener.getPort();
            String curBind = listener == null ? null : listener.getServerAddress();
            if (curPort != null && curPort == port
                    && (curBind == null || bindAddress.equals("0.0.0.0") || curBind.equals(bindAddress))) {
                return; // 幂等
            }
            stop();
        }
        start(port, bindAddress);
    }

    synchronized void start(int port, String bindAddress) {
        try {
            FtpServerFactory factory = new FtpServerFactory();
            factory.setUserManager(bridge);
            // ★ 关键：不设 FileSystemFactory 会回退 FtpServer 自带的本地磁盘文件系统，
            //   上传直接写到进程工作目录、完全绕过 GFS。必须显式指定 GFS 虚拟文件系统
            factory.setFileSystem(user -> {
                // 每会话一个视图；user 即 GfsFtpBridge.GfsFtpUser（含 gfsUserId）
                String gfsUserId = user instanceof GfsFtpBridge.GfsFtpUser gfsUser
                        ? gfsUser.gfsUserId() : null;
                return new GfsFtpFileSystem(bridge, gfsUserId);
            });

            ListenerFactory listenerFactory = new ListenerFactory();
            listenerFactory.setPort(port);
            if (bindAddress != null && !bindAddress.isBlank()) {
                listenerFactory.setServerAddress(bindAddress);
            }
            // 被动模式：GFS 与客户端之间可能有防火墙/NAT，被动端口固定小区间便于放行
            DataConnectionConfigurationFactory dataConf = new DataConnectionConfigurationFactory();
            dataConf.setPassivePorts(PASV_MIN + "-" + PASV_MAX);
            dataConf.setPassiveExternalAddress(bindAddress);
            listenerFactory.setDataConnectionConfiguration(dataConf.createDataConnectionConfiguration());
            factory.addListener("default", listenerFactory.createListener());

            this.server = (DefaultFtpServer) factory.createServer();
            this.server.start();
            log.info("FTP 服务已启动: {}:{}, 被动端口 {}-{}", bindAddress, port, PASV_MIN, PASV_MAX);
        } catch (Exception e) {
            this.server = null;
            throw new IllegalStateException("FTP 服务启动失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void stop() {
        DefaultFtpServer current = this.server;
        if (current != null) {
            try {
                current.stop();
            } catch (Exception e) {
                log.warn("FTP 服务停止异常", e);
            } finally {
                this.server = null;
            }
            log.info("FTP 服务已停止");
        }
    }

    @Override
    public boolean isRunning() {
        DefaultFtpServer current = this.server;
        return current != null && !current.isStopped();
    }
}
