package com.guanghe.fs.file.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.update.UpdateChain;
import com.mybatisflex.core.util.UpdateEntity;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.domain.dto.CopyFileCmd;
import com.guanghe.fs.file.domain.dto.CreateDirectoryCmd;
import com.guanghe.fs.file.domain.dto.CreateTextFileCmd;
import com.guanghe.fs.file.domain.dto.MoveFileCmd;
import com.guanghe.fs.file.domain.dto.RenameFileCmd;
import com.guanghe.fs.file.domain.dto.UpdateTextContentCmd;
import com.guanghe.fs.file.domain.qry.FileQry;
import com.guanghe.fs.file.domain.table.FileInfoTableDef;
import com.guanghe.fs.file.domain.table.FileUserFavoritesTableDef;
import com.guanghe.fs.file.domain.vo.FileDetailVO;
import com.guanghe.fs.file.domain.vo.FileVO;
import com.guanghe.fs.file.mapper.FileInfoMapper;
import com.guanghe.fs.file.mount.MountManager;
import com.guanghe.fs.file.mount.MountPathResolver;
import com.guanghe.fs.file.mount.MountPointService;
import com.guanghe.fs.file.service.FileInfoService;
import com.guanghe.fs.file.service.FileObjectReferenceService;
import com.guanghe.fs.framework.common.constant.RedisKey;
import com.guanghe.fs.framework.common.domain.PageResult;
import com.guanghe.fs.framework.common.enums.FileTypeEnum;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.exception.StorageOperationException;
import com.guanghe.fs.framework.common.utils.FileUtils;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.framework.common.utils.StringUtils;
import com.guanghe.fs.framework.redis.repository.RedisRepository;
import com.guanghe.fs.storage.plugin.core.IStorageOperationService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.guanghe.fs.storage.plugin.core.context.StoragePlatformContextHolder;
import com.guanghe.fs.storage.facade.StorageServiceFacade;
import io.github.linpeilie.Converter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.guanghe.fs.file.domain.table.FileInfoTableDef.FILE_INFO;
import static com.guanghe.fs.file.domain.table.FileUserFavoritesTableDef.FILE_USER_FAVORITES;

/**
 * 文件资源服务实现类
 *
 * @Author: guangheUlti
 * @Date: 2025/5/8 9:40
 */
@Slf4j
@Service
public class FileInfoServiceImpl extends ServiceImpl<FileInfoMapper, FileInfo> implements FileInfoService {

    /** 在线文本编辑仅支持的后缀与大小上限（1 MB，读/写两侧共用） */
    private static final String TEXT_SUFFIX = "txt";
    private static final long MAX_TEXT_EDIT_SIZE = 1024 * 1024;

    @Autowired
    private Converter converter;

    @Autowired
    private StorageServiceFacade storageServiceFacade;

    // FileObjectReferenceService 反向依赖 FileInfoService，用 ObjectProvider 延迟获取避免启动期循环依赖
    @Autowired
    private ObjectProvider<FileObjectReferenceService> objectReferenceServiceProvider;

    // 挂载集成组件（MountPointService 反向依赖 FileInfoService，字段注入容忍循环依赖）
    @Autowired
    private MountPathResolver mountPathResolver;

    @Autowired
    private MountPointService mountPointService;

    @Autowired
    private MountManager mountManager;

    @Autowired
    private com.guanghe.fs.storage.service.StorageSettingService storageSettingService;

    @Autowired
    private RedisRepository redisRepository;

    /** 当前配置是否为挂载式存储（能力位判断，勿比较 identifier 字符串） */
    private boolean isMountStorage(String settingId) {
        if (StrUtil.isBlank(settingId)) {
            return false;
        }
        try {
            return storageServiceFacade.getStorageService(settingId).isMountMode();
        } catch (Exception e) {
            log.warn("判断挂载存储失败，按非挂载处理: settingId={}", settingId, e);
            return false;
        }
    }

    @Override
    public FileInfo getAuthorizedFile(String fileId) {
        if (StrUtil.isBlank(fileId)) {
            throw new BusinessException(I18nUtils.getMessage("file.not.found"));
        }
        String userId = StpUtil.getLoginIdAsString();
        FileInfo fileInfo = getOne(new QueryWrapper()
                .where(FILE_INFO.ID.eq(fileId))
                .and(FILE_INFO.USER_ID.eq(userId))
                .and(FILE_INFO.IS_DELETED.eq(false)));
        if (fileInfo == null) {
            throw new BusinessException(I18nUtils.getMessage("file.not.found"));
        }
        return fileInfo;
    }

    @Override
    public long countByStorageSettingId(String settingId) {
        if (StrUtil.isBlank(settingId)) {
            return 0;
        }
        return count(new QueryWrapper()
                .where(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.eq(settingId))
                .and(FILE_INFO.IS_DELETED.eq(false)));
    }

    @Override
    public InputStream downloadFile(String fileId) {
        FileInfo fileInfo = getAuthorizedFile(fileId);
        if (fileInfo.getIsDir()) {
            throw new StorageOperationException(I18nUtils.getMessage("file.cannot.download.dir", new Object[]{fileId}));
        }
        if (fileInfo.getIsDeleted()) {
            throw new StorageOperationException(I18nUtils.getMessage("file.deleted", new Object[]{fileId}));
        }

        // 根据文件记录中的 storagePlatformSettingId 获取对应的存储服务
        try {
            IStorageOperationService storageService = storageServiceFacade.getStorageService(fileInfo.getStoragePlatformSettingId());
            return storageService.downloadFile(fileInfo.getObjectKey());
        } catch (StorageOperationException e) {
            // 直接抛出原始异常，让上层统一处理
            log.error("从存储平台下载文件失败: fileId={}, objectKey={}", fileId, fileInfo.getObjectKey(), e);
            throw e;
        }
    }

    @Override
    public String getFileUrl(String fileId, Integer expireSeconds) {
        FileInfo fileInfo = getAuthorizedFile(fileId);
        if (fileInfo.getIsDir()) {
            throw new StorageOperationException(I18nUtils.getMessage("file.dir.no.url", new Object[]{fileId}));
        }
        if (fileInfo.getIsDeleted()) {
            throw new StorageOperationException(I18nUtils.getMessage("file.deleted", new Object[]{fileId}));
        }
        IStorageOperationService storageService = storageServiceFacade.getStorageService(fileInfo.getStoragePlatformSettingId());
        if (!storageService.isFileExist(fileInfo.getObjectKey())) {
            throw new StorageOperationException(I18nUtils.getMessage("file.not.exist", new Object[]{fileId}));
        }
        return storageService.getFileUrl(fileInfo.getObjectKey(), expireSeconds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void moveFilesToRecycleBin(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return;
        }

        String userId = StpUtil.getLoginIdAsString();
        List<FileInfo> fileInfoList = list(new QueryWrapper()
                .where(FILE_INFO.ID.in(fileIds))
                .and(FILE_INFO.USER_ID.eq(userId)));
        if (fileInfoList.isEmpty()) {
            return;
        }

        // 收集所有需要删除的文件（包括子文件）
        List<FileInfo> toDeleteList = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (FileInfo fileInfo : fileInfoList) {
            // 挂载点记录本身禁止删除（⑭）：它代表与真实目录的映射关系
            if (isMountStorage(fileInfo.getStoragePlatformSettingId())
                    && Boolean.TRUE.equals(fileInfo.getIsDir())
                    && StrUtil.isEmpty(fileInfo.getParentId())) {
                throw new BusinessException(I18nUtils.getMessage("mount.point.protected"));
            }
            if (!fileInfo.getIsDeleted()) {
                toDeleteList.add(fileInfo);

                // 如果是文件夹，递归获取所有子文件和子文件夹
                if (fileInfo.getIsDir()) {
                    List<FileInfo> children = getAllChildrenRecursively(fileInfo.getId(), false);
                    toDeleteList.addAll(children);
                }
            }
        }

        if (toDeleteList.isEmpty()) {
            return;
        }

        // 批量标记为删除
        toDeleteList.forEach(fileInfo -> {
            fileInfo.setIsDeleted(true);
            fileInfo.setDeletedTime(now);
        });

        this.updateBatch(toDeleteList);
    }


    /**
     * 递归获取文件夹下的所有子文件和子文件夹
     *
     * @param parentId       父文件夹ID
     * @param includeDeleted 是否包含已删除的文件
     * @return 所有子文件列表
     */
    private List<FileInfo> getAllChildrenRecursively(String parentId, boolean includeDeleted) {
        List<FileInfo> allChildren = new ArrayList<>();

        // 查询直接子文件
        QueryWrapper query = new QueryWrapper()
                .where(FILE_INFO.PARENT_ID.eq(parentId));

        if (!includeDeleted) {
            query.and(FILE_INFO.IS_DELETED.eq(false));
        }

        List<FileInfo> directChildren = list(query);

        for (FileInfo child : directChildren) {
            allChildren.add(child);

            // 如果是文件夹，递归查询
            if (child.getIsDir()) {
                allChildren.addAll(getAllChildrenRecursively(child.getId(), includeDeleted));
            }
        }

        return allChildren;
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileInfo createDirectory(CreateDirectoryCmd cmd) {
        String folderId = IdUtil.fastSimpleUUID();
        String userId = StpUtil.getLoginIdAsString();
        String platformConfigId = StoragePlatformContextHolder.getConfigId();
        if (StrUtil.isNotBlank(cmd.getParentId())) {
            FileInfo parent = getAuthorizedFile(cmd.getParentId());
            if (!Boolean.TRUE.equals(parent.getIsDir())) {
                throw new BusinessException(I18nUtils.getMessage("file.target.dir.invalid"));
            }
        }
        String baseName = cmd.getFolderName().trim();
        String finalName = generateUniqueName(
                userId,
                cmd.getParentId(),
                baseName,
                true,
                null,
                platformConfigId
        );
        // 挂载式：先真实 mkdir（成功后再插 DB，失败不落库）
        if (isMountStorage(platformConfigId)) {
            IStorageOperationService storageService = storageServiceFacade.getStorageService(platformConfigId);
            if (StrUtil.isNotBlank(cmd.getParentId())) {
                FileInfo parent = getAuthorizedFile(cmd.getParentId());
                String parentKey = mountPathResolver.resolveRelativeKey(parent, platformConfigId);
                String dirKey = parentKey.isEmpty() ? finalName : parentKey + "/" + finalName;
                mountManager.locks().callWithLock(platformConfigId, () ->
                        storageService.mkdirDirectory(dirKey));
            } else {
                // 根下建目录：仅挂载点自身在根层，正常流程不允许其它根层目录，保持 DB 行为
                log.debug("挂载平台根层创建目录（不触碰真实 FS，等待挂载点逻辑）: {}", finalName);
            }
        }
        FileInfo dirInfo = new FileInfo();
        dirInfo.setId(folderId);
        dirInfo.setOriginalName(finalName);
        dirInfo.setDisplayName(finalName);
        dirInfo.setIsDir(true);
        dirInfo.setParentId(cmd.getParentId());
        dirInfo.setUserId(userId);
        dirInfo.setStoragePlatformSettingId(platformConfigId);
        LocalDateTime now = LocalDateTime.now();
        dirInfo.setUploadTime(now);
        dirInfo.setUpdateTime(now);
        dirInfo.setIsDeleted(false);
        save(dirInfo);
        return dirInfo;
    }

    @Override
    public FileInfo createTextFile(CreateTextFileCmd cmd) {
        String userId = StpUtil.getLoginIdAsString();
        String storagePlatformSettingId = StoragePlatformContextHolder.getConfigId();
        if (StrUtil.isNotBlank(cmd.getParentId())) {
            FileInfo parent = getAuthorizedFile(cmd.getParentId());
            if (!Boolean.TRUE.equals(parent.getIsDir())) {
                throw new BusinessException(I18nUtils.getMessage("file.target.dir.invalid"));
            }
        }
        String suffix = StrUtil.blankToDefault(cmd.getSuffix(), TEXT_SUFFIX).trim().toLowerCase();
        if (!FileTypeEnum.isEditableTextType(suffix)) {
            throw new BusinessException(I18nUtils.getMessage("file.text.not.editable"));
        }
        byte[] bytes = StrUtil.nullToEmpty(cmd.getContent()).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_EDIT_SIZE) {
            throw new BusinessException(I18nUtils.getMessage("file.text.too.large",
                    new Object[]{FileUtils.formatFileSize(MAX_TEXT_EDIT_SIZE)}));
        }
        // 去掉用户误带的后缀再统一拼接，避免出现 .txt.txt；重名冲突交给 generateUniqueName 处理
        String baseName = StrUtil.removeSuffixIgnoreCase(cmd.getFileName().trim(), "." + suffix);
        String displayName = generateUniqueName(
                userId,
                cmd.getParentId(),
                baseName + "." + suffix,
                false,
                null,
                storagePlatformSettingId
        );
        return writeTextObject(userId, storagePlatformSettingId, cmd.getParentId(), displayName, suffix, bytes);
    }

    @Override
    public String readTextContent(String fileId) {
        FileInfo fileInfo = getAuthorizedFile(fileId);
        assertEditableTextFile(fileInfo);
        try (InputStream in = downloadFile(fileId)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (BusinessException | StorageOperationException e) {
            throw e;
        } catch (Exception e) {
            log.error("读取文本内容失败: fileId={}", fileId, e);
            throw new StorageOperationException(I18nUtils.getMessage("file.text.read.failed"), e);
        }
    }

    @Override
    public void updateTextContent(String fileId, UpdateTextContentCmd cmd) {
        FileInfo fileInfo = getAuthorizedFile(fileId);
        assertEditableTextFile(fileInfo);
        byte[] bytes = cmd.getContent().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_EDIT_SIZE) {
            throw new BusinessException(I18nUtils.getMessage("file.text.too.large",
                    new Object[]{FileUtils.formatFileSize(MAX_TEXT_EDIT_SIZE)}));
        }
        String newMd5 = DigestUtil.md5Hex(bytes);
        if (newMd5.equals(fileInfo.getContentMd5())
                && fileInfo.getSize() != null && fileInfo.getSize() == bytes.length) {
            // 内容无变化，直接返回
            return;
        }
        String storagePlatformSettingId = fileInfo.getStoragePlatformSettingId();
        String oldObjectKey = fileInfo.getObjectKey();
        String oldContentMd5 = fileInfo.getContentMd5();
        Long oldSize = fileInfo.getSize();
        FileObjectReferenceService referenceService = objectReferenceServiceProvider.getObject();
        try (FileObjectReferenceService.ReferenceLock ignored =
                     referenceService.acquireContentLock(storagePlatformSettingId, newMd5, (long) bytes.length)) {
            FileInfo reusable = referenceService.findReusableFile(
                    newMd5, (long) bytes.length, storagePlatformSettingId);
            if (reusable != null && !fileId.equals(reusable.getId())) {
                // 新内容已有物理对象，直接切换引用
                try (FileObjectReferenceService.ReferenceLock objectLock =
                             referenceService.acquireObjectLock(
                                     reusable.getStoragePlatformSettingId(), reusable.getObjectKey())) {
                    fileInfo.setObjectKey(reusable.getObjectKey());
                }
            } else if (reusable == null) {
                // 写入全新物理对象，绝不覆盖旧 objectKey（可能被其它记录秒传引用）
                String objectKey = FileUtils.generateObjectKey(
                        fileInfo.getUserId(), IdUtil.fastSimpleUUID() + "." + TEXT_SUFFIX);
                IStorageOperationService storageService =
                        storageServiceFacade.getStorageService(storagePlatformSettingId);
                storageService.uploadFile(new ByteArrayInputStream(bytes), objectKey);
                fileInfo.setObjectKey(objectKey);
            }
            fileInfo.setContentMd5(newMd5);
            fileInfo.setSize((long) bytes.length);
            fileInfo.setUpdateTime(LocalDateTime.now());
            updateById(fileInfo);
        }
        // 对象键变更后，旧物理对象若无其它引用则清理；失败只留孤立对象，不影响正确性
        if (oldObjectKey != null && !oldObjectKey.equals(fileInfo.getObjectKey())) {
            FileInfo oldRef = new FileInfo();
            oldRef.setObjectKey(oldObjectKey);
            oldRef.setContentMd5(oldContentMd5);
            oldRef.setSize(oldSize);
            oldRef.setStoragePlatformSettingId(storagePlatformSettingId);
            try {
                referenceService.deletePhysicalFileIfUnreferencedWithLock(oldRef);
            } catch (Exception e) {
                log.warn("清理文本编辑前的旧物理对象失败: fileId={}, objectKey={}", fileId, oldObjectKey, e);
            }
        }
    }

    /** 校验文件可在线编辑：仅非目录的文本/代码/Markdown 类文件，且不超过大小上限 */
    private void assertEditableTextFile(FileInfo fileInfo) {
        String suffix = StrUtil.trimToEmpty(fileInfo.getSuffix());
        if (Boolean.TRUE.equals(fileInfo.getIsDir()) || !FileTypeEnum.isEditableTextType(suffix)) {
            throw new BusinessException(I18nUtils.getMessage("file.text.not.editable"));
        }
        if (fileInfo.getSize() != null && fileInfo.getSize() > MAX_TEXT_EDIT_SIZE) {
            throw new BusinessException(I18nUtils.getMessage("file.text.too.large",
                    new Object[]{FileUtils.formatFileSize(MAX_TEXT_EDIT_SIZE)}));
        }
    }

    /** 写入文本物理对象（含内容级复用）并落库文件记录 */
    private FileInfo writeTextObject(String userId, String storagePlatformSettingId,
                                     String parentId, String displayName, String suffix, byte[] bytes) {
        String contentMd5 = DigestUtil.md5Hex(bytes);
        FileObjectReferenceService referenceService = objectReferenceServiceProvider.getObject();
        LocalDateTime now = LocalDateTime.now();
        FileInfo fileInfo = new FileInfo();
        fileInfo.setId(IdUtil.fastSimpleUUID());
        fileInfo.setOriginalName(displayName);
        fileInfo.setDisplayName(displayName);
        fileInfo.setSuffix(suffix);
        fileInfo.setMimeType("text/plain");
        fileInfo.setIsDir(false);
        fileInfo.setParentId(parentId);
        fileInfo.setUserId(userId);
        fileInfo.setStoragePlatformSettingId(storagePlatformSettingId);
        fileInfo.setUploadTime(now);
        fileInfo.setIsDeleted(false);
        try (FileObjectReferenceService.ReferenceLock ignored =
                     referenceService.acquireContentLock(storagePlatformSettingId, contentMd5, (long) bytes.length)) {
            FileInfo reusable = referenceService.findReusableFile(
                    contentMd5, (long) bytes.length, storagePlatformSettingId);
            if (reusable != null) {
                try (FileObjectReferenceService.ReferenceLock objectLock =
                             referenceService.acquireObjectLock(
                                     reusable.getStoragePlatformSettingId(), reusable.getObjectKey())) {
                    fileInfo.setObjectKey(reusable.getObjectKey());
                }
            } else {
                String objectKey = FileUtils.generateObjectKey(
                        userId, IdUtil.fastSimpleUUID() + "." + suffix);
                IStorageOperationService storageService =
                        storageServiceFacade.getStorageService(storagePlatformSettingId);
                storageService.uploadFile(new ByteArrayInputStream(bytes), objectKey);
                fileInfo.setObjectKey(objectKey);
            }
            fileInfo.setContentMd5(contentMd5);
            fileInfo.setSize((long) bytes.length);
            fileInfo.setUpdateTime(now);
            save(fileInfo);
        }
        return fileInfo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileInfo writeFileContent(String parentId, String displayName, InputStream in, Long size) {
        String userId = StpUtil.getLoginIdAsString();
        if (StrUtil.isBlank(displayName) || !MountManager.isValidNameSegment(displayName)
                || ".".equals(displayName) || "..".equals(displayName)) {
            throw new BusinessException(I18nUtils.getMessage("file.name.invalid"));
        }
        FileInfo parent = null;
        if (StrUtil.isNotBlank(parentId)) {
            parent = getAuthorizedFile(parentId);
            if (!Boolean.TRUE.equals(parent.getIsDir())) {
                throw new BusinessException(I18nUtils.getMessage("file.target.dir.invalid"));
            }
        }
        String storagePlatformSettingId = StoragePlatformContextHolder.getConfigId();

        // 同目录同名现有文件 = 覆盖语义；挂载式存储不支持流式直传（物理路径即对象键，覆盖会破坏引用计数），明确拒绝
        boolean mountMode = isMountStorage(storagePlatformSettingId);
        if (mountMode) {
            throw new BusinessException(I18nUtils.getMessage("service.write.unsupported.mount"));
        }
        FileInfo existing = getOne(new QueryWrapper()
                .where(FILE_INFO.USER_ID.eq(userId))
                .and(FILE_INFO.IS_DELETED.eq(false))
                .and(FILE_INFO.PARENT_ID.eq(StrUtil.emptyToNull(parentId)))
                .and(FILE_INFO.DISPLAY_NAME.eq(displayName))
                .and(FILE_INFO.IS_DIR.eq(false)));

        String suffix = StrUtil.blankToDefault(FileUtils.extName(displayName), "bin").toLowerCase();
        String mimeType = guessMimeType(displayName);
        IStorageOperationService storageService = storageServiceFacade.getStorageService(storagePlatformSettingId);
        FileObjectReferenceService referenceService = objectReferenceServiceProvider.getObject();
        LocalDateTime now = LocalDateTime.now();

        // 先把上传内容落为新物理对象（用一次性 objectKey，避免覆盖他人引用的键）
        String newObjectKey = FileUtils.generateObjectKey(userId, IdUtil.fastSimpleUUID() + "." + suffix);
        // spool 到临时文件：既算 md5 又支持异常回滚删孤儿对象；Content-MD5 在流读完前不可得
        java.io.File tempFile = null;
        String contentMd5;
        long actualSize;
        try {
            tempFile = java.nio.file.Files.createTempFile("gfs-dav-", ".upload").toFile();
            actualSize = java.nio.file.Files.copy(in, tempFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            contentMd5 = cn.hutool.crypto.digest.DigestUtil.md5Hex(tempFile);
            if (size != null && size >= 0 && size != actualSize) {
                throw new StorageOperationException(I18nUtils.getMessage("file.upload.failed"));
            }
        } catch (BusinessException | StorageOperationException e) {
            cleanupTemp(tempFile);
            throw e;
        } catch (Exception e) {
            cleanupTemp(tempFile);
            throw new StorageOperationException(I18nUtils.getMessage("file.upload.failed"), e);
        }

        boolean reused = false;
        try (FileObjectReferenceService.ReferenceLock ignored =
                     referenceService.acquireContentLock(storagePlatformSettingId, contentMd5, actualSize)) {
            FileInfo reusable = referenceService.findReusableFile(contentMd5, actualSize, storagePlatformSettingId);
            if (reusable != null) {
                newObjectKey = reusable.getObjectKey();
                reused = true;
            } else {
                try (java.io.InputStream up = java.nio.file.Files.newInputStream(tempFile.toPath())) {
                    storageService.uploadFile(up, newObjectKey);
                }
            }

            FileInfo fileInfo;
            if (existing != null) {
                // 覆盖：切换引用后清理旧物理对象（若无其它引用）
                String oldObjectKey = existing.getObjectKey();
                String oldMd5 = existing.getContentMd5();
                Long oldSize = existing.getSize();
                existing.setObjectKey(newObjectKey);
                existing.setContentMd5(contentMd5);
                existing.setSize(actualSize);
                existing.setSuffix(suffix);
                existing.setMimeType(mimeType);
                existing.setUploadTime(now);
                existing.setUpdateTime(now);
                updateById(existing);
                fileInfo = existing;
                if (oldObjectKey != null && !oldObjectKey.equals(newObjectKey)) {
                    FileInfo oldRef = new FileInfo();
                    oldRef.setObjectKey(oldObjectKey);
                    oldRef.setContentMd5(oldMd5);
                    oldRef.setSize(oldSize);
                    oldRef.setStoragePlatformSettingId(storagePlatformSettingId);
                    try {
                        referenceService.deletePhysicalFileIfUnreferencedWithLock(oldRef);
                    } catch (Exception e) {
                        log.warn("流式直传覆盖后清理旧对象失败: fileId={}, objectKey={}", existing.getId(), oldObjectKey, e);
                    }
                }
            } else {
                fileInfo = new FileInfo();
                fileInfo.setId(IdUtil.fastSimpleUUID());
                fileInfo.setObjectKey(newObjectKey);
                fileInfo.setOriginalName(displayName);
                fileInfo.setDisplayName(displayName);
                fileInfo.setSuffix(suffix);
                fileInfo.setMimeType(mimeType);
                fileInfo.setIsDir(false);
                fileInfo.setParentId(StrUtil.emptyToNull(parentId));
                fileInfo.setUserId(userId);
                fileInfo.setStoragePlatformSettingId(storagePlatformSettingId);
                fileInfo.setContentMd5(contentMd5);
                fileInfo.setSize(actualSize);
                fileInfo.setUploadTime(now);
                fileInfo.setUpdateTime(now);
                fileInfo.setIsDeleted(false);
                save(fileInfo);
            }
            return fileInfo;
        } catch (Exception e) {
            // 已写的孤儿对象尽力清理（未复用且未落库时才有孤儿）
            if (!reused) {
                try {
                    storageService.deleteFile(newObjectKey);
                } catch (Exception ignore) {
                }
            }
            if (e instanceof BusinessException be) {
                throw be;
            }
            if (e instanceof StorageOperationException soe) {
                throw soe;
            }
            throw new StorageOperationException(I18nUtils.getMessage("file.upload.failed"), e);
        } finally {
            cleanupTemp(tempFile);
        }
    }

    private void cleanupTemp(java.io.File tempFile) {
        if (tempFile != null && tempFile.exists()) {
            try {
                java.nio.file.Files.deleteIfExists(tempFile.toPath());
            } catch (Exception ignore) {
            }
        }
    }

    private String guessMimeType(String fileName) {
        String contentType = FileUtils.getContentType("." + StrUtil.subAfter(fileName, ".", true));
        // FileUtils.getContentType 对未知后缀返回 image/jpg 兜底；协议端未知类型统一 octet-stream 更准确
        String ext = StrUtil.subAfter(fileName, ".", true).toLowerCase();
        if ("image/jpg".equals(contentType) && !List.of("jpg", "jpeg", "png", "bmp").contains(ext)) {
            return "application/octet-stream";
        }
        return contentType;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void renameFile(String fileId, RenameFileCmd cmd) {
        FileInfo fileInfo = getAuthorizedFile(fileId);
        if (fileInfo.getDisplayName().equals(cmd.getDisplayName())) {
            return;
        }
        String storagePlatformSettingId = StoragePlatformContextHolder.getConfigId();
        // 挂载点记录禁止改名（它会破坏挂载点判定）
        if (Boolean.TRUE.equals(fileInfo.getIsDir()) && StrUtil.isEmpty(fileInfo.getParentId())
                && isMountStorage(fileInfo.getStoragePlatformSettingId())) {
            throw new BusinessException(I18nUtils.getMessage("mount.point.protected"));
        }
        String newName = cmd.getDisplayName().trim();
        String finalName = generateUniqueName(
                fileInfo.getUserId(),
                fileInfo.getParentId(),
                newName,
                fileInfo.getIsDir(),
                fileId,
                storagePlatformSettingId
        );
        // 挂载式：算旧/新相对路径 → 真实 rename → 改 display_name + 本记录 object_key；目录还要子树前缀替换
        boolean mountRename = isMountStorage(fileInfo.getStoragePlatformSettingId())
                && StrUtil.isNotEmpty(fileInfo.getStoragePlatformSettingId());
        String oldKey = null;
        String newKey = null;
        final String lockedOldKey;
        final String lockedNewKey;
        if (mountRename) {
            IStorageOperationService storageService =
                    storageServiceFacade.getStorageService(fileInfo.getStoragePlatformSettingId());
            oldKey = mountPathResolver.resolveRelativeKey(fileInfo, fileInfo.getStoragePlatformSettingId());
            String parentKey = StrUtil.isEmpty(fileInfo.getParentId()) ? ""
                    : mountPathResolver.resolveRelativeKey(
                            getAuthorizedFile(fileInfo.getParentId()), fileInfo.getStoragePlatformSettingId());
            newKey = parentKey.isEmpty() ? finalName : parentKey + "/" + finalName;
            lockedOldKey = oldKey;
            lockedNewKey = newKey;
            mountManager.locks().callWithLock(fileInfo.getStoragePlatformSettingId(), () ->
                    storageService.rename(lockedOldKey, lockedNewKey));
        } else {
            lockedOldKey = null;
            lockedNewKey = null;
        }
        fileInfo.setDisplayName(finalName);
        LocalDateTime now = LocalDateTime.now();
        fileInfo.setUpdateTime(now);
        fileInfo.setLastAccessTime(now);
        if (mountRename && StrUtil.isNotEmpty(fileInfo.getObjectKey())) {
            fileInfo.setObjectKey(newKey);
        }
        updateById(fileInfo);
        // 目录改名：子树 objectKey 前缀批量替换
        if (mountRename && Boolean.TRUE.equals(fileInfo.getIsDir())) {
            replaceSubtreeObjectKeyPrefix(fileInfo.getStoragePlatformSettingId(), oldKey, newKey);
            log.info("挂载目录改名完成，子树前缀已替换: {} -> {}", oldKey, newKey);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void moveFile(MoveFileCmd cmd) {
        if (CollUtil.isEmpty(cmd.getFileIds())) {
            throw new BusinessException(I18nUtils.getMessage("file.id.list.empty"));
        }

        String targetDirId = StringUtils.isBlank(cmd.getDirId()) ? null : cmd.getDirId();
        String userId = StpUtil.getLoginIdAsString();

        if (targetDirId != null) {
            FileInfo dirInfo = getAuthorizedFile(targetDirId);
            if (dirInfo == null || !dirInfo.getIsDir()) {
                throw new BusinessException(I18nUtils.getMessage("file.target.dir.invalid"));
            }
        }

        List<FileInfo> fileInfos = list(new QueryWrapper()
                .where(FILE_INFO.ID.in(cmd.getFileIds()))
                .and(FILE_INFO.USER_ID.eq(userId))
                .and(FILE_INFO.IS_DELETED.eq(false)));

        // 挂载式：仅允许同一挂载设置内移动；挂载点本身不可移动；真实 rename + 子树前缀替换 + parent_id 更新
        boolean anyMount = fileInfos.stream()
                .anyMatch(f -> isMountStorage(f.getStoragePlatformSettingId()));
        if (anyMount) {
            moveMountedFiles(fileInfos, targetDirId);
            return;
        }

        List<FileInfo> updateList = new ArrayList<>();

        for (FileInfo fileInfo : fileInfos) {
            if (Objects.equals(fileInfo.getParentId(), targetDirId)) {
                continue;
            }

            if (targetDirId != null && fileInfo.getIsDir()) {
                if (fileInfo.getId().equals(targetDirId) || isSubDirectory(fileInfo.getId(), targetDirId)) {
                    throw new BusinessException(I18nUtils.getMessage("file.cannot.move.to.self", 
                            new Object[]{fileInfo.getDisplayName()}));
                }
            }

            String finalName = generateUniqueName(
                    fileInfo.getUserId(),
                    targetDirId,
                    fileInfo.getDisplayName(),
                    fileInfo.getIsDir(),
                    fileInfo.getId(),
                    fileInfo.getStoragePlatformSettingId()
            );

            FileInfo updateEntity = UpdateEntity.of(FileInfo.class, fileInfo.getId());
            updateEntity.setParentId(targetDirId);
            updateEntity.setDisplayName(finalName);
            updateEntity.setUpdateTime(LocalDateTime.now());
            updateList.add(updateEntity);
        }

        if (!updateList.isEmpty()) {
            this.updateBatch(updateList);
        }
    }

    /**
     * 挂载式移动：同挂载设置内真实 rename + 子树前缀替换 + parent_id 更新
     * 跨设置/挂载点本身移动拒绝（mount.move.unsupported / mount.point.protected）。
     */
    private void moveMountedFiles(List<FileInfo> fileInfos, String targetDirId) {
        String targetSettingId = null;
        if (targetDirId != null) {
            FileInfo targetDir = getAuthorizedFile(targetDirId);
            targetSettingId = targetDir.getStoragePlatformSettingId();
        }
        for (FileInfo fileInfo : fileInfos) {
            String settingId = fileInfo.getStoragePlatformSettingId();
            if (!isMountStorage(settingId)) {
                // 挂载与普通平台混选：拒绝（普通平台之间保持原逻辑，不混批）
                throw new BusinessException(I18nUtils.getMessage("mount.move.unsupported"));
            }
            // 挂载点记录本身不可移动
            if (Boolean.TRUE.equals(fileInfo.getIsDir()) && StrUtil.isEmpty(fileInfo.getParentId())) {
                throw new BusinessException(I18nUtils.getMessage("mount.point.protected"));
            }
            // 目标必须是同挂载设置的目录（根层 = 同设置即可）
            if (targetDirId != null && !settingId.equals(targetSettingId)) {
                throw new BusinessException(I18nUtils.getMessage("mount.move.unsupported"));
            }
            if (targetDirId != null && fileInfo.getIsDir()
                    && (fileInfo.getId().equals(targetDirId) || isSubDirectory(fileInfo.getId(), targetDirId))) {
                throw new BusinessException(I18nUtils.getMessage("file.cannot.move.to.self",
                        new Object[]{fileInfo.getDisplayName()}));
            }
        }
        for (FileInfo fileInfo : fileInfos) {
            String settingId = fileInfo.getStoragePlatformSettingId();
            IStorageOperationService storageService = storageServiceFacade.getStorageService(settingId);
            String oldKey = mountPathResolver.resolveRelativeKey(fileInfo, settingId);
            String parentKey = StrUtil.isEmpty(fileInfo.getParentId()) ? ""
                    : mountPathResolver.resolveRelativeKey(
                            getAuthorizedFile(fileInfo.getParentId()), settingId);
            String targetParentKey = targetDirId == null ? ""
                    : mountPathResolver.resolveRelativeKey(getAuthorizedFile(targetDirId), settingId);
            String newKey = targetParentKey.isEmpty()
                    ? fileInfo.getDisplayName()
                    : targetParentKey + "/" + fileInfo.getDisplayName();
            if (oldKey.equals(newKey)) {
                continue;
            }
            String finalNewKey = newKey;
            mountManager.locks().callWithLock(settingId, () ->
                    storageService.rename(oldKey, finalNewKey));
            // DB 更新：parent_id + display_name + object_key
            fileInfo.setParentId(targetDirId);
            fileInfo.setUpdateTime(LocalDateTime.now());
            if (StrUtil.isNotEmpty(fileInfo.getObjectKey())) {
                fileInfo.setObjectKey(newKey);
            }
            updateById(fileInfo);
            // 目录：子树 objectKey 前缀替换
            if (Boolean.TRUE.equals(fileInfo.getIsDir())) {
                replaceSubtreeObjectKeyPrefix(settingId, oldKey, newKey);
            }
            log.info("挂载移动完成: {} -> {}", oldKey, finalNewKey);
        }
    }

    /**
     * 目录改名/移动后的子树 objectKey 前缀替换。
     * 用 Java 侧 startswith 精确过滤（避开 SQL LIKE 中 _/% 通配符与转义方言差异），
     * 也避免使用 REPLACE() 等数据库方言函数。调用方必须已持 MountLocks。
     */
    private void replaceSubtreeObjectKeyPrefix(String settingId, String oldPrefix, String newPrefix) {
        String likeEscaped = oldPrefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        List<FileInfo> subtree = list(new QueryWrapper()
                .where(FILE_INFO.OBJECT_KEY.like(likeEscaped + "/%"))
                .and(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.eq(settingId)));
        List<FileInfo> updates = new ArrayList<>();
        for (FileInfo record : subtree) {
            String key = record.getObjectKey();
            // like 只是粗筛，通配符可能误命中，二次精确校验
            if (key == null || !key.startsWith(oldPrefix + "/")) {
                continue;
            }
            FileInfo updateEntity = UpdateEntity.of(FileInfo.class, record.getId());
            updateEntity.setObjectKey(newPrefix + key.substring(oldPrefix.length()));
            updates.add(updateEntity);
        }
        if (!updates.isEmpty()) {
            updateBatch(updates);
            log.info("挂载子树前缀替换完成: settingId={}, {} -> {}, rows={}",
                    settingId, oldPrefix, newPrefix, updates.size());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<FileInfo> copyFiles(CopyFileCmd cmd) {
        if (CollUtil.isEmpty(cmd.getFileIds())) {
            throw new BusinessException(I18nUtils.getMessage("file.id.list.empty"));
        }

        String userId = StpUtil.getLoginIdAsString();
        String targetDirId = StringUtils.isBlank(cmd.getDirId()) ? null : cmd.getDirId();
        String targetStorageId = StoragePlatformContextHolder.getConfigId();

        if (targetDirId != null) {
            FileInfo targetDir = getAuthorizedFile(targetDirId);
            if (!Boolean.TRUE.equals(targetDir.getIsDir())) {
                throw new BusinessException(I18nUtils.getMessage("file.target.dir.invalid"));
            }
            targetStorageId = targetDir.getStoragePlatformSettingId();
        }

        List<String> requestedIds = cmd.getFileIds().stream().distinct().toList();
        List<FileInfo> sourceFiles = list(new QueryWrapper()
                .where(FILE_INFO.ID.in(requestedIds))
                .and(FILE_INFO.USER_ID.eq(userId))
                .and(FILE_INFO.IS_DELETED.eq(false)));
        Map<String, FileInfo> sourceMap = sourceFiles.stream()
                .collect(Collectors.toMap(FileInfo::getId, file -> file));

        if (sourceMap.size() != requestedIds.size()) {
            throw new BusinessException(I18nUtils.getMessage("file.not.found"));
        }

        List<FileInfo> copiedTopLevelFiles = new ArrayList<>();
        for (String sourceId : requestedIds) {
            FileInfo source = sourceMap.get(sourceId);
            if (!sameStorage(source.getStoragePlatformSettingId(), targetStorageId)) {
                throw new BusinessException(I18nUtils.getMessage("file.cannot.copy.across.storage"));
            }
            if (targetDirId != null && Boolean.TRUE.equals(source.getIsDir())
                    && (source.getId().equals(targetDirId)
                    || isSubDirectory(source.getId(), targetDirId))) {
                throw new BusinessException(I18nUtils.getMessage(
                        "file.cannot.copy.to.self", new Object[]{source.getDisplayName()}));
            }

            String topLevelName = generateUniqueName(
                    userId,
                    targetDirId,
                    source.getDisplayName(),
                    source.getIsDir(),
                    null,
                    targetStorageId
            );
            LocalDateTime now = LocalDateTime.now();
            List<FileInfo> copies = new ArrayList<>();
            FileInfo topLevelCopy = cloneFileInfo(source, targetDirId, topLevelName, userId, now);
            copies.add(topLevelCopy);
            if (Boolean.TRUE.equals(source.getIsDir())) {
                cloneDirectoryChildren(source.getId(), topLevelCopy.getId(), userId, now, copies);
            }

            // 每个顶层项目单独批量落库，让下一项的重名检测能够看到前一项。
            this.saveBatch(copies);
            copiedTopLevelFiles.add(topLevelCopy);
        }
        return copiedTopLevelFiles;
    }

    private void cloneDirectoryChildren(String sourceParentId,
                                        String targetParentId,
                                        String userId,
                                        LocalDateTime now,
                                        List<FileInfo> copies) {
        List<FileInfo> children = list(new QueryWrapper()
                .where(FILE_INFO.PARENT_ID.eq(sourceParentId))
                .and(FILE_INFO.USER_ID.eq(userId))
                .and(FILE_INFO.IS_DELETED.eq(false))
                .orderBy(FILE_INFO.IS_DIR.desc(), FILE_INFO.UPLOAD_TIME.asc()));

        for (FileInfo child : children) {
            FileInfo childCopy = cloneFileInfo(
                    child,
                    targetParentId,
                    child.getDisplayName(),
                    userId,
                    now
            );
            copies.add(childCopy);
            if (Boolean.TRUE.equals(child.getIsDir())) {
                cloneDirectoryChildren(child.getId(), childCopy.getId(), userId, now, copies);
            }
        }
    }

    private FileInfo cloneFileInfo(FileInfo source,
                                   String parentId,
                                   String displayName,
                                   String userId,
                                   LocalDateTime now) {
        FileInfo copy = new FileInfo();
        copy.setId(IdUtil.fastSimpleUUID());
        copy.setObjectKey(source.getObjectKey());
        copy.setOriginalName(source.getOriginalName());
        copy.setDisplayName(displayName);
        copy.setSuffix(source.getSuffix());
        copy.setSize(source.getSize());
        copy.setMimeType(source.getMimeType());
        copy.setIsDir(source.getIsDir());
        copy.setParentId(parentId);
        copy.setUserId(userId);
        copy.setContentMd5(source.getContentMd5());
        copy.setStoragePlatformSettingId(source.getStoragePlatformSettingId());
        copy.setUploadTime(now);
        copy.setUpdateTime(now);
        copy.setLastAccessTime(null);
        copy.setIsDeleted(false);
        copy.setDeletedTime(null);
        return copy;
    }

    private boolean sameStorage(String left, String right) {
        return Objects.equals(StrUtil.blankToDefault(left, ""), StrUtil.blankToDefault(right, ""));
    }

    // 检查target Id是否是source Id的子目录
    private boolean isSubDirectory(String sourceId, String targetId) {
        FileInfo current = getOne(new QueryWrapper()
                .where(FILE_INFO.ID.eq(targetId)));
        while (current != null && current.getParentId() != null) {
            if (current.getParentId().equals(sourceId)) {
                return true;
            }
            current = getOne(new QueryWrapper()
                    .where(FILE_INFO.ID.eq(current.getParentId())));
        }
        return false;
    }

    /**
     * 生成唯一的文件名（处理重名冲突）
     * <p>
     * - 如果不存在重名：返回原名称
     * - 如果存在重名：自动添加 (1), (2), (3)... 后缀
     *
     * @param userId        用户ID
     * @param parentId      父目录ID
     * @param desiredName   期望的文件名
     * @param isDir         是否是文件夹
     * @param excludeFileId 排除的文件ID（可选，用于重命名场景）
     * @return 唯一的文件名
     */
    @Override
    public String generateUniqueName(String userId, String parentId,
                                     String desiredName, Boolean isDir,
                                     String excludeFileId, String storagePlatformSettingId) {

        String nameWithoutExt = desiredName;
        String extension = "";
        if (!isDir && desiredName.contains(".")) {
            int lastDotIndex = desiredName.lastIndexOf(".");
            nameWithoutExt = desiredName.substring(0, lastDotIndex);
            extension = desiredName.substring(lastDotIndex);
        }
        QueryWrapper query = buildSameLevelQuery(
                userId,
                parentId,
                nameWithoutExt,
                isDir,
                excludeFileId,
                storagePlatformSettingId
        );
        List<FileInfo> existingFiles = list(query);
        if (existingFiles.isEmpty()) {
            return desiredName;
        }
        Set<Integer> usedSuffixes = extractUsedSuffixes(existingFiles, nameWithoutExt, extension, isDir);
        int suffixNum = 0;
        String finalName;
        do {
            suffixNum++;
            finalName = buildNameWithSuffix(nameWithoutExt, suffixNum, extension, isDir);
        } while (usedSuffixes.contains(suffixNum));
        log.info("检测到重名，自动重命名：{} -> {}", desiredName, finalName);
        return finalName;
    }

    /**
     * 构建查询同级目录下同类型文件的条件
     */
    private QueryWrapper buildSameLevelQuery(String userId, String parentId,
                                             String baseName, Boolean isDir,
                                             String excludeFileId, String storagePlatformSettingId) {
        QueryWrapper query = new QueryWrapper();

        query.where(FILE_INFO.USER_ID.eq(userId))
                .and(FILE_INFO.IS_DIR.eq(isDir))
                .and(FILE_INFO.IS_DELETED.eq(false));

        if (StrUtil.isBlank(parentId)) {
            query.and(FILE_INFO.PARENT_ID.isNull());
        } else {
            query.and(FILE_INFO.PARENT_ID.eq(parentId));
        }
        // ------------------------------------

        query.and(FILE_INFO.DISPLAY_NAME.like(baseName + "%"));

        if (StringUtils.isEmpty(storagePlatformSettingId)) {
            query.and(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.isNull());
        } else {
            query.and(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.eq(storagePlatformSettingId));
        }

        if (StrUtil.isNotBlank(excludeFileId)) {
            query.and(FILE_INFO.ID.ne(excludeFileId));
        }
        return query;
    }

    /**
     * 提取已使用的后缀数字
     * 示例：
     * - photo.jpg       -> 0
     * - photo(1).jpg    -> 1
     * - photo(2).jpg    -> 2
     * - photo(abc).jpg  -> -1 (忽略)
     */
    private Set<Integer> extractUsedSuffixes(List<FileInfo> existingFiles,
                                             String nameWithoutExt,
                                             String extension,
                                             Boolean isDir) {
        // 主体名后必须紧跟目标扩展名整体匹配：3.txt 不算作 3.json 的重名
        String quotedExt = isDir ? "" : Pattern.quote(extension);
        Pattern numbered = Pattern.compile(
                "^" + Pattern.quote(nameWithoutExt) + "\\((\\d+)\\)" + quotedExt + "$");
        String exactName = nameWithoutExt + (isDir ? "" : extension);
        return existingFiles.stream()
                .map(f -> {
                    String displayName = f.getDisplayName();

                    // 检查是否完全匹配基础名称（表示原始文件，后缀为 0）
                    if (displayName.equals(exactName)) {
                        return 0;
                    }

                    // 匹配 base(n)ext 格式的后缀
                    Matcher matcher = numbered.matcher(displayName);

                    if (matcher.find()) {
                        return Integer.parseInt(matcher.group(1));
                    }

                    return -1; // 不匹配的名称（忽略）
                })
                .filter(n -> n >= 0)  // 只保留有效的后缀数字
                .collect(Collectors.toSet());
    }

    /**
     * 构建带后缀的文件名
     *
     * @param nameWithoutExt 不含扩展名的文件名
     * @param suffixNum      后缀数字
     * @param extension      扩展名（含点号）
     * @param isDir          是否是文件夹
     * @return 完整的文件名
     */
    private String buildNameWithSuffix(String nameWithoutExt, int suffixNum,
                                       String extension, Boolean isDir) {
        if (isDir) {
            // 文件夹：baseName(1)
            return nameWithoutExt + "(" + suffixNum + ")";
        } else {
            // 文件：baseName(1).ext
            return nameWithoutExt + "(" + suffixNum + ")" + extension;
        }
    }

    @Override
    public List<FileVO> getDirectoryTreePath(String dirId) {
        String userId = StpUtil.getLoginIdAsString();
        FileInfo fileInfo = getAuthorizedFile(dirId);

        List<FileVO> pathList = new ArrayList<>();
        FileInfo current = fileInfo;

        // 递归向上查找，直到根节点（parent_id 为 null）
        while (current != null) {
            FileVO fileVO = converter.convert(current, FileVO.class);
            pathList.add(0, fileVO);

            // 查找父节点
            if (current.getParentId() != null) {
                current = getOne(new QueryWrapper()
                        .where(FILE_INFO.ID.eq(current.getParentId()))
                        .and(FILE_INFO.USER_ID.eq(userId))
                        .and(FILE_INFO.IS_DELETED.eq(false)));
            } else {
                break;
            }
        }

        return pathList;
    }

    @Override
    public PageResult<FileVO> getList(FileQry qry) {
        String userId = StpUtil.getLoginIdAsString();
        String storagePlatformSettingId = StoragePlatformContextHolder.getConfigId();

        // 根列表入口懒创建挂载点记录（文档 8.3：FileHomeServiceImpl 根列表入口，此处在根视图统一触发）
        if (StrUtil.isNotBlank(storagePlatformSettingId) && qry.getParentId() == null
                && !Boolean.TRUE.equals(qry.getIsRecents()) && isMountStorage(storagePlatformSettingId)) {
            try {
                Map<String, Object> settingMap = new HashMap<>();
                var setting = storageSettingService.getById(storagePlatformSettingId);
                if (setting != null) {
                    settingMap.put("id", setting.getId());
                    settingMap.put("configData", setting.getConfigData());
                }
                mountPointService.ensureMountPoint(userId, settingMap);
            } catch (Exception e) {
                log.warn("挂载点懒创建失败，不影响本次列表: settingId={}", storagePlatformSettingId, e);
            }
        }

        int pageNum = qry.getPage() == null ? 1 : qry.getPage();
        int pageSize = qry.getPageSize() == null ? 10 : qry.getPageSize();
        Page<FileVO> pageParam = new Page<>(pageNum, pageSize);

        QueryWrapper wrapper = new QueryWrapper();
        wrapper.select(
                        "fi.*",
                        "CASE WHEN fuf.file_id IS NOT NULL THEN 1 ELSE 0 END AS is_favorite"
                )
                .from(FILE_INFO.as("fi"))
                .leftJoin(FILE_USER_FAVORITES.as("fuf"))
                .on(FILE_INFO.ID.eq(FILE_USER_FAVORITES.FILE_ID)
                        .and(FILE_USER_FAVORITES.USER_ID.eq(userId)))
                .where(FILE_INFO.USER_ID.eq(userId))
                .and(FILE_INFO.IS_DELETED.eq(false));
        // 存储平台过滤
        if (StringUtils.isEmpty(storagePlatformSettingId)) {
            wrapper.and(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.isNull());
        } else {
            wrapper.and(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.eq(storagePlatformSettingId));
        }
        // 最近使用视图 (Recents)
        if (Boolean.TRUE.equals(qry.getIsRecents())) {
            wrapper.and(FILE_INFO.IS_DIR.eq(false))
                    .orderBy(FILE_INFO.LAST_ACCESS_TIME.desc());
            // 最近使用通常不需要分页，只需要前 N 条，或者也可以直接分页查询第一页
            pageParam.setPageSize(20);
        } else {
            // 收藏过滤
            if (Boolean.TRUE.equals(qry.getIsFavorite()) && qry.getParentId() == null) {
                wrapper.and(FILE_USER_FAVORITES.FILE_ID.isNotNull());
            }

            // 目录/文件视图过滤
            if (Boolean.TRUE.equals(qry.getIsDir())) {
                wrapper.and(FILE_INFO.IS_DIR.eq(true));
            }

            // 父目录逻辑：判断是否是特殊筛选视图
            boolean isTypeFilter = StrUtil.isNotBlank(qry.getFileType());
            boolean isFavoriteView = Boolean.TRUE.equals(qry.getIsFavorite()) && qry.getParentId() == null;
            boolean isDirFilter = Boolean.TRUE.equals(qry.getIsDir()) && qry.getParentId() == null;
            boolean isGlobalSearch = StrUtil.isNotBlank(qry.getKeyword()) && qry.getParentId() == null;

            if (!isTypeFilter && !isFavoriteView && !isDirFilter && !isGlobalSearch) {
                if (qry.getParentId() == null) {
                    wrapper.and(FILE_INFO.PARENT_ID.isNull());
                } else {
                    wrapper.and(FILE_INFO.PARENT_ID.eq(qry.getParentId()));
                }
            }

            // 关键词搜索
            if (StrUtil.isNotBlank(qry.getKeyword())) {
                String kw = qry.getKeyword().trim();
                wrapper.and(FILE_INFO.ORIGINAL_NAME.like(kw).or(FILE_INFO.DISPLAY_NAME.like(kw)));
            }

            // 文件类型过滤 (内部调用 applyFileTypeFilter)
            applyFileTypeFilter(wrapper, qry);

            // 排序逻辑
            String orderByField = switch (String.valueOf(qry.getOrderBy())) {
                case "displayName" -> "display_name";
                case "suffix" -> "suffix";
                case "size" -> "size";
                default -> "update_time";
            };
            boolean isAsc = "ASC".equalsIgnoreCase(qry.getOrderDirection());

            wrapper.orderBy(FILE_INFO.IS_DIR.desc());
            if ("display_name".equals(orderByField)) {
                // 逐段按数值比较名称中的数字，避免 1、10、2，以及 2 (1)、2 (10)、2 (2)。
                wrapper.orderBy("CASE WHEN display_name REGEXP '[0-9]' THEN 0 ELSE 1 END", isAsc)
                        .orderBy("LOWER(REGEXP_SUBSTR(display_name, '^[^0-9]*'))", isAsc)
                        .orderBy("CAST(REGEXP_SUBSTR(display_name, '[0-9]+', 1, 1) AS UNSIGNED)", isAsc)
                        .orderBy("CAST(REGEXP_SUBSTR(display_name, '[0-9]+', 1, 2) AS UNSIGNED)", isAsc)
                        .orderBy("CAST(REGEXP_SUBSTR(display_name, '[0-9]+', 1, 3) AS UNSIGNED)", isAsc)
                        .orderBy("LOWER(display_name)", isAsc);
            } else {
                wrapper.orderBy(orderByField, isAsc);
            }
            // 保证分页排序稳定，避免排序字段相同时跨页重复或遗漏。
            wrapper.orderBy(FILE_INFO.ID.asc());
        }

        // 执行分页查询 (使用 listAs 直接映射到 VO)
        Page<FileVO> resultPage = this.pageAs(pageParam, wrapper, FileVO.class);

        // 异步补充缩略图 (这里用 parallelStream 没问题，或者在转换后处理)
        if (CollUtil.isNotEmpty(resultPage.getRecords())) {
            resultPage.getRecords().parallelStream().forEach(vo ->
                    vo.setThumbnailUrl(fillThumbnailUrl(vo.getId(), vo.getSuffix()))
            );
        }

        return PageResult.success(resultPage.getRecords(), resultPage.getTotalRow());
    }

    /**
     * 填充图片封面链接
     */
    private String fillThumbnailUrl(String fileId, String suffix) {
        // 如果非图片文件跳过
        if (!FileTypeEnum.isImageFile(suffix)) {
            return null;
        }
        // 统一走同源文件流，避免把 minio/rustfs 等 Docker 内部地址返回给浏览器。
        // 流接口已纳入防盗链拦截，缩略图由 <img> 直连（带不了登录头），内嵌短时 previewToken
        String token = UUID.randomUUID().toString().replace("-", "");
        redisRepository.setExpire(RedisKey.getPreviewTokenKey(token), fileId, RedisKey.PREVIEW_TOKEN_EXPIRE);
        return "/api/file/stream/preview/" + fileId + "?previewToken=" + token;
    }

    @Override
    public Long calculateUsedStorage() {
        String storagePlatformSettingId = StoragePlatformContextHolder.getConfigId();

        QueryWrapper query = new QueryWrapper()
                .where(FILE_INFO.IS_DELETED.eq(false))
                .and(FILE_INFO.IS_DIR.eq(false));
        // 本地存储的 configId 为 null，需用 IS NULL 匹配，否则等值比较永远不成立
        if (StringUtils.isEmpty(storagePlatformSettingId)) {
            query.and(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.isNull());
        } else {
            query.and(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.eq(storagePlatformSettingId));
        }

        // 统计总大小
        return this.list(query).stream()
                .map(FileInfo::getSize)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
    }

    @Override
    public FileDetailVO getFileDetails(String fileId) {
        FileInfo fileInfo = getAuthorizedFile(fileId);
        FileDetailVO vo = converter.convert(fileInfo, FileDetailVO.class);
        if (vo.getIsDir()) {
            Map<String, Long> stats = new HashMap<>();
            stats.put("size", 0L);
            stats.getOrDefault("fileCount", 0L);
            stats.put("fileCount", 0L);
            stats.put("folderCount", 0L);
            recursiveAccumulate(fileId, stats);

            //如果为文件夹则需要统计该文件夹下所有文件
            vo.setSize(stats.get("size"));
            vo.setIncludeFiles(stats.get("fileCount").intValue());
            vo.setIncludeFolders(stats.get("folderCount").intValue());
        } else {
            vo.setIncludeFiles(0);
            vo.setIncludeFolders(0);
            vo.setThumbnailUrl(fillThumbnailUrl(fileInfo.getId(), vo.getSuffix()));
        }
        return vo;
    }

    /**
     * 递归统计文件夹信息
     */
    private void recursiveAccumulate(String parentId, Map<String, Long> stats) {
        String storagePlatformSettingId = StoragePlatformContextHolder.getConfigId();
        QueryWrapper query = new QueryWrapper()
                .where(FILE_INFO.PARENT_ID.eq(parentId))
                .and(FILE_INFO.IS_DELETED.eq(false));
        // 同 calculateUsedStorage：本地存储的 configId 为 null
        if (StringUtils.isEmpty(storagePlatformSettingId)) {
            query.and(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.isNull());
        } else {
            query.and(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.eq(storagePlatformSettingId));
        }
        List<FileInfo> children = this.list(query);

        if (CollUtil.isEmpty(children)) {
            return;
        }

        for (FileInfo child : children) {
            if (child.getIsDir()) {
                // 统计文件夹个数并向下递归
                stats.put("folderCount", stats.get("folderCount") + 1);
                recursiveAccumulate(child.getId(), stats);
            } else {
                // 统计文件个数及大小
                stats.put("fileCount", stats.get("fileCount") + 1);
                long fileSize = child.getSize() != null ? child.getSize() : 0L;
                stats.put("size", stats.get("size") + fileSize);
            }
        }
    }

    @Override
    public List<FileVO> getDirs(String parentId) {
        String userId = StpUtil.getLoginIdAsString();
        String storagePlatformSettingId = StoragePlatformContextHolder.getConfigId();
        QueryWrapper wrapper = new QueryWrapper();
        wrapper.where(FILE_INFO.USER_ID.eq(userId)
                .and(FILE_INFO.IS_DELETED.eq(false))
                .and(FILE_INFO.IS_DIR.eq(true))
        );
        // 本地存储的 configId 为 null，需用 IS NULL 匹配
        if (StringUtils.isEmpty(storagePlatformSettingId)) {
            wrapper.and(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.isNull());
        } else {
            wrapper.and(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.eq(storagePlatformSettingId));
        }

        if (StrUtil.isNotBlank(parentId)) {
            wrapper.and(FILE_INFO.PARENT_ID.eq(parentId));
        } else {
            wrapper.and(FILE_INFO.PARENT_ID.isNull());
        }
        wrapper.orderBy(FILE_INFO.UPDATE_TIME.desc());
        return this.listAs(wrapper, FileVO.class);
    }

    @Override
    public List<FileVO> getByFileIds(List<String> fileIds) {
        if (CollUtil.isEmpty(fileIds)) {
            return List.of();
        }
        List<FileInfo> fileInfos = this.list(new QueryWrapper()
                .where(FILE_INFO.ID.in(fileIds))
                .and(FILE_INFO.IS_DELETED.eq(false)));
        return converter.convert(fileInfos, FileVO.class);
    }

    /**
     * 应用文件类型过滤
     */
    private void applyFileTypeFilter(QueryWrapper wrapper, FileQry qry) {
        if (qry.getFileType() == null || qry.getFileType().trim().isEmpty()) {
            return;
        }
        FileTypeEnum.FileCategory category = FileTypeEnum.FileCategory.fromCode(qry.getFileType());
        if (category != null) {
            // 按大类筛选
            applyFilterByCategory(wrapper, category);
            return;
        }
        FileTypeEnum fileType = FileTypeEnum.fromType(qry.getFileType());
        if (fileType == null) {
            log.warn("未识别的文件类型: {}", qry.getFileType());
            return;
        }
        // 按具体类型筛选
        applyFilterByType(wrapper, fileType);
    }

    /**
     * 按分类筛选
     */
    private void applyFilterByCategory(QueryWrapper wrapper, FileTypeEnum.FileCategory category) {
        List<String> categorySuffixes = FileTypeEnum.getSuffixesByCategory(category);

        if (category == FileTypeEnum.FileCategory.OTHER) {
            // OTHER 分类：匹配 OTHER 分类的已知后缀 + 所有未知后缀
            List<String> allOtherKnownSuffixes = FileTypeEnum.getAllKnownSuffixesExcluding(FileTypeEnum.FileCategory.OTHER);

            wrapper.and(FILE_INFO.IS_DIR.eq(false))
                    .and(
                            FILE_INFO.SUFFIX.in(categorySuffixes) // zip、rar 等
                                    .or(FILE_INFO.SUFFIX.notIn(allOtherKnownSuffixes)) // 真正的未知类型
                                    .or(FILE_INFO.SUFFIX.isNull().or(FILE_INFO.SUFFIX.eq("")))
                    );
        } else {
            // 常规分类：直接匹配后缀
            if (!categorySuffixes.isEmpty()) {
                wrapper.and(FILE_INFO.IS_DIR.eq(false))
                        .and(FILE_INFO.SUFFIX.in(categorySuffixes));
            }
        }
    }

    /**
     * 按具体类型筛选
     */
    private void applyFilterByType(QueryWrapper wrapper, FileTypeEnum fileType) {
        if (fileType.isOther()) {
            // 其他类型：排除所有已知后缀
            List<String> knownSuffixes = FileTypeEnum.getAllKnownSuffixes();
            wrapper.and(FILE_INFO.IS_DIR.eq(false))
                    .and(
                            FILE_INFO.SUFFIX.notIn(knownSuffixes)
                                    .or(FILE_INFO.SUFFIX.isNull().or(FILE_INFO.SUFFIX.eq("")))
                    );
        } else {
            // 具体类型：直接匹配后缀
            List<String> suffixes = fileType.getSuffixes();
            if (suffixes != null && !suffixes.isEmpty()) {
                wrapper.and(FILE_INFO.IS_DIR.eq(false))
                        .and(FILE_INFO.SUFFIX.in(suffixes));
            }
        }
    }
}
