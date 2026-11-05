package com.guanghe.fs.storage.plugin.core;

import com.guanghe.fs.framework.common.exception.StorageOperationException;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import com.guanghe.fs.storage.plugin.core.model.StorageObjectEntry;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
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
     * 清理分片上传遗留的孤儿临时目录（兜底）。
     * <p>
     * 只有「分片先落到服务器本地临时目录」的插件需要实现：目录名即 uploadId，
     * 名称不在 {@code liveUploadIds}（任务表里还有记录的分片会话）且最后修改时间早于
     * {@code maxAge} 的目录直接删除。删除瞬间被文件句柄挡住时，本次会跳过，
     * 下一轮再捞，不会留下永久垃圾。
     * <p>
     * 默认实现返回 0，表示该插件不在本地落分片。
     *
     * @return 实际删除的目录数
     */
    default int cleanupOrphanTempDirs(Set<String> liveUploadIds, Duration maxAge) {
        return 0;
    }

    /**
     * 删除某次分片上传留在服务器本地的临时目录，不访问远端。
     * <p>
     * 供任务记录即将删除时调用：uploadId 是找回临时目录的唯一线索，行一旦删除就再也找不到。
     * 与 {@link #abortMultipartUpload} 的区别是本方法只动本地落盘，不会唤醒远程存储，
     * 也不会对已完成的上传发出无效的远端请求。
     * <p>
     * 不在本地落分片的插件沿用默认实现。
     *
     * @param uploadId 分片上传ID（即临时目录名）
     * @return 目录已不存在返回 true；仍被文件句柄挡住等原因残留返回 false
     */
    default boolean cleanupResidualTempDir(String uploadId) {
        return true;
    }

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
     * 是否为挂载式存储（目录树镜像真实文件系统）。
     * <p>
     * 业务代码一律用能力位判断，勿比较 identifier 字符串。
     * 远端目录树/真实目录即文件位置（SFTP、SMB、FTP、WebDAV、内存、本地存储）的插件返回 true。
     *
     * @return 是否挂载式
     */
    default boolean isMountMode() {
        return false;
    }

    /**
     * 是否为直读式挂载（本地直读 LocalDirect 等）：不建后台全树扫描索引，
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
     * 是否为无状态远程存储（SFTP、SMB、FTP、WebDAV、内存、本地存储等）：文件条目完全以
     * 远端服务器或真实目录为准，不落 file_info、不建索引，浏览/上传/下载/预览/分享均按路径直接操作。
     * <p>
     * 无状态平台同时满足 {@link #isMountMode()}=true（保留挂载类能力的删除/清理语义），
     * 但不应参与挂载点创建、扫描与按层对账。
     *
     * @return 是否无状态远程
     */
    default boolean isStatelessRemote() {
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

