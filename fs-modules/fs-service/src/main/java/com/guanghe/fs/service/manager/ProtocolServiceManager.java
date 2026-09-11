package com.guanghe.fs.service.manager;

import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.service.domain.ServiceSetting;
import com.guanghe.fs.service.service.ServiceSettingService;
import com.guanghe.fs.service.sftp.SftpServerHolder;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.guanghe.fs.service.domain.table.ServiceSettingTableDef.SERVICE_SETTING;

/**
 * 对外文件服务生命周期管理：
 * - 应用启动时自动拉起 enabled=1 的服务（SFTP 启动 MINA server；WebDAV 仅开关 filter 放行标志）
 * - 配置变更热生效：enabled 翻转 → start/stop；端口/地址变化且保持启用 → 重启
 * - 启动失败不抛给保存动作，状态置 error 并把异常信息带给 /list
 */
@Slf4j
@Component
public class ProtocolServiceManager implements SmartLifecycle {

    /** 运行状态常量 */
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_STOPPED = "stopped";
    public static final String STATUS_ERROR = "error";

    private final ServiceSettingService serviceSettingService;
    private final ObjectProvider<SftpServerHolder> sftpServerHolderProvider;

    /** 各服务运行状态（内存态，不落库） */
    private final Map<String, ServiceRuntime> runtimes = new ConcurrentHashMap<>();

    private volatile boolean running = false;

    public ProtocolServiceManager(ServiceSettingService serviceSettingService,
                                  ObjectProvider<SftpServerHolder> sftpServerHolderProvider) {
        this.serviceSettingService = serviceSettingService;
        this.sftpServerHolderProvider = sftpServerHolderProvider;
    }

    @Override
    public void start() {
        List<ServiceSetting> settings = serviceSettingService.list(
                new QueryWrapper().where(SERVICE_SETTING.ENABLED.eq(1)));
        settings.forEach(setting -> {
            try {
                applyType(setting.getServiceType(), setting);
            } catch (Exception e) {
                log.error("对外文件服务自启失败: type={}", setting.getServiceType(), e);
                markError(setting.getServiceType(), e.getMessage());
            }
        });
        running = true;
        log.info("对外文件服务管理器已启动，自动拉起 {} 个已启用服务", settings.size());
    }

    @Override
    public void stop() {
        running = false;
        try {
            sftpServerHolderProvider.ifAvailable(SftpServerHolder::stopIfRunning);
        } catch (Exception e) {
            log.warn("SFTP 服务停止异常", e);
        }
        runtimes.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 配置保存后的热生效入口
     *
     * @param type       服务类型
     * @param newSetting 新配置
     */
    public void apply(String type, ServiceSetting newSetting) {
        try {
            applyType(type, newSetting);
        } catch (Exception e) {
            // 启动失败不抛给保存动作：配置保存成功与启动失败是两件事
            log.error("对外文件服务热生效失败: type={}", type, e);
            markError(type, e.getMessage());
        }
    }

    /**
     * 实时运行状态查询
     */
    public ServiceRuntime status(String type) {
        return runtimes.computeIfAbsent(type, t -> new ServiceRuntime(STATUS_STOPPED, null, null, null));
    }

    private void applyType(String type, ServiceSetting setting) {
        boolean wantRunning = setting.getEnabled() != null && setting.getEnabled() == 1;
        ServiceRuntime current = status(type);
        boolean isRunning = STATUS_RUNNING.equals(current.status());

        if (ServiceSettingService.TYPE_SFTP.equals(type)) {
            SftpServerHolder holder = sftpServerHolderProvider.getObject();
            int port = setting.getPort() == null ? 9022 : setting.getPort();
            String bindAddress = StrUtil.emptyToDefault(setting.getBindAddress(), "0.0.0.0");
            if (wantRunning && isRunning) {
                holder.restart(port, bindAddress);
                runtimes.put(type, ServiceRuntime.running(port, bindAddress));
            } else if (wantRunning) {
                holder.start(port, bindAddress);
                runtimes.put(type, ServiceRuntime.running(port, bindAddress));
            } else if (isRunning) {
                holder.stopIfRunning();
                runtimes.put(type, new ServiceRuntime(STATUS_STOPPED, null, port, bindAddress));
            }
        } else if (ServiceSettingService.TYPE_WEBDAV.equals(type)) {
            // WebDAV 无独立 socket：enabled 标志即运行状态，filter 据此放行或 503
            if (wantRunning) {
                runtimes.put(type, ServiceRuntime.running(null, setting.getBindAddress()));
            } else {
                runtimes.put(type, new ServiceRuntime(STATUS_STOPPED, null, null, setting.getBindAddress()));
            }
        }
    }

    private void markError(String type, String message) {
        runtimes.put(type, new ServiceRuntime(STATUS_ERROR, message, null, null));
    }

    /**
     * 运行时状态快照
     */
    public record ServiceRuntime(String status, String error, Integer port, String bindAddress) {

        static ServiceRuntime running(Integer port, String bindAddress) {
            return new ServiceRuntime(STATUS_RUNNING, null, port, bindAddress);
        }
    }
}
