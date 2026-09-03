package com.guanghe.fs.storage.plugin.obs;

import com.obs.services.ObsClient;
import com.obs.services.exception.ObsException;
import com.obs.services.model.*;
import com.guanghe.fs.framework.common.exception.StorageConfigException;
import com.guanghe.fs.framework.common.exception.StorageOperationException;
import com.guanghe.fs.storage.plugin.core.AbstractStorageOperationService;
import com.guanghe.fs.storage.plugin.core.annotation.StoragePlugin;
import com.guanghe.fs.storage.plugin.core.config.StorageConfig;
import com.guanghe.fs.storage.plugin.obs.config.ObsConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * 华为云 OBS 存储插件实现
 *
 * @Author: guangheUlti
 * @Date: 2026/03/10 20:06
 */
@Slf4j
@StoragePlugin(
        identifier = "Obs",
        name = "华为云OBS",
        description = "对象存储服务（Object Storage Service，OBS）提供海量、安全、高可靠、低成本的数据存储能力，可供用户存储任意类型和大小的数据。适合企业备份/归档、视频点播、视频监控等多种数据存储场景。",
        link = "https://support.huaweicloud.com/obs/index.html",
        schemaResource = "classpath:schema/huaweicloud-obs-schema.json"
)
public class ObsStorageServiceImpl extends AbstractStorageOperationService {

    private ObsClient obsClient;

    private String bucketName;

    @SuppressWarnings("unused")
    public ObsStorageServiceImpl() {
        super();
    }

    @SuppressWarnings("unused")
    public ObsStorageServiceImpl(StorageConfig config) {
        super(config);
    }

    @Override
    protected void validateConfig(StorageConfig config) {
        try {
            ObsConfig obsConfig = config.toObject(ObsConfig.class);

            if (obsConfig == null) {
                throw new StorageConfigException("存储平台配置错误：华为云OBS配置转换失败，配置对象为空");
            }

            if (obsConfig.getEndpoint() == null || obsConfig.getEndpoint().trim().isEmpty()) {
                throw new StorageConfigException("存储平台配置错误：华为云OBS endpoint 不能为空");
            }

            if (obsConfig.getAccessKey() == null || obsConfig.getAccessKey().trim().isEmpty()) {
                throw new StorageConfigException("存储平台配置错误：华为云OBS accessKey 不能为空");
            }

            if (obsConfig.getSecretKey() == null || obsConfig.getSecretKey().trim().isEmpty()) {
                throw new StorageConfigException("存储平台配置错误：华为云OBS secretKey 不能为空");
            }

            if (obsConfig.getBucket() == null || obsConfig.getBucket().trim().isEmpty()) {
                throw new StorageConfigException("存储平台配置错误：华为云OBS bucket 不能为空");
            }
        } catch (Exception e) {
            log.error("华为云OBS配置验证失败: {}", e.getMessage(), e);
            throw new StorageConfigException("当前存储平台配置错误：客户端校验失败");
        }
    }

    @Override
    protected void initialize(StorageConfig config) {
        try {
            ObsConfig obsConfig = config.toObject(ObsConfig.class);
            String ak = obsConfig.getAccessKey();
            String sk = obsConfig.getSecretKey();
            String endPoint = obsConfig.getEndpoint();
            this.obsClient = new ObsClient(ak, sk, endPoint);
            this.bucketName = obsConfig.getBucket();
            log.info("{} 华为云OBS客户端初始化成功: endpoint={}, bucket={}",
                    getLogPrefix(), obsConfig.getEndpoint(), this.bucketName);
        } catch (StorageConfigException e) {
            // 重新抛出配置异常
            throw e;
        } catch (Exception e) {
            log.error("华为云OBS初始化失败: {}", e.getMessage(), e);
            throw new StorageConfigException("当前存储平台配置错误：客户端初始化失败");
        }
    }

    @Override
    public void uploadFile(InputStream inputStream, String objectKey) {
        ensureNotPrototype();
        try {
            PutObjectRequest request = new PutObjectRequest();
            request.setBucketName(bucketName);
            request.setObjectKey(objectKey);
            request.setInput(inputStream);
            obsClient.putObject(request);
            log.debug("{} 文件上传成功: objectKey={}", getLogPrefix(), objectKey);
        } catch (ObsException e) {
            log.error("{} 文件上传失败: objectKey={}, errorCode={}, errorMessage={}",
                    getLogPrefix(), objectKey, e.getErrorCode(), e.getErrorMessage(), e);
            throw new StorageOperationException(
                    String.format("华为云OBS文件上传失败 [%s]: %s", e.getErrorCode(), e.getErrorMessage()), e);
        } catch (Exception e) {
            log.error("{} 文件上传失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("华为云OBS文件上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream downloadFile(String objectKey) {
        return null;
    }

    @Override
    public InputStream downloadFileRange(String objectKey, long startByte, long endByte) {
        ensureNotPrototype();
        try {
            if (startByte < 0 || endByte < startByte) {
                throw new StorageOperationException("无效的字节范围: startByte=" + startByte + ", endByte=" + endByte);
            }

            GetObjectRequest request = new GetObjectRequest(bucketName, objectKey);
            request.setRangeStart(startByte);
            request.setRangeEnd(endByte);

            ObsObject obsObject = obsClient.getObject(request);
            if (obsObject == null) {
                throw new StorageOperationException("文件不存在: " + objectKey);
            }

            log.debug("{} Range读取文件成功: objectKey={}, startByte={}, endByte={}",
                    getLogPrefix(), objectKey, startByte, endByte);

            return obsObject.getObjectContent();
        } catch (ObsException e) {
            if ("NoSuchKey".equals(e.getErrorCode())) {
                log.warn("{} 文件不存在: objectKey={}", getLogPrefix(), objectKey);
                throw new StorageOperationException("文件不存在: " + objectKey, e);
            }
            log.error("{} Range读取文件失败: objectKey={}, startByte={}, endByte={}, errorCode={}, errorMessage={}",
                    getLogPrefix(), objectKey, startByte, endByte, e.getErrorCode(), e.getErrorMessage(), e);
            throw new StorageOperationException(
                    String.format("华为云OBS Range读取文件失败 [%s]: %s", e.getErrorCode(), e.getErrorMessage()), e);
        } catch (Exception e) {
            log.error("{} Range读取文件失败: objectKey={}, startByte={}, endByte={}",
                    getLogPrefix(), objectKey, startByte, endByte, e);
            throw new StorageOperationException("华为云OBS Range读取文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteFile(String objectKey) {
        ensureNotPrototype();
        try {
            obsClient.deleteObject(bucketName, objectKey);
            log.debug("{} 文件删除成功: objectKey={}", getLogPrefix(), objectKey);
        } catch (ObsException e) {
            log.error("{} 文件删除失败: objectKey={}, errorCode={}, errorMessage={}",
                    getLogPrefix(), objectKey, e.getErrorCode(), e.getErrorMessage(), e);
            throw new StorageOperationException(
                    String.format("华为云OBS文件删除失败 [%s]: %s", e.getErrorCode(), e.getErrorMessage()), e);
        } catch (Exception e) {
            log.error("{} 文件删除失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("华为云OBS文件删除失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void rename(String objectKey, String destObjectKey) {
        ensureNotPrototype();
        try {
            //拷贝新文件至同目录下
            obsClient.copyObject(bucketName, objectKey, bucketName, destObjectKey);
            // 删除原key
            obsClient.deleteObject(bucketName, objectKey);
            log.debug("{} 文件重命名成功: sourceKey={}, newKey={}", getLogPrefix(), objectKey, destObjectKey);
        } catch (ObsException e) {
            log.error("{} 文件重命名成功失败: sourceKey={}, newKey={}, errorCode={}, errorMessage={}",
                    getLogPrefix(), objectKey, destObjectKey, e.getErrorCode(), e.getErrorMessage(), e);
            throw new StorageOperationException(
                    String.format("华为云OBS重命名失败 [%s]: %s", e.getErrorCode(), e.getErrorMessage()), e);
        } catch (Exception e) {
            log.error("{} 重命名失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("华为云OBS重命名失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String getFileUrl(String objectKey, Integer expireSeconds) {
        ensureNotPrototype();
        try {
            expireSeconds = expireSeconds + 1000;
            TemporarySignatureRequest request = new TemporarySignatureRequest(HttpMethodEnum.GET, expireSeconds);
            request.setBucketName(bucketName);
            request.setObjectKey(objectKey);
            TemporarySignatureResponse response = obsClient.createTemporarySignature(request);
            return response.getSignedUrl();
        } catch (ObsException e) {
            log.error("{} 生成文件URL失败: objectKey={}, errorCode={}, errorMessage={}",
                    getLogPrefix(), objectKey, e.getErrorCode(), e.getErrorMessage(), e);
            throw new StorageOperationException(
                    String.format("华为云OBS生成文件URL失败 [%s]: %s", e.getErrorCode(), e.getErrorMessage()), e);
        } catch (Exception e) {
            log.error("{} 生成文件URL失败: objectKey={}", getLogPrefix(), objectKey, e);
            throw new StorageOperationException("华为云OBS生成文件URL失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream getFileStream(String objectKey) {
        GetObjectRequest getObjectRequest = new GetObjectRequest(bucketName, objectKey);
        ObsObject ossObject = obsClient.getObject(getObjectRequest);
        return ossObject.getObjectContent();
    }

    @Override
    public boolean isFileExist(String objectKey) {
        ensureNotPrototype();
        return obsClient.doesObjectExist(bucketName, objectKey);
    }

    @Override
    public String initiateMultipartUpload(String objectKey, String mimeType) {
        ensureNotPrototype();
        try {
            InitiateMultipartUploadRequest request = new InitiateMultipartUploadRequest(bucketName, objectKey);
            ObjectMetadata metadata = new ObjectMetadata();
            if (metadata.getContentType() == null) {
                metadata.setContentType(mimeType);
            }
            request.setMetadata(metadata);
            InitiateMultipartUploadResult upResult = obsClient.initiateMultipartUpload(request);
            return upResult.getUploadId();
        } catch (Exception e) {
            throw new StorageOperationException("华为云OBS生成文件URL失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String uploadPart(String objectKey, String uploadId, int partNumber, long partSize, InputStream partInputStream) {
        ensureNotPrototype();
        try {
            // 创建UploadPartRequest
            UploadPartRequest uploadPartRequest = new UploadPartRequest(bucketName, objectKey);
            uploadPartRequest.setUploadId(uploadId);
            uploadPartRequest.setPartNumber(partNumber + 1);
            uploadPartRequest.setPartSize(partSize);
            uploadPartRequest.setInput(partInputStream);

            // 上传分片
            UploadPartResult uploadPartResult = obsClient.uploadPart(uploadPartRequest);
            String etag = uploadPartResult.getEtag();

            log.debug("{} 分片上传成功: objectKey={}, partNumber={}, etag={}",
                    getLogPrefix(), objectKey, partNumber, etag);
            return etag;
        } catch (ObsException e) {
            log.error("{} 分片上传失败: objectKey={}, partNumber={}, errorCode={}, errorMessage={}",
                    getLogPrefix(), objectKey, partNumber, e.getErrorCode(), e.getErrorMessage(), e);
            throw new StorageOperationException(
                    String.format("华为云OBS分片上传失败 [%s]: %s", e.getErrorCode(), e.getErrorMessage()), e);
        } catch (Exception e) {
            log.error("{} 分片上传失败: objectKey={}, partNumber={}", getLogPrefix(), objectKey, partNumber, e);
            throw new StorageOperationException("华为云OBS分片上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Set<Integer> listParts(String objectKey, String uploadId) {
        ensureNotPrototype();

        try {
            Set<Integer> uploadedParts = new HashSet<>();

            ListPartsRequest request = new ListPartsRequest(bucketName, objectKey);
            request.setUploadId(uploadId);
            ListPartsResult result;
            do {
                // 列举分片
                result = obsClient.listParts(request);
                // 收集分片号
                for (Multipart part : result.getMultipartList()) {
                    uploadedParts.add(part.getPartNumber());
                }
                // 设置下一页标记
                request.setPartNumberMarker(Integer.parseInt(result.getNextPartNumberMarker()));

            } while (result.isTruncated());
            log.debug("{} 已上传分片列表: objectKey={}, uploadId={}, parts={}",
                    getLogPrefix(), objectKey, uploadId, uploadedParts);
            return uploadedParts;
        } catch (ObsException e) {
            // 如果uploadId不存在，返回空集合
            if ("NoSuchUpload".equals(e.getErrorCode())) {
                log.warn("{} 上传任务不存在: objectKey={}, uploadId={}",
                        getLogPrefix(), objectKey, uploadId);
                return Collections.emptySet();
            }

            log.error("{} 列举分片失败: objectKey={}, uploadId={}, errorCode={}, errorMessage={}",
                    getLogPrefix(), objectKey, uploadId, e.getErrorCode(), e.getErrorMessage(), e);
            throw new StorageOperationException(
                    String.format("华为云OBS列举分片失败 [%s]: %s", e.getErrorCode(), e.getErrorMessage()), e);
        } catch (Exception e) {
            log.error("{} 列举分片失败: objectKey={}, uploadId={}",
                    getLogPrefix(), objectKey, uploadId, e);
            throw new StorageOperationException("华为云OBS列举分片失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void completeMultipartUpload(String objectKey, String uploadId, List<Map<String, Object>> partETags) {
        ensureNotPrototype();

        try {
            List<PartEtag> parts = new ArrayList<>();
            for (Map<String, Object> partInfo : partETags) {
                int partNumber = (int) partInfo.get("partNumber");
                String eTag = (String) partInfo.get("eTag");
                parts.add(new PartEtag(eTag, partNumber + 1));
            }

            CompleteMultipartUploadRequest completeRequest =
                    new CompleteMultipartUploadRequest(bucketName, objectKey, uploadId, parts);

            obsClient.completeMultipartUpload(completeRequest);
            log.info("{} 分片合并成功: objectKey={}, uploadId={}", getLogPrefix(), objectKey, uploadId);
        } catch (ObsException e) {
            log.error("{} 分片合并失败: objectKey={}, uploadId={}, errorCode={}, errorMessage={}",
                    getLogPrefix(), objectKey, uploadId, e.getErrorCode(), e.getErrorMessage(), e);
            throw new StorageOperationException(
                    String.format("华为云OBS分片合并失败 [%s]: %s", e.getErrorCode(), e.getErrorMessage()), e);
        } catch (Exception e) {
            log.error("{} 分片合并失败: objectKey={}, uploadId={}", getLogPrefix(), objectKey, uploadId, e);
            throw new StorageOperationException("华为云OBS分片合并失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void abortMultipartUpload(String objectKey, String uploadId) {
        ensureNotPrototype();

        try {
            AbortMultipartUploadRequest abortRequest =
                    new AbortMultipartUploadRequest(bucketName, objectKey, uploadId);
            obsClient.abortMultipartUpload(abortRequest);

            log.info("{} 分片上传已取消: objectKey={}, uploadId={}", getLogPrefix(), objectKey, uploadId);
        } catch (ObsException e) {
            log.error("{} 取消分片上传失败: objectKey={}, uploadId={}, errorCode={}, errorMessage={}",
                    getLogPrefix(), objectKey, uploadId, e.getErrorCode(), e.getErrorMessage(), e);
            throw new StorageOperationException(
                    String.format("华为云OBS取消分片上传失败 [%s]: %s", e.getErrorCode(), e.getErrorMessage()), e);
        } catch (Exception e) {
            log.error("{} 取消分片上传失败: objectKey={}, uploadId={}", getLogPrefix(), objectKey, uploadId, e);
            throw new StorageOperationException("华为云OBS取消分片上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (obsClient != null) {
            try {
                obsClient.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
