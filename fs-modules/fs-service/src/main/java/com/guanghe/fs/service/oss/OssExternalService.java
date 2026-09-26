package com.guanghe.fs.service.oss;

import com.guanghe.fs.service.domain.ServiceSetting;
import com.guanghe.fs.service.spi.ExternalFileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * OSS（S3 兼容）对外服务 SPI 实现：复用主 HTTP 端口（/oss/** 由 OssController 承载），
 * 无独立 socket —— enabled 标志即运行状态，OssAuthFilter 据此放行或 503。
 * SigV4 认证由 OssAuthFilter 完成（AES-GCM 可逆存储 Secret Key）。
 */
@Slf4j
@Component
public class OssExternalService implements ExternalFileService {

    @Override
    public String type() {
        return "oss";
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
        log.info("OSS 服务状态更新: {}", enabled ? "enabled（/oss 放行）" : "disabled（/oss 返回 503）");
    }

    @Override
    public void stop() {
        // 无 socket 可停；分片会话随应用关闭由 JVM 临时文件回收
    }

    @Override
    public boolean isRunning() {
        return false;
    }
}
