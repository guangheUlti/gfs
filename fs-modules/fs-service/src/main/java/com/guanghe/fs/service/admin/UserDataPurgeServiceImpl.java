package com.guanghe.fs.service.admin;

import com.guanghe.fs.file.service.FileCollectionService;
import com.guanghe.fs.file.service.FileInfoService;
import com.guanghe.fs.file.service.FileRecycleService;
import com.guanghe.fs.file.service.FileShareService;
import com.guanghe.fs.file.service.FileTransferTaskService;
import com.guanghe.fs.file.service.FileUserFavoritesService;
import com.guanghe.fs.service.oss.OssAccessKeyService;
import com.guanghe.fs.system.domain.vo.UserDataSummaryVO;
import com.guanghe.fs.system.service.SysUserTransferSettingService;
import com.guanghe.fs.system.service.UserDataPurgeService;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.guanghe.fs.file.domain.table.FileCollectionTableDef.FILE_COLLECTION;
import static com.guanghe.fs.file.domain.table.FileInfoTableDef.FILE_INFO;
import static com.guanghe.fs.file.domain.table.FileShareTableDef.FILE_SHARE;
import static com.guanghe.fs.file.domain.table.FileTransferTaskTableDef.FILE_TRANSFER_TASK;
import static com.guanghe.fs.file.domain.table.FileUserFavoritesTableDef.FILE_USER_FAVORITES;

/**
 * 账号数据清理实现：聚合文件、分享、收集、传输、OSS 密钥等模块的用户数据。
 * <p>
 * 无法放进 fs-system（会反向依赖文件模块），因此由 fs-service 实现 fs-system 定义的接口。
 *
 * @Author: guangheUlti
 * @Date: 2026/10/29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserDataPurgeServiceImpl implements UserDataPurgeService {

    private final FileInfoService fileInfoService;

    private final FileRecycleService fileRecycleService;

    private final FileShareService fileShareService;

    private final FileCollectionService fileCollectionService;

    private final FileTransferTaskService fileTransferTaskService;

    private final FileUserFavoritesService fileUserFavoritesService;

    private final OssAccessKeyService ossAccessKeyService;

    private final SysUserTransferSettingService userTransferSettingService;

    @Override
    public UserDataSummaryVO summarize(String userId) {
        UserDataSummaryVO summary = new UserDataSummaryVO();
        if (userId == null || userId.isBlank()) {
            return summary;
        }
        summary.setFileCount(fileInfoService.count(
                new QueryWrapper().where(FILE_INFO.USER_ID.eq(userId))));
        summary.setRecycleCount(fileInfoService.count(
                new QueryWrapper().where(FILE_INFO.USER_ID.eq(userId))
                        .and(FILE_INFO.IS_DELETED.eq(true))));
        summary.setFavoriteCount(fileUserFavoritesService.count(
                new QueryWrapper().where(FILE_USER_FAVORITES.USER_ID.eq(userId))));
        summary.setShareCount(fileShareService.count(
                new QueryWrapper().where(FILE_SHARE.USER_ID.eq(userId))));
        summary.setCollectionCount(fileCollectionService.count(
                new QueryWrapper().where(FILE_COLLECTION.USER_ID.eq(userId))));
        summary.setTransferTaskCount(fileTransferTaskService.count(
                new QueryWrapper().where(FILE_TRANSFER_TASK.USER_ID.eq(userId))));
        summary.setOssKeyCount(ossAccessKeyService.countByUser(userId));
        return summary;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void purge(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        // 顺序：先删文件（含物理对象）与收藏，再删分享、收集、任务，最后清配置
        fileRecycleService.purgeUserFiles(userId);
        fileShareService.purgeUserShares(userId);
        fileCollectionService.purgeUserCollections(userId);
        fileTransferTaskService.purgeUserTasks(userId);
        fileUserFavoritesService.removeByUserId(userId);
        ossAccessKeyService.deleteByUser(userId);
        userTransferSettingService.deleteUserTransferSetting(userId);
        log.info("账号数据清理完成: userId={}", userId);
    }
}
