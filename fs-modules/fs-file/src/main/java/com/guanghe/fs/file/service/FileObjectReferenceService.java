package com.guanghe.fs.file.service;

import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.framework.redis.repository.RedisRepository;
import com.guanghe.fs.storage.facade.StorageServiceFacade;
import com.guanghe.fs.storage.plugin.core.IStorageOperationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

import static com.guanghe.fs.file.domain.table.FileInfoTableDef.FILE_INFO;

/**
 * 物理存储对象引用协调服务。
 * file_info 是引用关系的唯一事实来源，不维护容易失真的独立引用计数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileObjectReferenceService {

    private static final String LOCK_PREFIX = "file:object-reference:lock:";
    private static final long LOCK_EXPIRE_SECONDS = 60;
    private static final long LOCK_WAIT_MILLIS = 10_000;
    private static final long LOCK_RETRY_MILLIS = 50;

    private final FileInfoService fileInfoService;
    private final StorageServiceFacade storageServiceFacade;
    private final RedisRepository redisRepository;

    /**
     * 在同一物理存储配置中查找内容相同且对象仍真实存在的文件。
     * 回收站记录仍可恢复，因此同样属于有效引用。
     * 落盘加密存储直接短路：物理对象是按写入口令加密的密文，复用旧对象在密钥变更后
     * 会变成「新记录指向无法解密的对象」，且 contentMd5 是明文哈希与密文无关，去重前提不成立。
     */
    public FileInfo findReusableFile(String contentMd5, Long size, String storageSettingId) {
        if (StrUtil.isBlank(contentMd5)) {
            return null;
        }
        if (isStorageEncrypted(storageSettingId)) {
            log.debug("落盘加密存储，跳过去重复用: storageSettingId={}, md5={}", storageSettingId, contentMd5);
            return null;
        }

        QueryWrapper wrapper = new QueryWrapper()
                .where(FILE_INFO.CONTENT_MD5.eq(contentMd5))
                .and(FILE_INFO.SIZE.eq(size))
                .and(FILE_INFO.IS_DIR.eq(false))
                .and(FILE_INFO.OBJECT_KEY.isNotNull())
                .orderBy(FILE_INFO.IS_DELETED.asc(), FILE_INFO.UPLOAD_TIME.asc());
        applyStorageSettingFilter(wrapper, storageSettingId);

        for (FileInfo candidate : fileInfoService.list(wrapper)) {
            try {
                IStorageOperationService storageService =
                        storageServiceFacade.getStorageService(candidate.getStoragePlatformSettingId());
                if (storageService.isFileExist(candidate.getObjectKey())) {
                    return candidate;
                }
                log.warn("去重候选对象不存在，保留文件记录并跳过: fileId={}, objectKey={}",
                        candidate.getId(), candidate.getObjectKey());
            } catch (Exception e) {
                log.warn("检查去重候选对象失败，跳过本次候选: fileId={}, objectKey={}",
                        candidate.getId(), candidate.getObjectKey(), e);
            }
        }
        return null;
    }

    /**
     * 数据库中是否仍有任意用户记录引用该物理对象（包括回收站记录）。
     */
    public boolean hasReferences(FileInfo file) {
        if (file == null || StrUtil.isBlank(file.getObjectKey())) {
            return false;
        }
        QueryWrapper wrapper = new QueryWrapper()
                .where(FILE_INFO.OBJECT_KEY.eq(file.getObjectKey()));
        applyStorageSettingFilter(wrapper, file.getStoragePlatformSettingId());
        return fileInfoService.count(wrapper) > 0;
    }

    /**
     * 仅在确认没有任何引用时删除物理对象。
     */
    public void deletePhysicalFileIfUnreferenced(FileInfo file) {
        if (file == null || StrUtil.isBlank(file.getObjectKey()) || hasReferences(file)) {
            return;
        }
        IStorageOperationService storageService =
                storageServiceFacade.getStorageService(file.getStoragePlatformSettingId());
        storageService.deleteFile(file.getObjectKey());
        log.info("最后一个引用已删除，清理物理对象: objectKey={}", file.getObjectKey());
    }

    /**
     * 永久删除数据库记录提交后调用。逐个对象加锁并重新计数，避免与并发秒传互相干扰。
     */
    public void deletePhysicalFileIfUnreferencedWithLock(FileInfo file) {
        try (ReferenceLock contentLock = acquireContentLock(
                file.getStoragePlatformSettingId(), file.getContentMd5(), file.getSize());
             ReferenceLock objectLock = acquireObjectLock(
                     file.getStoragePlatformSettingId(), file.getObjectKey())) {
            deletePhysicalFileIfUnreferenced(file);
        }
    }

    public ReferenceLock acquireContentLock(String storageSettingId, String contentMd5, Long size) {
        if (StrUtil.isBlank(contentMd5)) {
            return ReferenceLock.noop();
        }
        return acquireLock("content:" + storageKey(storageSettingId) + ":" + contentMd5 + ":" + size);
    }

    public ReferenceLock acquireObjectLock(String storageSettingId, String objectKey) {
        if (StrUtil.isBlank(objectKey)) {
            return ReferenceLock.noop();
        }
        return acquireLock("object:" + storageKey(storageSettingId) + ":" + objectKey);
    }

    private ReferenceLock acquireLock(String lockName) {
        String key = LOCK_PREFIX + lockName;
        String token = UUID.randomUUID().toString();
        long deadline = System.currentTimeMillis() + LOCK_WAIT_MILLIS;

        while (System.currentTimeMillis() < deadline) {
            Boolean locked = redisRepository.setIfAbsent(key, token, LOCK_EXPIRE_SECONDS);
            if (Boolean.TRUE.equals(locked)) {
                return new ReferenceLock(redisRepository, key, token);
            }
            try {
                Thread.sleep(LOCK_RETRY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(I18nUtils.getMessage("file.object.busy"));
            }
        }
        throw new BusinessException(I18nUtils.getMessage("file.object.busy"));
    }

    private void applyStorageSettingFilter(QueryWrapper wrapper, String storageSettingId) {
        if (StrUtil.isBlank(storageSettingId)) {
            wrapper.and(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.isNull());
        } else {
            wrapper.and(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.eq(storageSettingId));
        }
    }

    /**
     * 目标存储是否开启落盘加密（存储不可用时按未加密处理，走原有去重逻辑）
     */
    private boolean isStorageEncrypted(String storageSettingId) {
        try {
            return storageServiceFacade.getStorageService(storageSettingId).isEncryptionEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private String storageKey(String storageSettingId) {
        return StrUtil.isBlank(storageSettingId) ? "local" : storageSettingId;
    }

    public static final class ReferenceLock implements AutoCloseable {
        private final RedisRepository redisRepository;
        private final String key;
        private final String token;
        private boolean closed;

        private ReferenceLock(RedisRepository redisRepository, String key, String token) {
            this.redisRepository = redisRepository;
            this.key = key;
            this.token = token;
        }

        private ReferenceLock() {
            this.redisRepository = null;
            this.key = null;
            this.token = null;
            this.closed = true;
        }

        private static ReferenceLock noop() {
            return new ReferenceLock();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (!redisRepository.compareAndDelete(key, token)) {
                log.warn("物理对象引用锁释放失败或已过期: key={}", key);
            }
        }
    }
}
