package com.guanghe.fs.service.manager;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.service.domain.ServiceSetting;
import com.guanghe.fs.service.service.ServiceSettingService;
import com.guanghe.fs.storage.domain.StorageSetting;
import com.guanghe.fs.storage.plugin.core.context.StoragePlatformContext;
import com.guanghe.fs.storage.plugin.core.context.StoragePlatformContextHolder;
import com.guanghe.fs.storage.plugin.core.utils.StorageUtils;
import com.guanghe.fs.storage.service.StorageSettingService;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.guanghe.fs.service.domain.table.ServiceSettingTableDef.SERVICE_SETTING;

/**
 * 对外协议的存储作用域：每个服务（WebDAV/SFTP/FTP/OSS/AList）固定落到服务设置里指定的存储。
 * <p>
 * 外部协议请求不经过 {@code StoragePlatformInterceptor}（它只挂 /apis/**），
 * 因此在协议入口把该存储写入 {@link StoragePlatformContextHolder}，
 * 之后 FileInfoService 的读写删全部按这个上下文走。
 * <p>
 * 未配置、配置的存储被删除/禁用时回退到内置系统存储——升级后旧配置行为不变。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExternalStorageScopeService {

    /** 缓存时长：存储被禁用/删除后最多 30 秒自动回退到 Local */
    private static final long CACHE_TTL_MS = 30_000L;

    private final ServiceSettingService serviceSettingService;
    private final StorageSettingService storageSettingService;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(String settingId, long loadedAt) {
    }

    /**
     * 服务当前绑定的存储配置 ID（未配置为 Local）
     */
    public String storageSettingId(String serviceType) {
        Cached cached = cache.get(serviceType);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAt() < CACHE_TTL_MS) {
            return cached.settingId();
        }
        String resolved = resolve(serviceType);
        cache.put(serviceType, new Cached(resolved, now));
        return resolved;
    }

    /**
     * 把服务存储写入当前线程上下文（须在登录桥接之后调用）
     */
    public void apply(String serviceType) {
        String settingId = storageSettingId(serviceType);
        StoragePlatformContext context = StoragePlatformContext.builder()
                .configId(StorageUtils.normalizeConfigId(settingId))
                .userId(StpUtil.getLoginIdAsString())
                .build();
        StoragePlatformContextHolder.setContext(context);
    }

    public void clear() {
        StoragePlatformContextHolder.clear();
    }

    /** 服务配置保存后立即失效缓存，下一条请求生效 */
    public void invalidate(String serviceType) {
        cache.remove(serviceType);
    }

    private String resolve(String serviceType) {
        try {
            ServiceSetting setting = serviceSettingService.getOne(
                    new QueryWrapper().where(SERVICE_SETTING.SERVICE_TYPE.eq(serviceType)));
            String settingId = ServiceSettingService.storageSettingId(setting);
            if (StorageUtils.isLocalConfig(settingId)) {
                return StorageUtils.LOCAL_PLATFORM_IDENTIFIER;
            }
            StorageSetting storage = storageSettingService.getById(settingId);
            if (storage == null || storage.getEnabled() == null || storage.getEnabled() != 1) {
                log.warn("对外服务绑定的存储不可用，回退本地存储: serviceType={}, settingId={}",
                        serviceType, settingId);
                return StorageUtils.LOCAL_PLATFORM_IDENTIFIER;
            }
            return settingId;
        } catch (Exception e) {
            log.warn("解析对外服务存储失败，回退本地存储: serviceType={}", serviceType, e);
            return StorageUtils.LOCAL_PLATFORM_IDENTIFIER;
        }
    }
}
