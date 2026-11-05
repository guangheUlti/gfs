package com.guanghe.fs.file.mount;

import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.file.cache.TransferTaskCacheManager;
import com.guanghe.fs.file.domain.FileCollection;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.domain.FileShareItem;
import com.guanghe.fs.file.domain.FileTransferTask;
import com.guanghe.fs.file.mapper.FileCollectionMapper;
import com.guanghe.fs.file.mapper.FileCollectionSubmissionMapper;
import com.guanghe.fs.file.mapper.FileInfoMapper;
import com.guanghe.fs.file.mapper.FileShareAccessRecordMapper;
import com.guanghe.fs.file.mapper.FileShareItemMapper;
import com.guanghe.fs.file.mapper.FileShareMapper;
import com.guanghe.fs.file.mapper.FileTransferTaskMapper;
import com.guanghe.fs.file.mapper.FileUserFavoritesMapper;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.guanghe.fs.file.domain.table.FileCollectionSubmissionTableDef.FILE_COLLECTION_SUBMISSION;
import static com.guanghe.fs.file.domain.table.FileCollectionTableDef.FILE_COLLECTION;
import static com.guanghe.fs.file.domain.table.FileInfoTableDef.FILE_INFO;
import static com.guanghe.fs.file.domain.table.FileShareAccessRecordTableDef.FILE_SHARE_ACCESS_RECORD;
import static com.guanghe.fs.file.domain.table.FileShareItemTableDef.FILE_SHARE_ITEM;
import static com.guanghe.fs.file.domain.table.FileShareTableDef.FILE_SHARE;
import static com.guanghe.fs.file.domain.table.FileTransferTaskTableDef.FILE_TRANSFER_TASK;
import static com.guanghe.fs.file.domain.table.FileUserFavoritesTableDef.FILE_USER_FAVORITES;

/**
 * 存储卸载服务：删除接入存储配置时回收该存储在 GFS 侧留下的全部记录。
 * <p>
 * 安全红线——本服务只删数据库行，绝不发起任何远端/磁盘删除：挂载与远程协议存储以真实目录、
 * 远端服务器为唯一真相源，OSS 配置删除后其桶内对象同样保留（只是不再被 GFS 索引）。
 * 物理文件的删除只属于系统存储的文件删除链路。
 * <p>
 * 全部按 settingId 收口，与具体平台无关：任何平台新增配置都自动落到同一套卸载行为上。
 * 系统存储的索引行 {@code storage_platform_setting_id} 恒为 NULL，因此不会被本服务命中。
 *
 * @Author: guangheUlti
 * @Date: 2026/11/05
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageUnmountService {

    /**
     * 单批 IN 条件长度：线上单个存储可达十万级索引行，逐批处理避免一次性装载与超长 SQL
     */
    private static final int BATCH_SIZE = 500;

    private final FileInfoMapper fileInfoMapper;
    private final FileUserFavoritesMapper favoritesMapper;
    private final FileShareItemMapper shareItemMapper;
    private final FileShareMapper shareMapper;
    private final FileShareAccessRecordMapper shareAccessRecordMapper;
    private final FileCollectionMapper collectionMapper;
    private final FileCollectionSubmissionMapper collectionSubmissionMapper;
    private final FileTransferTaskMapper transferTaskMapper;
    private final TransferTaskCacheManager transferTaskCacheManager;

    /**
     * 卸载指定存储配置在 GFS 侧的全部痕迹：
     * 网盘索引（含回收站）→ 收藏 → 分享项（项清空则连带分享与其访问记录）→ 文件收集与提交记录 → 传输任务。
     * <p>
     * 调用方负责持挂载锁并包裹事务：任一步失败整体回滚，宁可拒绝删除也不留指向已删配置的行。
     */
    @Transactional(rollbackFor = Exception.class)
    public void unmount(String settingId) {
        Set<String> touchedShareIds = new HashSet<>();
        long fileInfoCount = 0;
        long favoritesCount = 0;
        long shareItemCount = 0;

        while (true) {
            List<String> fileIds = fileInfoMapper
                    .selectListByQuery(new QueryWrapper()
                            .select(FILE_INFO.ID)
                            .from(FILE_INFO)
                            .where(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.eq(settingId))
                            .limit(BATCH_SIZE))
                    .stream()
                    .map(FileInfo::getId)
                    .toList();
            if (fileIds.isEmpty()) {
                break;
            }

            List<String> shareIds = shareItemMapper
                    .selectListByQuery(new QueryWrapper()
                            .select(FILE_SHARE_ITEM.SHARE_ID)
                            .from(FILE_SHARE_ITEM)
                            .where(FILE_SHARE_ITEM.FILE_ID.in(fileIds)))
                    .stream()
                    .map(FileShareItem::getShareId)
                    .filter(StrUtil::isNotBlank)
                    .toList();

            favoritesCount += favoritesMapper.deleteByQuery(new QueryWrapper()
                    .where(FILE_USER_FAVORITES.FILE_ID.in(fileIds)));

            QueryWrapper shareItemQuery = new QueryWrapper()
                    .where(FILE_SHARE_ITEM.FILE_ID.in(fileIds));
            shareItemCount += shareItemMapper.deleteByQuery(shareItemQuery);
            touchedShareIds.addAll(shareIds);

            fileInfoMapper.deleteByQuery(new QueryWrapper()
                    .where(FILE_INFO.ID.in(fileIds)));
            fileInfoCount += fileIds.size();
        }

        long shareCount = deleteEmptyShares(touchedShareIds);
        long collectionCount = deleteCollections(settingId);
        long taskCount = deleteTransferTasks(settingId);

        if (fileInfoCount + favoritesCount + shareItemCount + shareCount
                + collectionCount + taskCount > 0) {
            log.info("存储卸载完成，GFS 侧记录已清理（远端与真实文件未动）: settingId={}, 索引={}, 收藏={}, 分享项={}, 分享={}, 收集={}, 传输任务={}",
                    settingId, fileInfoCount, favoritesCount, shareItemCount,
                    shareCount, collectionCount, taskCount);
        }
    }

    /**
     * 分享项被卸载清空后，分享本身已无可展示内容，连同访问记录一并回收；
     * 仍指向其他存储文件的分享保持原样。
     */
    private long deleteEmptyShares(Set<String> touchedShareIds) {
        if (touchedShareIds.isEmpty()) {
            return 0;
        }
        List<String> shareIdList = new ArrayList<>(touchedShareIds);
        long deleted = 0;
        for (int i = 0; i < shareIdList.size(); i += BATCH_SIZE) {
            List<String> batch = shareIdList.subList(i, Math.min(shareIdList.size(), i + BATCH_SIZE));
            Set<String> stillShared = shareItemMapper
                    .selectListByQuery(new QueryWrapper()
                            .select(FILE_SHARE_ITEM.SHARE_ID)
                            .from(FILE_SHARE_ITEM)
                            .where(FILE_SHARE_ITEM.SHARE_ID.in(batch))
                            .groupBy(FILE_SHARE_ITEM.SHARE_ID))
                    .stream()
                    .map(FileShareItem::getShareId)
                    .collect(Collectors.toSet());
            List<String> emptyShareIds = batch.stream()
                    .filter(shareId -> !stillShared.contains(shareId))
                    .toList();
            if (emptyShareIds.isEmpty()) {
                continue;
            }
            shareAccessRecordMapper.deleteByQuery(new QueryWrapper()
                    .where(FILE_SHARE_ACCESS_RECORD.SHARE_ID.in(emptyShareIds)));
            deleted += shareMapper.deleteByQuery(new QueryWrapper()
                    .where(FILE_SHARE.ID.in(emptyShareIds)));
        }
        return deleted;
    }

    /**
     * 回收目标文件夹落在该存储上的文件收集：收集链接指向的目录已随索引删除，
     * 保留下来只会让公开上传口持续报「存储配置不存在」。
     */
    private long deleteCollections(String settingId) {
        List<String> collectionIds = collectionMapper
                .selectListByQuery(new QueryWrapper()
                        .select(FILE_COLLECTION.ID)
                        .from(FILE_COLLECTION)
                        .where(FILE_COLLECTION.STORAGE_PLATFORM_SETTING_ID.eq(settingId)))
                .stream()
                .map(FileCollection::getId)
                .toList();
        if (collectionIds.isEmpty()) {
            return 0;
        }
        collectionSubmissionMapper.deleteByQuery(new QueryWrapper()
                .where(FILE_COLLECTION_SUBMISSION.COLLECTION_ID.in(collectionIds)));

        QueryWrapper taskQuery = new QueryWrapper()
                .where(FILE_TRANSFER_TASK.COLLECTION_ID.in(collectionIds));
        List<FileTransferTask> collectionTasks = transferTaskMapper.selectListByQuery(taskQuery);
        if (!collectionTasks.isEmpty()) {
            transferTaskMapper.deleteByQuery(taskQuery);
            cleanTaskCache(collectionTasks);
        }

        collectionMapper.deleteByQuery(new QueryWrapper()
                .where(FILE_COLLECTION.ID.in(collectionIds)));
        return collectionIds.size();
    }

    /**
     * 回收该存储上的传输任务：任务行留着会在续传、合并、取消时去加载已删除的存储配置而报错，
     * 且进行中的任务在合并阶段会重新写回指向已删配置的索引行。
     */
    private long deleteTransferTasks(String settingId) {
        long deleted = 0;
        while (true) {
            List<FileTransferTask> tasks = transferTaskMapper
                    .selectListByQuery(new QueryWrapper()
                            .select(FILE_TRANSFER_TASK.ID, FILE_TRANSFER_TASK.TASK_ID)
                            .from(FILE_TRANSFER_TASK)
                            .where(FILE_TRANSFER_TASK.STORAGE_PLATFORM_SETTING_ID.eq(settingId))
                            .limit(BATCH_SIZE));
            if (tasks.isEmpty()) {
                return deleted;
            }
            List<Long> ids = tasks.stream()
                    .map(FileTransferTask::getId)
                    .filter(Objects::nonNull)
                    .toList();
            if (ids.isEmpty()) {
                return deleted;
            }
            deleted += transferTaskMapper.deleteByQuery(new QueryWrapper()
                    .where(FILE_TRANSFER_TASK.ID.in(ids)));
            cleanTaskCache(tasks);
        }
    }

    private void cleanTaskCache(List<FileTransferTask> tasks) {
        transferTaskCacheManager.cleanTasks(tasks.stream()
                .map(FileTransferTask::getTaskId)
                .filter(StrUtil::isNotBlank)
                .toList());
    }
}
