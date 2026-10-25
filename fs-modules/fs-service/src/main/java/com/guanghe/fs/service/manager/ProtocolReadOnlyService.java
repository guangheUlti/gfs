package com.guanghe.fs.service.manager;

import com.guanghe.fs.service.domain.ServiceSetting;
import com.guanghe.fs.service.service.ServiceSettingService;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.guanghe.fs.service.domain.table.ServiceSettingTableDef.SERVICE_SETTING;

/**
 * 协议端只读开关的运行时快照（WebDAV / SFTP / FTP / OSS 共用）。
 * <p>
 * 配置存在 {@code service_settings.config_data} 的 {@code readOnly} 键里（见
 * {@link ServiceSettingService#isReadOnly}）。SFTP/FTP/OSS 的写操作发生在协议线程（NIO / MINA / 过滤器）里，
 * 每写一次文件都查库既浪费也没必要，这里按协议类型缓存布尔值：
 * <ul>
 *   <li>首次访问懒加载（查一次库）；</li>
 *   <li>管理端保存配置时由 {@code ServiceSettingController} 调 {@link #update} 立即刷新，无需重启；</li>
 *   <li>读库失败按「可写」降级并告警——单机部署下这是可接受的，不能因为配置读不出来就把网盘写死。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProtocolReadOnlyService {

    private final ServiceSettingService serviceSettingService;

    /** 服务类型 → 是否只读 */
    private final Map<String, Boolean> cache = new ConcurrentHashMap<>();

    /**
     * 指定协议当前是否只读
     *
     * @param type 服务类型（webdav/sftp/ftp/oss）
     */
    public boolean isReadOnly(String type) {
        Boolean cached = cache.get(type);
        if (cached != null) {
            return cached;
        }
        boolean value = load(type);
        cache.put(type, value);
        return value;
    }

    /** 配置保存后刷新（管理端 PUT /apis/service/{type}/config 调用） */
    public void update(String type, boolean readOnly) {
        cache.put(type, readOnly);
        log.info("协议只读开关已刷新: type={}, readOnly={}", type, readOnly);
    }

    private boolean load(String type) {
        try {
            ServiceSetting setting = serviceSettingService.getOne(
                    new QueryWrapper().where(SERVICE_SETTING.SERVICE_TYPE.eq(type)));
            return ServiceSettingService.isReadOnly(setting);
        } catch (Exception e) {
            log.warn("读取协议只读开关失败，按可写处理: type={}", type, e);
            return false;
        }
    }
}
