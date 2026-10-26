package com.guanghe.fs.service.alist;

import com.guanghe.fs.service.domain.ServiceSetting;
import com.guanghe.fs.service.spi.ExternalFileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AList（v3 开放 API）对外服务 SPI 实现：复用主 HTTP 端口，
 * {@code /api/**} 由 {@link AlistAuthController}/{@link AlistFsController} 承载，
 * {@code /d/**} 直链播放由 {@link AlistRawController} 承载。
 * <p>
 * 无独立 socket —— enabled 标志即运行状态，{@link AlistAuthFilter} 据此放行或 503。
 */
@Slf4j
@Component
public class AlistExternalService implements ExternalFileService {

    @Override
    public String type() {
        return "alist";
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
        log.info("AList 服务状态更新: {}", enabled ? "enabled（/api 放行）" : "disabled（/api 返回 503）");
    }

    @Override
    public void stop() {
        // 无 socket 可停；应用关闭随容器结束
    }

    @Override
    public boolean isRunning() {
        return false;
    }
}
