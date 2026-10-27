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

    /** 服务类型：AList（v3 开放 API，/api/** + /d/** 直链） */
    String TYPE_ALIST = "alist";

    /** `config_data` 里的只读标记键（各协议只读模式） */
    String CONFIG_READ_ONLY = "readOnly";

    /** `config_data` 里的游客只读标记键（仅 AList：未登录也可浏览/播放） */
    String CONFIG_ALLOW_GUEST = "allowGuest";

    /** `config_data` 里的游客视角账号键（仅 AList：匿名请求以该账号身份只读） */
    String CONFIG_GUEST_USER_ID = "guestUserId";

    /**
     * 校验服务类型是否合法（以 SPI 注册表为准动态校验；静态兜底覆盖内置协议）
     *
     * @param type 服务类型
     * @return 是否合法
     */
    static boolean isValidType(String type) {
        return TYPE_WEBDAV.equals(type) || TYPE_SFTP.equals(type) || TYPE_FTP.equals(type)
                || TYPE_OSS.equals(type) || TYPE_ALIST.equals(type);
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
        return isFlag(setting, CONFIG_READ_ONLY);
    }

    /**
     * 解析游客只读：`config_data` 中 allowGuest=true 即允许未登录的只读访问（仅 AList）。
     * <p>
     * 影视仓/TVBox 的 AList 入口不登录、也不发 token，只按「游客 + 目录密码」模型
     * 调 /api/fs/list 和 /d 直链；不开这个开关它们一个请求都过不了。
     *
     * @param setting 服务配置
     * @return 是否允许游客只读
     */
    static boolean isGuestAllowed(ServiceSetting setting) {
        return isFlag(setting, CONFIG_ALLOW_GUEST);
    }

    /**
     * 解析游客视角账号 ID：匿名请求会以该账号身份只读访问文件。
     * <p>
     * 只读接口一律按当前登录用户过滤文件，游客没有身份时无法直接查询；
     * 因此必须绑定一个具体账号，否则匿名请求会在 {@code StpUtil.getLoginIdAsString()} 处失败。
     *
     * @param setting 服务配置
     * @return 账号 ID；未配置返回 null
     */
    static String guestUserId(ServiceSetting setting) {
        return strValue(setting, CONFIG_GUEST_USER_ID);
    }

    private static boolean isFlag(ServiceSetting setting, String key) {
        return Boolean.TRUE.equals(value(setting, key));
    }

    private static String strValue(ServiceSetting setting, String key) {
        Object value = value(setting, key);
        return value == null ? null : StrUtil.trimToNull(String.valueOf(value));
    }

    private static Object value(ServiceSetting setting, String key) {
        if (setting == null || StrUtil.isBlank(setting.getConfigData())) {
            return null;
        }
        try {
            Dict config = JsonUtils.parseMap(setting.getConfigData());
            return config == null ? null : config.get(key);
        } catch (Exception e) {
            return null;
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
        return mergeFlag(rawConfig, CONFIG_READ_ONLY, readOnly);
    }

    /**
     * 在既有 `config_data` JSON 上合并游客只读标记（仅 AList 使用）。
     */
    static String mergeGuest(String rawConfig, boolean allowGuest) {
        return mergeFlag(rawConfig, CONFIG_ALLOW_GUEST, allowGuest);
    }

    /**
     * 在既有 `config_data` JSON 上合并游客视角账号；传空表示清除绑定。
     */
    static String mergeGuestUser(String rawConfig, String userId) {
        Dict config = parseConfig(rawConfig);
        if (StrUtil.isBlank(userId)) {
            config.remove(CONFIG_GUEST_USER_ID);
        } else {
            config.set(CONFIG_GUEST_USER_ID, userId.trim());
        }
        return JsonUtils.toJsonString(config);
    }

    private static String mergeFlag(String rawConfig, String key, boolean value) {
        Dict config = parseConfig(rawConfig);
        if (value) {
            config.set(key, true);
        } else {
            config.remove(key);
        }
        return JsonUtils.toJsonString(config);
    }

    private static Dict parseConfig(String rawConfig) {
        if (StrUtil.isNotBlank(rawConfig)) {
            try {
                Dict config = JsonUtils.parseMap(rawConfig);
                if (config != null) {
                    return config;
                }
            } catch (Exception ignored) {
                // 原配置是坏 JSON：按空对象重写，避免把坏数据继续往下传
            }
        }
        return Dict.create();
    }
}
