package com.guanghe.fs.service.webdav;

import com.guanghe.fs.service.domain.ServiceSetting;
import com.guanghe.fs.service.spi.ExternalFileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * WebDAV 对外服务 SPI 实现：复用主 HTTP 端口（/dav/** 由 DavController 承载），
 * 无独立 socket —— enabled 标志即运行状态，DavAuthFilter 据此放行或 503。
 */
@Slf4j
@Component
public class WebDavExternalService implements ExternalFileService {

    @Override
    public String type() {
        return "webdav";
    }

    @Override
    public boolean socketBased() {
        return false;
    }

    @Override
    public Integer defaultPort() {
        return null;
    }

    @Override
    public void apply(ServiceSetting setting) {
        boolean enabled = setting.getEnabled() != null && setting.getEnabled() == 1;
        log.info("WebDAV 服务状态更新: {}", enabled ? "enabled（/dav 放行）" : "disabled（/dav 返回 503）");
        // 无 socket 生命周期；filter 每请求查库判断 enabled，无需内存动作
    }

    @Override
    public void stop() {
        // 无 socket 可停；应用关闭随容器结束
    }

    @Override
    public boolean isRunning() {
        // 运行状态以配置 enabled 为准，由管理器维护
        return false;
    }
}
