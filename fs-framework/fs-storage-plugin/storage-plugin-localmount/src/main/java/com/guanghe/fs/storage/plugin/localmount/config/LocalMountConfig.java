package com.guanghe.fs.storage.plugin.localmount.config;

import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import lombok.Data;

/**
 * 本地挂载配置
 *
 * @Author: guangheUlti
 * @Date: 2026/09/09
 */
@Data
public class LocalMountConfig {

    /** 挂载点显示名（用户根目录下的挂载目录名，默认"本地挂载"） */
    private String mountName;

    /** 挂载根路径（真实绝对路径，必填；支持 d:/、d:\\、d://、D:\\x\\、带不带尾斜杠等常用写法，归一化见 {@link #normalizedRootPath()}） */
    private String rootPath;

    /** 是否跟随符号链接（"true" 开启，默认 false；前端以开关提交布尔值） */
    private String followSymlinks;

    /** 扫描间隔秒数（可选；定时重扫周期，留空用全局默认 5 分钟；开启实时监听时退化为兜底全扫周期） */
    private String rescanIntervalSeconds;

    /** 实时监听（"true" 开启，默认关闭）：OS 目录变更事件秒级触发同步，无需等定时扫描 */
    private String realtimeWatch;

    /** 是否开启落盘加密（"true" 开启） */
    private String encryptionEnabled;

    /** 落盘加密口令（开启加密时必填） */
    private String encryptionSecret;

    /** 从 StorageConfig 转换为配置对象 */
    public static LocalMountConfig toObject(StorageConfig config) {
        return config.toObject(LocalMountConfig.class);
    }

    /** 是否开启落盘加密（容错解析，非 "true" 一律视为关闭） */
    public boolean isEncryptionEnabled() {
        return "true".equalsIgnoreCase(encryptionEnabled == null ? "" : encryptionEnabled.trim());
    }

    /** 是否跟随符号链接（容错解析，非 "true" 一律视为关闭；布尔 true 由 Jackson 宽松转成 "true"） */
    public boolean isFollowSymlinks() {
        return "true".equalsIgnoreCase(followSymlinks == null ? "" : followSymlinks.trim());
    }

    /** 是否开启实时监听（容错解析，非 "true" 一律视为关闭；布尔 true 由 Jackson 宽松转成 "true"） */
    public boolean isRealtimeWatch() {
        return "true".equalsIgnoreCase(realtimeWatch == null ? "" : realtimeWatch.trim());
    }

    /**
     * 定时重扫间隔（秒）：解析失败/非正数返回 null（表示未配置，用全局默认）。
     */
    public Long parsedRescanIntervalSeconds() {
        try {
            if (rescanIntervalSeconds == null || rescanIntervalSeconds.trim().isEmpty()) {
                return null;
            }
            long v = Long.parseLong(rescanIntervalSeconds.trim());
            return v > 0 ? v : null;
            // 非数字一律按未配置处理
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 挂载根路径归一化：兼容常用写法后统一为正斜杠绝对路径。
     * <ul>
     *   <li>反斜杠转正斜杠（D:\\a\\b → D:/a/b）；</li>
     *   <li>折叠连续斜杠（d://a → d:/a）；</li>
     *   <li>盘符大写归一（d:/a → D:/a）；</li>
     *   <li>纯盘符视为盘根（d: → D:/）：Java 会把无斜杠盘符解析为「该盘当前工作目录」，与用户直觉不符；</li>
     *   <li>盘符后缺斜杠补齐（d:work → D:/work，同理避免驱动器相对路径陷阱）；</li>
     *   <li>去掉尾部斜杠（D:/a/ → D:/a）。</li>
     * </ul>
     */
    public String normalizedRootPath() {
        if (rootPath == null) {
            return null;
        }
        String p = rootPath.trim().replace('\\', '/').replaceAll("/{2,}", "/");
        if (p.isEmpty()) {
            return p;
        }
        // 盘符大写归一
        if (p.length() >= 2 && p.charAt(1) == ':') {
            p = Character.toUpperCase(p.charAt(0)) + p.substring(1);
            // 盘符后缺斜杠：补齐（避免驱动器相对路径解析到「盘符当前工作目录」）
            if (p.length() > 2 && p.charAt(2) != '/') {
                p = p.substring(0, 2) + "/" + p.substring(2);
            }
        }
        // 去掉尾部斜杠
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        // 纯盘符（"D:"）视为盘根
        if (p.length() == 2 && p.charAt(1) == ':') {
            p = p + "/";
        }
        return p;
    }
}
