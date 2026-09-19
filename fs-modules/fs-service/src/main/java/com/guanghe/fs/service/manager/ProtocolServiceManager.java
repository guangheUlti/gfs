package com.guanghe.fs.service.manager;

import com.guanghe.fs.service.domain.ServiceSetting;
import com.guanghe.fs.service.service.ServiceSettingService;
import com.guanghe.fs.service.spi.ExternalFileService;
import com.guanghe.fs.service.spi.ExternalFileServiceRegistry;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.guanghe.fs.service.domain.table.ServiceSettingTableDef.SERVICE_SETTING;

/**
 * 对外文件服务生命周期管理（SPI 驱动）：
 * - 协议实现通过 {@link ExternalFileServiceRegistry} 注册（WebDAV/SFTP/FTP…），
 *   管理器只做生命周期编排，不含任何协议特有逻辑；
 * - 应用启动时自动拉起 enabled=1 的服务；
 * - 配置变更热生效：apply 交给协议实现自行启动/重启/停止；
 * - 启动失败不抛给保存动作，状态置 error 并把异常信息带给 /list。
 */
@Slf4j
@Component
public class ProtocolServiceManager implements SmartLifecycle {

    /** 运行状态常量 */
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_STOPPED = "stopped";
    public static final String STATUS_ERROR = "error";

    private final ServiceSettingService serviceSettingService;
    private final ExternalFileServiceRegistry registry;

    /** 各服务运行状态（内存态，不落库） */
    private final Map<String, ServiceRuntime> runtimes = new ConcurrentHashMap<>();

    private volatile boolean running = false;

    public ProtocolServiceManager(ServiceSettingService serviceSettingService,
                                  ExternalFileServiceRegistry registry) {
        this.serviceSettingService = serviceSettingService;
        this.registry = registry;
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
        registry.all().values().forEach(impl -> {
            try {
                impl.stop();
            } catch (Exception e) {
                log.warn("对外文件服务停止异常: type={}", impl.type(), e);
            }
        });
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

    private void applyType(String type, ServiceSetting setting) throws Exception {
        ExternalFileService impl = registry.get(type);
        if (impl == null) {
            log.warn("未注册的对外文件服务类型，忽略: type={}", type);
            return;
        }
        boolean wantRunning = setting.getEnabled() != null && setting.getEnabled() == 1;
        ServiceRuntime current = status(type);
        boolean isRunning = STATUS_RUNNING.equals(current.status()) || impl.isRunning();
        int port = setting.getPort() == null ? 0 : setting.getPort();
        String bindAddress = setting.getBindAddress();

        if (wantRunning) {
            // 重启判断：socket 型服务端口/地址变化需重启；非 socket 型 apply 幂等
            boolean needRestart = isRunning && impl.socketBased()
                    && (current.port() != null && current.port() != port
                        || current.bindAddress() != null && !current.bindAddress().equals(bindAddress));
            if (needRestart) {
                impl.stop();
            }
            impl.apply(setting);
            runtimes.put(type, ServiceRuntime.running(impl.socketBased() ? port : null, bindAddress));
        } else {
            impl.stop();
            runtimes.put(type, new ServiceRuntime(STATUS_STOPPED, null,
                    impl.socketBased() ? port : null, bindAddress));
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
