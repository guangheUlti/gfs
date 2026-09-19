package com.guanghe.fs.service.spi;

import com.guanghe.fs.service.domain.ServiceSetting;

/**
 * 对外文件服务 SPI。
 * <p>
 * 与存储插件的 {@code IStorageOperationService} 同一设计思想：协议实现自声明能力，
 * 管理器按类型注册表分发，新增协议（FTP/…）只需新增实现类，不改管理器与控制器。
 * <p>
 * 生命周期语义：
 * <ul>
 *   <li>{@link #apply(ServiceSetting)} 是唯一的配置热生效入口：实现按 enabled/port/bindAddress
 *       自行决定启动/重启/停止，启动失败抛异常由管理器转为 error 状态（不抛给保存动作）；</li>
 *   <li>{@link #stop()} 供应用关闭时强制停止；</li>
 *   <li>{@link #socketBased()} = false 表示复用主 HTTP 端口（如 WebDAV），仅开关语义，
 *       配置页不展示端口/地址表单，运行状态即 enabled 标志。</li>
 * </ul>
 */
public interface ExternalFileService {

    /** 服务类型标识，对应 service_settings.service_type（webdav / sftp / ftp） */
    String type();

    /**
     * 是否有独立 socket 监听。false = 复用主 HTTP 端口（仅开关语义，无端口/地址配置）。
     */
    boolean socketBased();

    /** 默认监听端口（配置未设置时用）；socketBased=false 返回 null */
    Integer defaultPort();

    /**
     * 应用配置（热生效）：按 setting 自行启动/重启/停止。
     *
     * @param setting 最新配置
     * @throws Exception 启动失败（管理器捕获后置 error 状态）
     */
    void apply(ServiceSetting setting) throws Exception;

    /** 强制停止（应用关闭 / restart 前置） */
    void stop() throws Exception;

    /** 当前是否运行中（用于重启判断与状态展示兜底） */
    boolean isRunning();
}
