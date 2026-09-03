package com.guanghe.fs.storage.plugin.core.s3;

import com.guanghe.fs.framework.common.exception.StorageConfigException;
import com.guanghe.fs.framework.common.exception.StorageOperationException;
import com.guanghe.fs.storage.plugin.core.AbstractStorageOperationService;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * S3兼容存储抽象服务
 * 封装AWS S3 SDK的通用逻辑
 *
 * @param <T> 具体的S3兼容配置类型
 * @Author: guangheUlti
 * @Date: 2026/01/12 22:06
 */
@Slf4j
public abstract class AbstractS3CompatibleStorageService<T extends S3CompatibleConfig>
        extends AbstractStorageOperationService {

    protected S3Client s3Client;
    protected S3Presigner s3Presigner;
    protected String bucketName;
    protected T s3Config;

    /**
     * 原型构造函数
     */
    protected AbstractS3CompatibleStorageService() {
        super();
    }

    /**
     * 配置化构造函数
     */
    protected AbstractS3CompatibleStorageService(StorageConfig config) {
        super(config);
    }

    /**
     * 获取S3配置类的Class对象（子类实现）
     */
    protected abstract Class<T> getS3ConfigClass();

    /**
     * 自定义配置校验（子类可选实现）
     */
    protected void customValidateConfig(T s3Config) {
        // 默认空实现
    }

    /**
     * 自定义S3客户端配置（子类可选实现）
     */
    protected void customizeS3Configuration(S3Configuration.Builder s3ConfigBuilder) {
        // 默认空实现
    }

    @Override
    protected void validateConfig(StorageConfig config) {
        try {
            s3Config = config.toObject(getS3ConfigClass());

            if (s3Config == null) {
                throw new StorageConfigException("S3配置转换失败，配置对象为空");
            }

            // 通用校验
            if (s3Config.getEndpoint() == null || s3Config.getEndpoint().trim().isEmpty()) {
                throw new StorageConfigException("endpoint 不能为空");
            }
            if (s3Config.getAccessKey() == null || s3Config.getAccessKey().trim().isEmpty()) {
                throw new StorageConfigException("accessKey 不能为空");
            }
            if (s3Config.getSecretKey() == null || s3Config.getSecretKey().trim().isEmpty()) {
                throw new StorageConfigException("secretKey 不能为空");
            }
            if (s3Config.getBucket() == null || s3Config.getBucket().trim().isEmpty()) {
                throw new StorageConfigException("bucket 不能为空");
            }

            // 子类自定义校验
            customValidateConfig(s3Config);

        } catch (StorageConfigException e) {
            throw e;
        } catch (Exception e) {
            log.error("S3配置验证失败: {}", e.getMessage(), e);
            throw new StorageConfigException("存储平台配置错误：" + e.getMessage());
        }
    }

    @Override
    protected void initialize(StorageConfig config) {
        try {
            s3Config = config.toObject(getS3ConfigClass());
            // 构建凭证
            AwsBasicCredentials credentials = AwsBasicCredentials.create(
                    s3Config.getAccessKey(),
                    s3Config.getSecretKey()
            );
            // 构建S3配置
            S3Configuration.Builder s3ConfigBuilder = S3Configuration.builder()
                    .pathStyleAccessEnabled(s3Config.getPathStyleAccess());
            // 子类自定义配置
            customizeS3Configuration(s3ConfigBuilder);
//            // 构建HTTP客户端
//            SdkHttpClient.Builder httpClientBuilder = SdkHttpClient.builder()
//                    .connectionTimeout(Duration.ofSeconds(s3Config.getConnectionTimeout()))
//                    .socketTimeout(Duration.ofSeconds(s3Config.getSocketTimeout()));
            // 构建S3客户端
            this.s3Client = S3Client.builder()
                    .region(s3Config.getRegion())
                    .endpointOverride(URI.create(s3Config.getEndpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .serviceConfiguration(s3ConfigBuilder.build())
//                    .httpClientBuilder(httpClientBuilder)
                    .build();
            // 构建预签名URL生成器
            this.s3Presigner = S3Presigner.builder()
                    .region(s3Config.getRegion())
                    .endpointOverride(URI.create(s3Config.getEndpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .serviceConfiguration(s3ConfigBuilder.build())
                    .build();
            this.bucketName = s3Config.getBucket();
            log.info("{} S3客户端初始化成功: endpoint={}, bucket={}",
                    getLogPrefix(), s3Config.getEndpoint(), this.bucketName);
        } catch (StorageConfigException e) {
            throw e;
        } catch (Exception e) {
            log.error("S3客户端初始化失败: {}", e.getMessage(), e);
            throw new StorageConfigException("存储平台配置错误：客户端初始化失败");
        }
    }

    @Override
    public void uploadFile(InputStream inputStream, String objectKey) {
        ensureNotPrototype();
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();
            s3Client.putObject(request, RequestBody.fromInputStream(inputStream, inputStream.available()));
            log.debug("{} 文件上传成功: objectKey={}", getLogPrefix(), objectKey);
        } catch (Exception e) {
            log.error("{} 文件上传失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("S3文件上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream downloadFile(String objectKey) {
        ensureNotPrototype();
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();
            InputStream stream = s3Client.getObject(request, ResponseTransformer.toInputStream());
            log.debug("{} 文件下载成功: objectKey={}", getLogPrefix(), objectKey);
            return stream;
        } catch (NoSuchKeyException e) {
            throw new StorageOperationException("文件不存在: " + objectKey, e);
        } catch (Exception e) {
            log.error("{} 文件下载失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("S3文件下载失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream downloadFileRange(String objectKey, long startByte, long endByte) {
        ensureNotPrototype();
        try {
            if (startByte < 0 || endByte < startByte) {
                throw new StorageOperationException("无效的字节范围: startByte=" + startByte + ", endByte=" + endByte);
            }

            // 构建 Range 请求头：bytes=startByte-endByte
            String range = "bytes=" + startByte + "-" + endByte;
            
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .range(range)
                    .build();
            
            InputStream stream = s3Client.getObject(request, ResponseTransformer.toInputStream());
            
            log.debug("{} Range读取文件成功: objectKey={}, range={}", 
                    getLogPrefix(), objectKey, range);
            
            return stream;
        } catch (NoSuchKeyException e) {
            throw new StorageOperationException("文件不存在: " + objectKey, e);
        } catch (S3Exception e) {
            log.error("{} S3 Range读取失败: objectKey={}, startByte={}, endByte={}, errorCode={}", 
                    getLogPrefix(), objectKey, startByte, endByte, e.awsErrorDetails().errorCode(), e);
            throw new StorageOperationException("S3 Range读取文件失败: " + e.awsErrorDetails().errorMessage(), e);
        } catch (Exception e) {
            log.error("{} Range读取文件失败: objectKey={}, startByte={}, endByte={}", 
                    getLogPrefix(), objectKey, startByte, endByte, e);
            throw new StorageOperationException("S3 Range读取文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteFile(String objectKey) {
        ensureNotPrototype();
        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();
            s3Client.deleteObject(request);
            log.debug("{} 文件删除成功: objectKey={}", getLogPrefix(), objectKey);
        } catch (Exception e) {
            log.error("{} 文件删除失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("S3文件删除失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void rename(String objectKey, String newFileName) {
        ensureNotPrototype();
        try {
            // S3不支持重命名，需要拷贝后删除
            String newKey = objectKey.substring(0, objectKey.lastIndexOf('/') + 1) + newFileName;
            CopyObjectRequest copyRequest = CopyObjectRequest.builder()
                    .sourceBucket(bucketName)
                    .sourceKey(objectKey)
                    .destinationBucket(bucketName)
                    .destinationKey(newKey)
                    .build();
            s3Client.copyObject(copyRequest);
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();
            s3Client.deleteObject(deleteRequest);
            log.debug("{} 文件重命名成功: {} -> {}", getLogPrefix(), objectKey, newKey);
        } catch (Exception e) {
            log.error("{} 文件重命名失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("S3文件重命名失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String getFileUrl(String objectKey, Integer expireSeconds) {
        ensureNotPrototype();
        try {
            // 如果配置了自定义域名，直接拼接
            if (s3Config.getCustomDomain() != null && !s3Config.getCustomDomain().isEmpty()) {
                return s3Config.getCustomDomain() + "/" + objectKey;
            }
            // 生成预签名URL
            Duration expiration = expireSeconds != null
                    ? Duration.ofSeconds(expireSeconds)
                    : Duration.ofHours(1); // 默认1小时
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(expiration)
                    .getObjectRequest(getObjectRequest)
                    .build();
            PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
            return presignedRequest.url().toString();
        } catch (Exception e) {
            log.error("{} 生成文件URL失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("S3生成文件URL失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream getFileStream(String objectKey) {
        return downloadFile(objectKey);
    }

    @Override
    public boolean isFileExist(String objectKey) {
        ensureNotPrototype();
        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();
            s3Client.headObject(request);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            log.error("{} 检查文件存在失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("检查文件存在失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String initiateMultipartUpload(String objectKey, String mimeType) {
        ensureNotPrototype();
        try {
            CreateMultipartUploadRequest.Builder requestBuilder = CreateMultipartUploadRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey);
            if (mimeType != null && !mimeType.isEmpty()) {
                requestBuilder.contentType(mimeType);
            }
            CreateMultipartUploadResponse response = s3Client.createMultipartUpload(requestBuilder.build());
            return response.uploadId();
        } catch (Exception e) {
            log.error("{} 初始化分片上传失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("S3初始化分片上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String uploadPart(String objectKey, String uploadId, int partNumber,
                             long partSize, InputStream partInputStream) {
        ensureNotPrototype();
        try {
            UploadPartRequest request = UploadPartRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .uploadId(uploadId)
                    .partNumber(partNumber + 1) // AWS SDK partNumber从1开始
                    .build();
            UploadPartResponse response = s3Client.uploadPart(request,
                    RequestBody.fromInputStream(partInputStream, partSize));
            String eTag = response.eTag();
            log.debug("{} 分片上传成功: objectKey={}, partNumber={}, eTag={}",
                    getLogPrefix(), objectKey, partNumber, eTag);
            return eTag;
        } catch (Exception e) {
            log.error("{} 分片上传失败: objectKey={}, partNumber={}",
                    getLogPrefix(), objectKey, partNumber, e);
            throw new StorageOperationException("S3分片上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Set<Integer> listParts(String objectKey, String uploadId) {
        ensureNotPrototype();
        try {
            ListPartsRequest request = ListPartsRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .uploadId(uploadId)
                    .build();
            ListPartsResponse response = s3Client.listParts(request);
            return response.parts().stream()
                    .map(Part::partNumber)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("{} 列举分片失败，返回空集合: {}", getLogPrefix(), e.getMessage());
            return Collections.emptySet();
        }
    }

    @Override
    public void completeMultipartUpload(String objectKey, String uploadId,
                                        List<Map<String, Object>> partETags) {
        ensureNotPrototype();
        try {
            List<CompletedPart> completedParts = partETags.stream()
                    .map(map -> CompletedPart.builder()
                            .partNumber((int) map.get("partNumber") + 1)
                            .eTag((String) map.get("eTag"))
                            .build())
                    .collect(Collectors.toList());
            CompletedMultipartUpload completedUpload = CompletedMultipartUpload.builder()
                    .parts(completedParts)
                    .build();
            CompleteMultipartUploadRequest request = CompleteMultipartUploadRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .uploadId(uploadId)
                    .multipartUpload(completedUpload)
                    .build();
            s3Client.completeMultipartUpload(request);
            log.info("{} 分片合并成功: objectKey={}, uploadId={}",
                    getLogPrefix(), objectKey, uploadId);
        } catch (Exception e) {
            log.error("{} 分片合并失败: objectKey={}, uploadId={}",
                    getLogPrefix(), objectKey, uploadId, e);
            throw new StorageOperationException("S3分片合并失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void abortMultipartUpload(String objectKey, String uploadId) {
        ensureNotPrototype();
        try {
            AbortMultipartUploadRequest request = AbortMultipartUploadRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .uploadId(uploadId)
                    .build();
            s3Client.abortMultipartUpload(request);
            log.info("{} 分片上传已取消: objectKey={}, uploadId={}",
                    getLogPrefix(), objectKey, uploadId);
        } catch (Exception e) {
            log.error("{} 取消分片上传失败: objectKey={}, uploadId={}",
                    getLogPrefix(), objectKey, uploadId, e);
            throw new StorageOperationException("S3取消分片上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (s3Client != null) {
            s3Client.close();
        }
        if (s3Presigner != null) {
            s3Presigner.close();
        }
    }
}
