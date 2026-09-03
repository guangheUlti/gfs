package com.guanghe.fs.storage.plugin.aliyunoss;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.model.*;
import com.guanghe.fs.framework.common.exception.StorageConfigException;
import com.guanghe.fs.framework.common.exception.StorageOperationException;
import com.guanghe.fs.storage.plugin.aliyunoss.config.AliyunOssConfig;
import com.guanghe.fs.storage.plugin.core.AbstractStorageOperationService;
import com.guanghe.fs.storage.plugin.core.annotation.StoragePlugin;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.*;

/**
 * 阿里云 OSS 存储插件实现
 *
 * @Author: guangheUlti
 * @Date: 2026/01/12 22:06
 */
@Slf4j
@StoragePlugin(
        identifier = "AliyunOSS",
        name = "阿里云OSS",
        description = "阿里云对象存储 OSS（Object Storage Service）是一款海量、安全、低成本、高可靠的云存储服务",
        icon = "icon-aliyun1",
        link = "https://www.aliyun.com/product/oss",
        schemaResource = "classpath:schema/aliyun-oss-schema.json"
)
public class AliyunOssStorageServiceImpl extends AbstractStorageOperationService {

    private OSS ossClient;

    private String bucketName;

    @SuppressWarnings("unused")
    public AliyunOssStorageServiceImpl() {
        super();
    }

    @SuppressWarnings("unused")
    public AliyunOssStorageServiceImpl(StorageConfig config) {
        super(config);
    }

    @Override
    protected void validateConfig(StorageConfig config) {
        try {
            AliyunOssConfig aliyunOssConfig = config.toObject(AliyunOssConfig.class);

            if (aliyunOssConfig == null) {
                throw new StorageConfigException("存储平台配置错误：阿里云OSS配置转换失败，配置对象为空");
            }

            if (aliyunOssConfig.getEndpoint() == null || aliyunOssConfig.getEndpoint().trim().isEmpty()) {
                throw new StorageConfigException("存储平台配置错误：阿里云OSS endpoint 不能为空");
            }

            if (aliyunOssConfig.getAccessKey() == null || aliyunOssConfig.getAccessKey().trim().isEmpty()) {
                throw new StorageConfigException("存储平台配置错误：阿里云OSS accessKey 不能为空");
            }

            if (aliyunOssConfig.getSecretKey() == null || aliyunOssConfig.getSecretKey().trim().isEmpty()) {
                throw new StorageConfigException("存储平台配置错误：阿里云OSS secretKey 不能为空");
            }

            if (aliyunOssConfig.getBucket() == null || aliyunOssConfig.getBucket().trim().isEmpty()) {
                throw new StorageConfigException("存储平台配置错误：阿里云OSS bucket 不能为空");
            }
        } catch (Exception e) {
            log.error("阿里云OSS配置验证失败: {}", e.getMessage(), e);
            throw new StorageConfigException("当前存储平台配置错误：客户端校验失败");
        }
    }

    @Override
    protected void initialize(StorageConfig config) {
        try {
            AliyunOssConfig aliyunOssConfig = config.toObject(AliyunOssConfig.class);
            DefaultCredentialProvider provider = new DefaultCredentialProvider(aliyunOssConfig.getAccessKey(),
                    aliyunOssConfig.getSecretKey());
            ClientBuilderConfiguration clientBuilderConfiguration = new ClientBuilderConfiguration();
            this.ossClient = OSSClientBuilder.create()
                    .credentialsProvider(provider)
                    .clientConfiguration(clientBuilderConfiguration)
                    .region(aliyunOssConfig.getRegion())
                    .endpoint(aliyunOssConfig.getEndpoint())
                    .build();
            this.bucketName = aliyunOssConfig.getBucket();
            log.info("{} 阿里云OSS客户端初始化成功: endpoint={}, bucket={}",
                    getLogPrefix(), aliyunOssConfig.getEndpoint(), this.bucketName);
        } catch (StorageConfigException e) {
            // 重新抛出配置异常
            throw e;
        } catch (Exception e) {
            log.error("阿里云OSS初始化失败: {}", e.getMessage(), e);
            throw new StorageConfigException("当前存储平台配置错误：客户端初始化失败");
        }

    }

    @Override
    public void uploadFile(InputStream inputStream, String objectKey) {
        ensureNotPrototype();
        try {
            PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, objectKey, inputStream);
            ossClient.putObject(putObjectRequest);
            log.debug("{} 文件上传成功: objectKey={}", getLogPrefix(), objectKey);
        } catch (OSSException e) {
            log.error("{} 文件上传失败: objectKey={}, errorCode={}, errorMessage={}",
                    getLogPrefix(), objectKey, e.getErrorCode(), e.getErrorMessage(), e);
            throw new StorageOperationException(
                    String.format("阿里云OSS文件上传失败 [%s]: %s", e.getErrorCode(), e.getErrorMessage()), e);
        } catch (Exception e) {
            log.error("{} 文件上传失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("阿里云OSS文件上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream downloadFile(String objectKey) {
        ensureNotPrototype();
        try {
            OSSObject ossObject = ossClient.getObject(bucketName, objectKey);
            if (ossObject == null) {
                throw new StorageOperationException("文件不存在: " + objectKey);
            }
            log.debug("{} 文件下载成功: objectKey={}", getLogPrefix(), objectKey);
            return ossObject.getObjectContent();
        } catch (OSSException e) {
            if ("NoSuchKey".equals(e.getErrorCode())) {
                log.warn("{} 文件不存在: objectKey={}", getLogPrefix(), objectKey);
                throw new StorageOperationException("文件不存在: " + objectKey, e);
            }
            log.error("{} 文件下载失败: objectKey={}, errorCode={}, errorMessage={}",
                    getLogPrefix(), objectKey, e.getErrorCode(), e.getErrorMessage(), e);
            throw new StorageOperationException(
                    String.format("阿里云OSS文件下载失败 [%s]: %s", e.getErrorCode(), e.getErrorMessage()), e);
        } catch (Exception e) {
            log.error("{} 文件下载失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("阿里云OSS文件下载失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream downloadFileRange(String objectKey, long startByte, long endByte) {
        ensureNotPrototype();
        try {
            if (startByte < 0 || endByte < startByte) {
                throw new StorageOperationException("无效的字节范围: startByte=" + startByte + ", endByte=" + endByte);
            }

            // 创建GetObjectRequest并设置Range
            GetObjectRequest getObjectRequest = new GetObjectRequest(bucketName, objectKey);
            getObjectRequest.setRange(startByte, endByte);

            OSSObject ossObject = ossClient.getObject(getObjectRequest);
            if (ossObject == null) {
                throw new StorageOperationException("文件不存在: " + objectKey);
            }

            log.debug("{} Range读取文件成功: objectKey={}, startByte={}, endByte={}",
                    getLogPrefix(), objectKey, startByte, endByte);

            return ossObject.getObjectContent();
        } catch (OSSException e) {
            if ("NoSuchKey".equals(e.getErrorCode())) {
                log.warn("{} 文件不存在: objectKey={}", getLogPrefix(), objectKey);
                throw new StorageOperationException("文件不存在: " + objectKey, e);
            }
            log.error("{} Range读取文件失败: objectKey={}, startByte={}, endByte={}, errorCode={}, errorMessage={}",
                    getLogPrefix(), objectKey, startByte, endByte, e.getErrorCode(), e.getErrorMessage(), e);
            throw new StorageOperationException(
                    String.format("阿里云OSS Range读取文件失败 [%s]: %s", e.getErrorCode(), e.getErrorMessage()), e);
        } catch (Exception e) {
            log.error("{} Range读取文件失败: objectKey={}, startByte={}, endByte={}",
                    getLogPrefix(), objectKey, startByte, endByte, e);
            throw new StorageOperationException("阿里云OSS Range读取文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteFile(String objectKey) {
        ensureNotPrototype();
        try {
            ossClient.deleteObject(bucketName, objectKey);
            log.debug("{} 文件删除成功: objectKey={}", getLogPrefix(), objectKey);
        } catch (OSSException e) {
            if ("NoSuchKey".equals(e.getErrorCode())) {
                log.debug("{} 文件不存在，视为删除成功: objectKey={}", getLogPrefix(), objectKey);
            }
            log.error("{} 文件删除失败: objectKey={}, errorCode={}, errorMessage={}",
                    getLogPrefix(), objectKey, e.getErrorCode(), e.getErrorMessage(), e);
            throw new StorageOperationException(
                    String.format("阿里云OSS文件删除失败 [%s]: %s", e.getErrorCode(), e.getErrorMessage()), e);
        } catch (Exception e) {
            log.error("{} 文件删除失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("阿里云OSS文件删除失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void rename(String objectKey, String destObjectKey) {
        ensureNotPrototype();
        try {
            //拷贝新文件至同目录下
            ossClient.copyObject(bucketName, objectKey, bucketName, destObjectKey);
            // 删除原key
            ossClient.deleteObject(bucketName, objectKey);
            log.debug("{} 文件重命名成功: sourceKey={}, newKey={}", getLogPrefix(), objectKey, destObjectKey);
        } catch (OSSException e) {
            log.error("{} 文件重命名成功失败: sourceKey={}, newKey={}, errorCode={}, errorMessage={}",
                    getLogPrefix(), objectKey, destObjectKey, e.getErrorCode(), e.getErrorMessage(), e);
            throw new StorageOperationException(
                    String.format("阿里云OSS重命名失败 [%s]: %s", e.getErrorCode(), e.getErrorMessage()), e);
        } catch (Exception e) {
            log.error("{} 重命名失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("阿里云OSS重命名失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String getFileUrl(String objectKey, Integer expireSeconds) {
        ensureNotPrototype();
        try {
            java.util.Date expiration = expireSeconds != null
                    ? new java.util.Date(System.currentTimeMillis() + expireSeconds * 1000L)
                    : null;

            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucketName, objectKey);
            if (expiration != null) {
                request.setExpiration(expiration);
            }

            java.net.URL url = ossClient.generatePresignedUrl(request);
            return url.toString();
        } catch (OSSException e) {
            log.error("{} 生成文件URL失败: objectKey={}, errorCode={}, errorMessage={}",
                    getLogPrefix(), objectKey, e.getErrorCode(), e.getErrorMessage(), e);
            throw new StorageOperationException(
                    String.format("阿里云OSS生成文件URL失败 [%s]: %s", e.getErrorCode(), e.getErrorMessage()), e);
        } catch (Exception e) {
            log.error("{} 生成文件URL失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("阿里云OSS生成文件URL失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream getFileStream(String objectKey) {
        GetObjectRequest getObjectRequest = new GetObjectRequest(bucketName, objectKey);
        OSSObject ossObject = ossClient.getObject(getObjectRequest);
        return ossObject.getObjectContent();
    }

    @Override
    public boolean isFileExist(String objectKey) {
        ensureNotPrototype();
        return ossClient.doesObjectExist(bucketName, objectKey);
    }

    @Override
    public String initiateMultipartUpload(String objectKey, String mimeType) {
        ensureNotPrototype();
        try {
            // 初始化分片。
            InitiateMultipartUploadRequest request = new InitiateMultipartUploadRequest(bucketName, objectKey);
            // 创建ObjectMetadata并设置Content-Type。
            ObjectMetadata metadata = new ObjectMetadata();
            if (metadata.getContentType() == null) {
                metadata.setContentType(mimeType);
            }
            InitiateMultipartUploadResult upResult = ossClient.initiateMultipartUpload(request);
            return upResult.getUploadId();
        } catch (Exception e) {
            throw new StorageOperationException("阿里云OSS生成文件URL失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String uploadPart(String objectKey, String uploadId, int partNumber, long partSize, InputStream partInputStream) {
        ensureNotPrototype();

        try {
            // 创建UploadPartRequest
            UploadPartRequest uploadPartRequest = new UploadPartRequest();
            uploadPartRequest.setBucketName(bucketName);
            uploadPartRequest.setKey(objectKey);
            uploadPartRequest.setUploadId(uploadId);
            uploadPartRequest.setPartNumber(partNumber + 1);
            uploadPartRequest.setPartSize(partSize);
            uploadPartRequest.setInputStream(partInputStream);

            // 上传分片
            UploadPartResult uploadPartResult = ossClient.uploadPart(uploadPartRequest);
            String etag = uploadPartResult.getETag();

            log.debug("{} 分片上传成功: objectKey={}, partNumber={}, etag={}",
                    getLogPrefix(), objectKey, partNumber, etag);

            return etag;

        } catch (OSSException e) {
            log.error("{} 分片上传失败: objectKey={}, partNumber={}, errorCode={}, errorMessage={}",
                    getLogPrefix(), objectKey, partNumber, e.getErrorCode(), e.getErrorMessage(), e);
            throw new StorageOperationException(
                    String.format("阿里云OSS分片上传失败 [%s]: %s", e.getErrorCode(), e.getErrorMessage()), e);
        } catch (Exception e) {
            log.error("{} 分片上传失败: objectKey={}, partNumber={}", getLogPrefix(), objectKey, partNumber, e);
            throw new StorageOperationException("阿里云OSS分片上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Set<Integer> listParts(String objectKey, String uploadId) {
        ensureNotPrototype();

        try {
            Set<Integer> uploadedParts = new HashSet<>();

            // 创建ListPartsRequest
            ListPartsRequest listPartsRequest = new ListPartsRequest(bucketName, objectKey, uploadId);

            PartListing partListing;
            do {
                // 列举分片
                partListing = ossClient.listParts(listPartsRequest);

                // 收集分片号
                for (PartSummary part : partListing.getParts()) {
                    uploadedParts.add(part.getPartNumber());
                }

                // 设置下一页标记
                listPartsRequest.setPartNumberMarker(partListing.getNextPartNumberMarker());

            } while (partListing.isTruncated()); // 如果还有更多分片，继续循环
            log.debug("{} 已上传分片列表: objectKey={}, uploadId={}, parts={}",
                    getLogPrefix(), objectKey, uploadId, uploadedParts);
            return uploadedParts;
        } catch (OSSException e) {
            // 如果uploadId不存在，返回空集合
            if ("NoSuchUpload".equals(e.getErrorCode())) {
                log.warn("{} 上传任务不存在: objectKey={}, uploadId={}",
                        getLogPrefix(), objectKey, uploadId);
                return Collections.emptySet();
            }

            log.error("{} 列举分片失败: objectKey={}, uploadId={}, errorCode={}, errorMessage={}",
                    getLogPrefix(), objectKey, uploadId, e.getErrorCode(), e.getErrorMessage(), e);
            throw new StorageOperationException(
                    String.format("阿里云OSS列举分片失败 [%s]: %s", e.getErrorCode(), e.getErrorMessage()), e);
        } catch (Exception e) {
            log.error("{} 列举分片失败: objectKey={}, uploadId={}",
                    getLogPrefix(), objectKey, uploadId, e);
            throw new StorageOperationException("阿里云OSS列举分片失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void completeMultipartUpload(String objectKey, String uploadId, List<Map<String, Object>> partETags) {
        ensureNotPrototype();

        try {
            // 转换partETags为PartETag列表
            List<PartETag> parts = new ArrayList<>();
            for (Map<String, Object> partInfo : partETags) {
                int partNumber = (int) partInfo.get("partNumber");
                String eTag = (String) partInfo.get("eTag");
                parts.add(new PartETag(partNumber + 1, eTag));
            }

            // 创建CompleteMultipartUploadRequest
            CompleteMultipartUploadRequest completeRequest =
                    new CompleteMultipartUploadRequest(bucketName, objectKey, uploadId, parts);

            // 完成分片上传
            CompleteMultipartUploadResult result = ossClient.completeMultipartUpload(completeRequest);

            log.info("{} 分片合并成功: objectKey={}, uploadId={}", getLogPrefix(), objectKey, uploadId);
        } catch (OSSException e) {
            log.error("{} 分片合并失败: objectKey={}, uploadId={}, errorCode={}, errorMessage={}",
                    getLogPrefix(), objectKey, uploadId, e.getErrorCode(), e.getErrorMessage(), e);
            throw new StorageOperationException(
                    String.format("阿里云OSS分片合并失败 [%s]: %s", e.getErrorCode(), e.getErrorMessage()), e);
        } catch (Exception e) {
            log.error("{} 分片合并失败: objectKey={}, uploadId={}", getLogPrefix(), objectKey, uploadId, e);
            throw new StorageOperationException("阿里云OSS分片合并失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void abortMultipartUpload(String objectKey, String uploadId) {
        ensureNotPrototype();

        try {
            // 创建AbortMultipartUploadRequest
            AbortMultipartUploadRequest abortRequest =
                    new AbortMultipartUploadRequest(bucketName, objectKey, uploadId);

            // 取消分片上传
            ossClient.abortMultipartUpload(abortRequest);

            log.info("{} 分片上传已取消: objectKey={}, uploadId={}", getLogPrefix(), objectKey, uploadId);

        } catch (OSSException e) {
            log.error("{} 取消分片上传失败: objectKey={}, uploadId={}, errorCode={}, errorMessage={}",
                    getLogPrefix(), objectKey, uploadId, e.getErrorCode(), e.getErrorMessage(), e);
            throw new StorageOperationException(
                    String.format("阿里云OSS取消分片上传失败 [%s]: %s", e.getErrorCode(), e.getErrorMessage()), e);
        } catch (Exception e) {
            log.error("{} 取消分片上传失败: objectKey={}, uploadId={}", getLogPrefix(), objectKey, uploadId, e);
            throw new StorageOperationException("阿里云OSS取消分片上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (ossClient != null) {
            ossClient.shutdown();
        }
    }
}