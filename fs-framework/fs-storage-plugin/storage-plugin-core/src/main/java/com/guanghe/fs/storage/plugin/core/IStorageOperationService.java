package com.guanghe.fs.storage.plugin.core;

import com.guanghe.fs.storage.plugin.core.config.StorageConfig;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
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
     * 关闭资源
     */
    @Override
    default void close() throws IOException {
        // 默认空实现
    }
}

