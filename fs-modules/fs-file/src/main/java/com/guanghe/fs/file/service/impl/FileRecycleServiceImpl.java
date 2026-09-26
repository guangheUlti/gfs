package com.guanghe.fs.file.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.domain.qry.FileRecycleQry;
import com.guanghe.fs.file.domain.table.FileInfoTableDef;
import com.guanghe.fs.file.domain.vo.FileRecycleVO;
import com.guanghe.fs.file.mount.MountPathResolver;
import com.guanghe.fs.file.service.FileInfoService;
import com.guanghe.fs.file.service.FileObjectReferenceService;
import com.guanghe.fs.file.service.FileRecycleService;
import com.guanghe.fs.file.service.FileUserFavoritesService;
import com.guanghe.fs.framework.common.domain.PageResult;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.storage.facade.StorageServiceFacade;
import com.guanghe.fs.storage.plugin.core.IStorageOperationService;
import com.guanghe.fs.storage.plugin.core.context.StoragePlatformContextHolder;
import io.github.linpeilie.Converter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.mybatisflex.core.query.QueryMethods.notExists;
import static com.guanghe.fs.file.domain.table.FileInfoTableDef.FILE_INFO;

/**
 * 回收站服务接口实现
 *
 * @Author: guangheUlti
 * @Date: 2025/5/8 9:35
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileRecycleServiceImpl implements FileRecycleService {

    private final Converter converter;

    private final FileInfoService fileInfoService;

    private final FileUserFavoritesService fileUserFavoritesService;

    private final FileObjectReferenceService objectReferenceService;

    private final StorageServiceFacade storageServiceFacade;

    private final MountPathResolver mountPathResolver;

    @Override
    public PageResult<FileRecycleVO> getRecyclePages(FileRecycleQry qry) {
        String userId = StpUtil.getLoginIdAsString();
        String configId = StoragePlatformContextHolder.getConfigId();
        int pageNum = qry.getPage() == null ? 1 : qry.getPage();
        int pageSize = qry.getPageSize() == null ? 10 : qry.getPageSize();

        FileInfoTableDef t1 = FILE_INFO.as("t1");
        FileInfoTableDef t2 = FILE_INFO.as("t2");

        QueryWrapper queryWrapper = QueryWrapper.create()
                .select(t1.ALL_COLUMNS)
                .from(t1)
                .where(t1.USER_ID.eq(userId))
                .and(t1.IS_DELETED.eq(true));
        applyStoragePlatformFilter(queryWrapper, t1, configId);

        if (StrUtil.isNotBlank(qry.getKeyword())) {
            String keyword = qry.getKeyword().trim();
            queryWrapper.and(
                    t1.ORIGINAL_NAME.like(keyword)
                            .or(t1.DISPLAY_NAME.like(keyword))
            );
        } else {
            queryWrapper.and(
                    t1.PARENT_ID.isNull()
                            .or(
                                    notExists(
                                            QueryWrapper.create()
                                                    .select(t2.ID)
                                                    .from(t2)
                                                    .where(t2.ID.eq(t1.PARENT_ID))
                                                    .and(t2.IS_DELETED.eq(true))
                                    )
                            )
            );
        }

        queryWrapper.orderBy(FILE_INFO.IS_DIR.desc())
                .orderBy(FILE_INFO.UPDATE_TIME.desc());

        Page<FileInfo> resultPage = fileInfoService.page(new Page<>(pageNum, pageSize), queryWrapper);

        List<FileRecycleVO> voList = converter.convert(resultPage.getRecords(), FileRecycleVO.class);
        return PageResult.success(voList, resultPage.getTotalRow());
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restoreFiles(List<String> fileIds) {
        if (CollUtil.isEmpty(fileIds)) return;
        String userId = StpUtil.getLoginIdAsString();

        Set<String> allIdsToRestore = collectFileIdsRecursively(
                fileIds,
                userId,
                wrapper -> wrapper.and(FILE_INFO.IS_DELETED.eq(true))
        );

        Set<String> parentIdsInRecycle = collectParentIdsInRecycle(fileIds, userId);
        allIdsToRestore.addAll(parentIdsInRecycle);

        if (CollUtil.isEmpty(allIdsToRestore)) {
            throw new BusinessException(I18nUtils.getMessage("recycle.file.not.found"));
        }

        UpdateChain.of(FileInfo.class)
                .set(FileInfo::getIsDeleted, false)
                .set(FileInfo::getDeletedTime, null)
                .where(FILE_INFO.ID.in(allIdsToRestore))
                .and(FILE_INFO.USER_ID.eq(userId))
                .update();

        log.info("用户 {} 恢复文件/文件夹，共 {} 项", userId, allIdsToRestore.size());
    }

    /**
     * 向上递归收集：找出这些文件在回收站中的所有祖先文件夹
     */
    private Set<String> collectParentIdsInRecycle(List<String> currentIds, String userId) {
        Set<String> allParentIds = new HashSet<>();
        List<String> runnerIds = new ArrayList<>(currentIds);

        while (CollUtil.isNotEmpty(runnerIds)) {
            List<String> pIds = fileInfoService.queryChain()
                    .select(FILE_INFO.PARENT_ID)
                    .where(FILE_INFO.ID.in(runnerIds))
                    .and(FILE_INFO.PARENT_ID.isNotNull())
                    .and(FILE_INFO.USER_ID.eq(userId))
                    .listAs(String.class)
                    .stream().filter(StrUtil::isNotBlank).distinct().collect(Collectors.toList());

            if (CollUtil.isEmpty(pIds)) break;

            // 2. 看看这些 parentId 中，哪些还在回收站里 (is_deleted = true)
            List<String> deletedParents = fileInfoService.queryChain()
                    .select(FILE_INFO.ID)
                    .where(FILE_INFO.ID.in(pIds))
                    .and(FILE_INFO.IS_DELETED.eq(true))
                    .listAs(String.class);

            if (CollUtil.isEmpty(deletedParents)) break;

            // 3. 收集并继续往上找
            allParentIds.addAll(deletedParents);
            runnerIds = deletedParents;
        }
        return allParentIds;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void permanentlyDeleteFiles(List<String> fileIds) {
        permanentlyDeleteFiles(fileIds, StpUtil.getLoginIdAsString());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void permanentlyDeleteFiles(List<String> fileIds, String userId) {
        doPermanentDelete(fileIds, userId, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void permanentlyDeleteActiveFiles(List<String> fileIds) {
        doPermanentDelete(fileIds, StpUtil.getLoginIdAsString(), false);
    }

    private void doPermanentDelete(List<String> fileIds, String userId, boolean onlyRecycled) {
        if (CollUtil.isEmpty(fileIds)) {
            return;
        }
        Set<String> allFileIds = collectFileIdsRecursively(
                fileIds,
                userId,
                onlyRecycled ? wrapper -> wrapper.and(FILE_INFO.IS_DELETED.eq(true)) : null
        );
        if (CollUtil.isEmpty(allFileIds)) {
            throw new BusinessException(I18nUtils.getMessage(
                    onlyRecycled ? "recycle.file.not.found.delete" : "file.not.found"));
        }
        List<FileInfo> allFiles = fileInfoService.listByIds(allFileIds);
        List<FileInfo> physicalObjects = allFiles.stream()
                .filter(file -> StrUtil.isNotBlank(file.getObjectKey()))
                .collect(Collectors.toMap(
                        file -> String.valueOf(file.getStoragePlatformSettingId()) + "|" + file.getObjectKey(),
                        file -> file,
                        (left, right) -> left,
                        LinkedHashMap::new
                ))
                .values().stream().toList();

        // 挂载式：目录记录不走秒传引用链路，afterCommit 直接 deleteDirectory 清真实目录（8.4：真实目录随永久删除清理）。
        // 目录行的 object_key 恒为 NULL（真实键由显示名链推导），必须在删除前的事务内先解析好相对键，
        // 否则 afterCommit 时行已删、无从解析，目录在真实存储/内存树中残留并被对账导回。
        Map<String, List<FileInfo>> mountDirDeletes = new LinkedHashMap<>();
        for (FileInfo file : allFiles) {
            if (!Boolean.TRUE.equals(file.getIsDir())
                    || !isMountStorage(file.getStoragePlatformSettingId())) {
                continue;
            }
            try {
                String dirKey = mountPathResolver.resolveRelativeKey(file, file.getStoragePlatformSettingId());
                if (StrUtil.isBlank(dirKey)) {
                    continue; // 挂载点自身：无真实目录
                }
                file.setObjectKey(dirKey);
                mountDirDeletes.computeIfAbsent(file.getStoragePlatformSettingId(), k -> new ArrayList<>()).add(file);
            } catch (Exception e) {
                log.warn("挂载目录相对键解析失败，跳过真实目录清理: id={}, error={}", file.getId(), e.getMessage());
            }
        }
        physicalObjects = physicalObjects.stream()
                .filter(file -> !(Boolean.TRUE.equals(file.getIsDir())
                        && mountDirDeletes.containsKey(String.valueOf(file.getStoragePlatformSettingId()))
                        && StrUtil.isNotBlank(file.getObjectKey())))
                .toList();
        final Map<String, List<FileInfo>> mountDirDeletesFinal = mountDirDeletes;
        final List<FileInfo> physicalObjectsFinal = physicalObjects;

        fileInfoService.removeByIds(allFileIds);

        fileUserFavoritesService.removeByFileIds(allFileIds, userId);

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        for (FileInfo file : physicalObjectsFinal) {
                            try {
                                objectReferenceService.deletePhysicalFileIfUnreferencedWithLock(file);
                            } catch (Exception e) {
                                log.error("删除无引用物理文件失败: {}", file.getObjectKey(), e);
                            }
                        }
                        // 挂载目录：直接删真实目录树（幂等，真实目录已不存在时静默通过）
                        for (Map.Entry<String, List<FileInfo>> entry : mountDirDeletesFinal.entrySet()) {
                            try {
                                IStorageOperationService storageService =
                                        storageServiceFacade.getStorageService(entry.getKey());
                                for (FileInfo dir : entry.getValue()) {
                                    try {
                                        storageService.deleteDirectory(dir.getObjectKey());
                                        log.info("挂载目录永久删除完成: objectKey={}", dir.getObjectKey());
                                    } catch (Exception e) {
                                        log.error("挂载目录永久删除失败: {}", dir.getObjectKey(), e);
                                    }
                                }
                            } catch (Exception e) {
                                log.error("获取挂载存储实例失败，跳过目录删除: settingId={}", entry.getKey(), e);
                            }
                        }
                    }
                }
        );
    }

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

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void clearRecycles() {
        String userId = StpUtil.getLoginIdAsString();
        String configId = StoragePlatformContextHolder.getConfigId();

        FileInfoTableDef t1 = FILE_INFO.as("t1");
        FileInfoTableDef t2 = FILE_INFO.as("t2");

        // 直接从数据库查出所有回收站的“顶层项”ID
        QueryWrapper topLevelWrapper = QueryWrapper.create()
                .select(t1.ID)
                .from(t1)
                .where(t1.USER_ID.eq(userId))
                .and(t1.IS_DELETED.eq(true))
                .and(t1.PARENT_ID.isNull().or(
                        notExists(QueryWrapper.create().from(t2).where(t2.ID.eq(t1.PARENT_ID)).and(t2.IS_DELETED.eq(true)))
                ));
        applyStoragePlatformFilter(topLevelWrapper, t1, configId);
        List<String> topLevelIds = fileInfoService.listAs(topLevelWrapper, String.class);

        if (CollUtil.isNotEmpty(topLevelIds)) {
            this.permanentlyDeleteFiles(topLevelIds);
        }
    }

    /**
     * 追加存储平台过滤条件。
     * <p>
     * 本地存储的 configId 规范化后为 null，而数据库里存的也是 NULL，
     * 直接 eq(null) 会生成恒不成立的 "= NULL"，故必须走 IS NULL 分支。
     */
    private void applyStoragePlatformFilter(QueryWrapper wrapper, FileInfoTableDef table, String configId) {
        if (StrUtil.isBlank(configId)) {
            wrapper.and(table.STORAGE_PLATFORM_SETTING_ID.isNull());
        } else {
            wrapper.and(table.STORAGE_PLATFORM_SETTING_ID.eq(configId));
        }
    }


    /**
     * 递归收集文件ID（通用方法）
     *
     * @param fileIds 初始文件ID列表
     * @param userId  用户ID
     * @param filter  过滤条件（可选）
     * @return 收集到的所有文件ID集合
     */
    private Set<String> collectFileIdsRecursively(
            List<String> fileIds,
            String userId,
            Consumer<QueryWrapper> filter) {

        QueryWrapper initialWrapper = new QueryWrapper()
                .where(FILE_INFO.ID.in(fileIds))
                .and(FILE_INFO.USER_ID.eq(userId));

        if (filter != null) {
            filter.accept(initialWrapper);
        }

        List<FileInfo> files = fileInfoService.list(initialWrapper);

        if (CollUtil.isEmpty(files)) {
            return Collections.emptySet();
        }

        Set<String> allFileIds = new HashSet<>();
        files.forEach(file -> collectFileIdsRecursive(file, allFileIds, userId, filter));

        return allFileIds;
    }

    private void collectFileIdsRecursive(
            FileInfo file,
            Set<String> allFileIds,
            String userId,
            Consumer<QueryWrapper> filter) {

        allFileIds.add(file.getId());

        if (file.getIsDir()) {
            QueryWrapper wrapper = new QueryWrapper()
                    .where(FILE_INFO.PARENT_ID.eq(file.getId()))
                    .and(FILE_INFO.USER_ID.eq(userId));

            if (filter != null) {
                filter.accept(wrapper);
            }

            List<FileInfo> children = fileInfoService.list(wrapper);
            children.forEach(child -> collectFileIdsRecursive(child, allFileIds, userId, filter));
        }
    }
}
