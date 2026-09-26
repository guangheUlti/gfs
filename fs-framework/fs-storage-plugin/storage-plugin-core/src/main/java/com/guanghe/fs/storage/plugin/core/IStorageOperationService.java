package com.guanghe.fs.storage.plugin.core;

import com.guanghe.fs.framework.common.exception.StorageOperationException;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import com.guanghe.fs.storage.plugin.core.model.StorageObjectEntry;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 存储平台操作接口
 * 定义了存储平台的基本操作，如上传、下载、删除等
 *
 * @Author: guangheUlti
 * @Date: 2026/01/12 22:06
 */
public interface IStorageOperationService extends Closeable {

    /**
     * 创建配置化实例（工厂方法）
     *
     * @param config 存储配置
     * @return 配置化的实例
     */
    IStorageOperationService createConfiguredInstance(StorageConfig config);

    /**
     * 上传文件
     *
     * @param inputStream 文件流
     * @param objectKey   对象键（文件路径）
     * @return 文件访问URL
     */
    void uploadFile(InputStream inputStream, String objectKey);

    /**
     * 下载文件
     *
     * @param objectKey 对象键
     * @return 文件流
     */
    InputStream downloadFile(String objectKey);

    /**
     * 按字节范围读取文件（用于分片下载）
     *
     * @param objectKey 对象键（文件路径）
     * @param startByte 起始字节位置（包含，从0开始）
     * @param endByte   结束字节位置（包含）
     * @return 指定范围的文件流
     */
    InputStream downloadFileRange(String objectKey, long startByte, long endByte);

    /**
     * 删除文件
     *
     * @param objectKey 对象键
     * @return 是否成功
     */
    void deleteFile(String objectKey);

    /**
     * 重命名文件
     *
     * @param objectKey     原始对象名
     * @param destObjectKey 目标对象名
     */
    void rename(String objectKey, String destObjectKey);

    /**
     * 获取文件访问URL
     *
     * @param objectKey     对象键
     * @param expireSeconds 过期时间（秒），null表示永久
     * @return 访问URL
     */
    String getFileUrl(String objectKey, Integer expireSeconds);

    /**
     * 获取文件流
     *
     * @param objectKey
     * @return
     */
    InputStream getFileStream(String objectKey);

    /**
     * 检查文件是否存在
     *
     * @param objectKey 对象键
     * @return 是否存在
     */
    boolean isFileExist(String objectKey);

    /**
     * 初始化分片上传
     *
     * @param objectKey 对象键
     * @param mimeType  文件类型
     * @return 全局唯一上传ID
     */
    String initiateMultipartUpload(String objectKey, String mimeType);

    /**
     * 上传分片
     *
     * @param objectKey       对象键
     * @param uploadId        上传ID
     * @param partNumber      分片序号
     * @param partSize        分片大小
     * @param partInputStream 分片流
     * @return ETag
     */
    String uploadPart(String objectKey, String uploadId, int partNumber,
                      long partSize, InputStream partInputStream);

    /**
     * 列举已上传的所有分片
     *
     * @param objectKey 对象键
     * @param uploadId  上传ID
     * @return
     */
    Set<Integer> listParts(String objectKey, String uploadId);

    /**
     * 完成分片上传
     *
     * @param objectKey 对象键
     * @param uploadId  上传ID
     * @param partETags 分片ETag列表
     * @return 文件访问URL
     */
    void completeMultipartUpload(String objectKey, String uploadId,
                                 List<Map<String, Object>> partETags);

    /**
     * 取消分片上传
     *
     * @param objectKey 对象键
     * @param uploadId  上传ID
     */
    void abortMultipartUpload(String objectKey, String uploadId);

    /**
     * 获取存储底座当前可写入的剩余字节数。
     * <p>
     * 仅本地磁盘类存储有确定答案；对象存储（OSS/S3 等）无本地磁盘概念，
     * 沿用默认实现返回 null，表示容量不可知。
     *
     * @return 剩余可用字节数，容量不可知时返回 null
     */
    default Long getAvailableSpace() {
        return null;
    }

    /**
     * 当前环境还可申请的最大配置容量（字节）。供「添加存储」表单实时提示。
     * <p>
     * 与具体配置实例无关（可在原型上调用，无需 {@code ensureNotPrototype()}）：
     * 返回「还能新建多大容量的实例」的保守估计；无法估计时返回 null，前端不展示。
     *
     * @return 可申请最大容量字节数，不可知返回 null
     */
    default Long getMaxConfigurableCapacity() {
        return null;
    }

    /**
     * 挂载根路径（仅本地磁盘类挂载实现，供实时监听注册 OS 目录事件用）。
     *
     * @return 挂载根绝对路径；非本地磁盘类平台返回 null
     */
    default Path mountRootPath() {
        return null;
    }

    /**
     * 定时重扫间隔（秒）。仅挂载式实现。
     * <p>
     * 空表示「用全局默认」：调用方以 {@code fs.file.mount.scan-interval}（毫秒）兑底。
     * 开启实时监听（{@link #supportsWatchRealtime()}）时，本间隔自动退化为兑底全扫周期，
     * 覆盖监听丢失（WatchService 异常、事件丢失、网络盘等场景）。
     *
     * @return 间隔秒数；null 表示未配置
     */
    default Long rescanIntervalSeconds() {
        return null;
    }

    /**
     * 是否支持 OS 级实时监听（WatchService）。
     * <p>
     * 与「用户是否开启」无关，只描述插件平台能力：本地磁盘类挂载为 true，
     * SMB/SFTP/FTP 等网络协议挂载为 false（协议无目录变更推送）。
     *
     * @return 平台是否支持实时监听
     */
    default boolean isRealtimeWatchSupported() {
        return false;
    }

    /**
     * 用户是否开启了实时监听（需 {@link #isRealtimeWatchSupported()} 为 true 才有意义）。
     * <p>
     * 开启后：外部变更经 OS 事件秒级触发增量对账；定时扫描退化为兑底
     * （周期取 {@link #rescanIntervalSeconds()}，未配置则用全局默认）。
     * 关闭后：仅按间隔定时全扫。
     *
     * @return 是否开启实时监听
     */
    default boolean supportsWatchRealtime() {
        return false;
    }

    /**
     * 是否为挂载式存储（目录树镜像真实文件系统）。
     * <p>
     * 业务代码一律用能力位判断，勿比较 identifier 字符串。
     * 仅 {@code LocalMount} 类挂载插件返回 true。
     *
     * @return 是否挂载式
     */
    default boolean isMountMode() {
        return false;
    }

    /**
     * 是否为「本地挂载」（LocalDirect，直读式）存储：不建后台扫描索引，
     * 浏览时实时列目录并按层对账索引行，每次增删改查都直接作用于真实文件系统。
     * <p>
     * 业务代码一律用能力位判断，勿比较 identifier 字符串；
     * 直读平台同时满足 {@link #isMountMode()}=true（复用写穿透链路）。
     *
     * @return 是否直读式访问
     */
    default boolean isDirectAccess() {
        return false;
    }

    /**
     * 是否开启了落盘加密。
     * <p>
     * 开启后物理对象是密文：秒传/去重复用对象、以及任何绕过存储插件直接读物理文件的路径都不再安全或不再成立，
     * 业务侧据此关闭复用逻辑（如秒传）。加解密对插件接口调用方透明。
     *
     * @return 是否落盘加密
     */
    default boolean isEncryptionEnabled() {
        return false;
    }

    /**
     * 创建目录（含逐级父链）。仅挂载式实现
     *
     * @param dirKey 目录相对键（posix '/' 分隔、无前导 '/'）
     */
    default void mkdirDirectory(String dirKey) {
        throw new StorageOperationException("当前存储平台不支持创建目录");
    }

    /**
     * 列举一层目录内容（挂载同步器用）。仅挂载式实现
     *
     * @param dirKey 目录相对键，posix '/' 分隔、无前导 '/'，空串表示根
     * @return 一层目录条目列表
     */
    default List<StorageObjectEntry> listObjects(String dirKey) {
        throw new StorageOperationException("当前存储平台不支持目录列举");
    }

    /**
     * 删除目录（递归，含内部文件）。仅挂载式实现，供永久删除清理真实目录
     *
     * @param dirKey 目录相对键
     */
    default void deleteDirectory(String dirKey) {
        throw new StorageOperationException("当前存储平台不支持删除目录");
    }

    /**
     * 关闭资源
     */
    @Override
    default void close() throws IOException {
        // 默认空实现
    }
}

