package com.guanghe.fs.service.service;

import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.framework.common.utils.JsonUtils;
import com.guanghe.fs.service.domain.ServiceSetting;
import com.mybatisflex.core.service.IService;

/**
 * 对外文件服务配置业务接口
 */
public interface ServiceSettingService extends IService<ServiceSetting> {

    /** 服务类型：WebDAV */
    String TYPE_WEBDAV = "webdav";

    /** 服务类型：SFTP */
    String TYPE_SFTP = "sftp";

    /** 服务类型：FTP */
    String TYPE_FTP = "ftp";

    /** 服务类型：OSS（S3 兼容网关，/oss/**） */
    String TYPE_OSS = "oss";

    /** `config_data` 里的只读标记键（WebDAV 只读模式） */
    String CONFIG_READ_ONLY = "readOnly";

    /**
     * 校验服务类型是否合法（以 SPI 注册表为准动态校验；静态兜底覆盖内置协议）
     *
     * @param type 服务类型
     * @return 是否合法
     */
    static boolean isValidType(String type) {
        return TYPE_WEBDAV.equals(type) || TYPE_SFTP.equals(type) || TYPE_FTP.equals(type) || TYPE_OSS.equals(type);
    }

    /**
     * 解析只读模式：`config_data` 中 readOnly=true 即只读。
     * <p>
     * 脏数据（非法 JSON）按「可写」处理——配置读不出来时不能把服务锁死，
     * 管理员仍能进界面把它关掉。
     *
     * @param setting 服务配置
     * @return 是否只读
     */
    static boolean isReadOnly(ServiceSetting setting) {
        if (setting == null || StrUtil.isBlank(setting.getConfigData())) {
            return false;
        }
        try {
            Dict config = JsonUtils.parseMap(setting.getConfigData());
            return config != null && Boolean.TRUE.equals(config.get(CONFIG_READ_ONLY));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 在既有 `config_data` JSON 上合并只读标记：保留其它扩展键（如未来 sftp 主机密钥路径），
     * 关掉只读时移除该键而不是写 false，保持配置干净。
     *
     * @param rawConfig 原 config_data（可为空）
     * @param readOnly  是否只读
     * @return 合并后的 JSON 字符串
     */
    static String mergeReadOnly(String rawConfig, boolean readOnly) {
        Dict config = null;
        if (StrUtil.isNotBlank(rawConfig)) {
            try {
                config = JsonUtils.parseMap(rawConfig);
            } catch (Exception ignored) {
                // 原配置是坏 JSON：按空对象重写，避免把坏数据继续往下传
            }
        }
        if (config == null) {
            config = Dict.create();
        }
        if (readOnly) {
            config.set(CONFIG_READ_ONLY, true);
        } else {
            config.remove(CONFIG_READ_ONLY);
        }
        return JsonUtils.toJsonString(config);
    }
}
