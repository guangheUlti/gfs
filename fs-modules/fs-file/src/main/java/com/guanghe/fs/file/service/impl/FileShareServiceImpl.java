package com.guanghe.fs.file.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.domain.FileShare;
import com.guanghe.fs.file.domain.FileShareItem;
import com.guanghe.fs.file.domain.dto.CreateDirectLinkCmd;
import com.guanghe.fs.file.domain.dto.CreateFileShareAccessRecordCmd;
import com.guanghe.fs.file.domain.dto.CreateShareCmd;
import com.guanghe.fs.file.domain.dto.VerifyShareCodeCmd;
import com.guanghe.fs.file.domain.event.CreateFileShareAccessRecordEvent;
import com.guanghe.fs.file.domain.qry.FileShareQry;
import com.guanghe.fs.file.domain.vo.DirectLinkVO;
import com.guanghe.fs.file.domain.vo.FileDownloadVO;
import com.guanghe.fs.file.domain.vo.FileShareThinVO;
import com.guanghe.fs.file.domain.vo.FileShareVO;
import com.guanghe.fs.file.domain.vo.FileVO;
import com.guanghe.fs.file.domain.vo.FolderDownloadTaskVO;
import com.guanghe.fs.file.mapper.FileShareMapper;
import com.guanghe.fs.file.service.FileInfoService;
import com.guanghe.fs.file.service.FileShareItemService;
import com.guanghe.fs.file.service.FileShareService;
import com.guanghe.fs.file.service.FileTransferTaskService;
import com.guanghe.fs.framework.common.domain.PageResult;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.framework.common.utils.Ip2RegionUtils;
import com.guanghe.fs.framework.common.utils.IpUtils;
import com.guanghe.fs.framework.common.utils.StringUtils;
import com.guanghe.fs.storage.facade.StorageServiceFacade;
import com.guanghe.fs.storage.plugin.core.IStorageOperationService;
import io.github.linpeilie.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.guanghe.fs.file.domain.table.FileInfoTableDef.FILE_INFO;
import static com.guanghe.fs.file.domain.table.FileShareItemTableDef.FILE_SHARE_ITEM;
import static com.guanghe.fs.file.domain.table.FileShareTableDef.FILE_SHARE;

/**
 * 文件分享服务实现类
 *
 * @Author: guangheUlti
 * @Date: 2025/10/30 10:02
 */
@Service
@RequiredArgsConstructor
public class FileShareServiceImpl extends ServiceImpl<FileShareMapper, FileShare> implements FileShareService {

    private final FileInfoService fileInfoService;

    private final FileShareItemService fileShareItemService;

    private final FileTransferTaskService fileTransferTaskService;

    private final Converter converter;

    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    private StorageServiceFacade storageServiceFacade;

    private static final String CACHE_NAME = "share";

    @Override
    public PageResult<FileShareVO> getPages(FileShareQry qry) {
        String userId = StpUtil.getLoginIdAsString();
        int page = qry.getPage() == null ? 1 : qry.getPage();
        int pageSize = qry.getPageSize() == null ? 10 : qry.getPageSize();

        Page<FileShare> p = new Page<>(page, pageSize);

        QueryWrapper wrapper = new QueryWrapper();
        wrapper.where(FILE_SHARE.USER_ID.eq(userId));

        if (StringUtils.isNotEmpty(qry.getKeyword())) {
            String keyword = "%" + qry.getKeyword().trim() + "%";
            wrapper.and(FILE_SHARE.SHARE_NAME.like(keyword));
        }
        if (StringUtils.isEmpty(qry.getOrderBy()) || StringUtils.isEmpty(qry.getOrderDirection())) {
            wrapper.orderBy(FILE_SHARE.CREATED_AT.desc());
        } else {
            String orderBy = StrUtil.toUnderlineCase(qry.getOrderBy());
            boolean isAsc = "ASC".equalsIgnoreCase(qry.getOrderDirection());
            wrapper.orderBy(orderBy, isAsc);
        }
        this.page(p, wrapper);
        List<FileShare> fileShares = p.getRecords();
        List<FileShareVO> fileShareVOS = converter.convert(fileShares, FileShareVO.class);

        return PageResult.success(fileShareVOS, p.getTotalRow());
    }

    @Override
//    @Cacheable(value = CACHE_NAME, key = "#shareId", unless = "#result == null", sync = true)
    public FileShareVO getDetail(String shareId) {
        String userId = StpUtil.getLoginIdAsString();
        FileShare share = this.getOne(new QueryWrapper()
                .where(FILE_SHARE.ID.eq(shareId))
                .and(FILE_SHARE.USER_ID.eq(userId)));
        if (share == null) {
            throw new BusinessException(I18nUtils.getMessage("share.not.exist"));
        }
        return buildShareVO(share);
    }

    /**
     * 构建分享VO
     */
    private FileShareVO buildShareVO(FileShare share) {
        FileShareVO vo = converter.convert(share, FileShareVO.class);
        // 是否永久有效
        vo.setIsPermanent(share.getExpireTime() == null);
        // 查询有几个文件
        vo.setFileCount(fileShareItemService.countByShareId(share.getId()));
        // 判断是否到期
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileShareVO createShare(CreateShareCmd cmd) {
        String userId = StpUtil.getLoginIdAsString();
        FileShare share = new FileShare();
        share.setUserId(userId);
        share.setViewCount(0);
        share.setDownloadCount(0);

        List<FileInfo> authorizedFiles = cmd.getFileIds().stream()
                .distinct()
                .map(fileInfoService::getAuthorizedFile)
                .toList();

        if (StrUtil.isNotBlank(cmd.getShareName())) {
            share.setShareName(cmd.getShareName());
        } else {
            FileInfo fileInfo = authorizedFiles.getFirst();
            // 默认取第一个文件名，如果是多个文件则显示第一个文件名+"等{数量}"文件
            if (cmd.getFileIds().size() > 1) {
                if (fileInfo != null) {
                    share.setShareName(fileInfo.getDisplayName() + "等" + cmd.getFileIds().size() + "个文件");
                }
            } else {
                if (fileInfo != null) {
                    share.setShareName(fileInfo.getDisplayName());
                }
            }
        }
        if (cmd.getExpireType() == 4) {
            share.setExpireTime(null);
        } else if (cmd.getExpireType() == 3) {
            share.setExpireTime(cmd.getExpireTime());
        } else {
            share.setExpireTime(calculateExpireTime(cmd.getExpireType()));
        }

        if (cmd.getNeedShareCode()) {
            share.setShareCode(RandomUtil.randomString(4));
        }

        // 分享统一可下载；scope 列非空且无默认值，前端不再传时补默认，历史值原样保留
        share.setScope(StrUtil.blankToDefault(cmd.getScope(), "download"));
        share.setMaxViewCount(cmd.getMaxViewCount());
        share.setMaxDownloadCount(cmd.getMaxDownloadCount());

        this.save(share);

        fileShareItemService.saveShareItems(share.getId(), cmd.getFileIds());

        // 更新被分享文件的访问时间
        updateFileLastAccessTime(cmd.getFileIds());

        return buildShareVO(share);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DirectLinkVO createDirectLink(CreateDirectLinkCmd cmd) {
        String userId = StpUtil.getLoginIdAsString();
        String fileId = cmd.getFileId();

        FileInfo fileInfo = fileInfoService.getOne(new QueryWrapper()
                .where(FILE_INFO.ID.eq(fileId))
                .and(FILE_INFO.USER_ID.eq(userId))
                .and(FILE_INFO.IS_DELETED.eq(false)));
        if (fileInfo == null) {
            throw new BusinessException(I18nUtils.getMessage("file.not.exist", new Object[]{fileId}));
        }
        if (Boolean.TRUE.equals(fileInfo.getIsDir())) {
            throw new BusinessException(I18nUtils.getMessage("file.not.file"));
        }

        // 祖先文件 id 链（含自身），覆盖「文件直接被分享」与「文件位于被分享文件夹内」两种情况
        Set<String> candidateIds = new HashSet<>();
        collectAncestorIds(fileInfo, candidateIds);

        String shareId = findReusableShareId(userId, candidateIds);
        if (shareId == null) {
            // 复用未命中，创建最小分享（无提取码、scope=download、默认7天）
            CreateShareCmd createCmd = new CreateShareCmd();
            createCmd.setFileIds(List.of(fileId));
            createCmd.setShareName(fileInfo.getDisplayName());
            createCmd.setExpireType(cmd.getExpireType() == null ? 1 : cmd.getExpireType());
            createCmd.setScope("download");
            createCmd.setNeedShareCode(false);
            createCmd.setMaxDownloadCount(cmd.getMaxDownloadCount());
            shareId = createShare(createCmd).getId();
        }

        DirectLinkVO vo = new DirectLinkVO();
        vo.setShareId(shareId);
        vo.setFileId(fileId);
        vo.setDirectUrl("/apis/share/" + shareId + "/raw/" + fileId);
        return vo;
    }

    /**
     * 查找可复用的未过期分享：其分享项命中候选文件集合，取最近创建的一条
     */
    private String findReusableShareId(String userId, Set<String> candidateIds) {
        if (CollUtil.isEmpty(candidateIds)) {
            return null;
        }
        List<FileShareItem> matchItems = fileShareItemService.list(new QueryWrapper()
                .where(FILE_SHARE_ITEM.FILE_ID.in(new ArrayList<>(candidateIds))));
        if (CollUtil.isEmpty(matchItems)) {
            return null;
        }
        Set<String> shareIdSet = matchItems.stream()
                .map(FileShareItem::getShareId)
                .collect(Collectors.toSet());
        FileShare reusable = this.getOne(new QueryWrapper()
                .where(FILE_SHARE.ID.in(shareIdSet))
                .and(FILE_SHARE.USER_ID.eq(userId))
                .and(FILE_SHARE.EXPIRE_TIME.isNull().or(FILE_SHARE.EXPIRE_TIME.gt(LocalDateTime.now())))
                .orderBy(FILE_SHARE.CREATED_AT.desc()));
        return reusable == null ? null : reusable.getId();
    }

    /**
     * 沿 parentId 向上收集祖先 id（含自身）
     */
    private void collectAncestorIds(FileInfo fileInfo, Set<String> ids) {
        FileInfo current = fileInfo;
        Set<String> visited = new HashSet<>();
        while (current != null && visited.add(current.getId())) {
            ids.add(current.getId());
            if (StringUtils.isEmpty(current.getParentId())) {
                break;
            }
            current = fileInfoService.getOne(new QueryWrapper()
                    .where(FILE_INFO.ID.eq(current.getParentId()))
                    .and(FILE_INFO.USER_ID.eq(current.getUserId()))
                    .and(FILE_INFO.IS_DELETED.eq(false)));
        }
    }


    /**
     * 更新文件最后访问时间
     *
     * @param fileIds 文件ID列表
     */
    private void updateFileLastAccessTime(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        fileIds.forEach(fileId -> {
            FileInfo fileInfo = new FileInfo();
            fileInfo.setId(fileId);
            fileInfo.setLastAccessTime(now);
            fileInfoService.updateById(fileInfo);
        });
    }

    /**
     * 计算过期时间
     */
    private static LocalDateTime calculateExpireTime(Integer expireType) {
        LocalDateTime now = LocalDateTime.now();
        return switch (expireType) {
            case 1 -> now.plusDays(7);
            case 2 -> now.plusDays(30);
            default -> now.plusDays(7);
        };
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelShares(List<String> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        String userId = StpUtil.getLoginIdAsString();
        List<String> authorizedIds = this.list(new QueryWrapper()
                        .where(FILE_SHARE.ID.in(ids))
                        .and(FILE_SHARE.USER_ID.eq(userId)))
                .stream()
                .map(FileShare::getId)
                .toList();
        if (authorizedIds.isEmpty()) {
            return;
        }
        this.removeByIds(authorizedIds);
        fileShareItemService.remove(new QueryWrapper().in(FileShareItem::getShareId, authorizedIds));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelAllShares() {
        String userId = StpUtil.getLoginIdAsString();
        List<FileShare> shareIds = this.list(new QueryWrapper().where(FILE_SHARE.USER_ID.eq(userId)));
        List<String> shareIdList = shareIds.stream().map(FileShare::getId).toList();
        this.cancelShares(shareIdList);
    }

    @Override
    public boolean verifyShareCode(VerifyShareCodeCmd cmd) {
        // 故意延迟200ms，增加暴力破解成本
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        FileShareVO share = this.getDetail(cmd.getShareId());
        if (share == null) {
            throw new BusinessException(I18nUtils.getMessage("share.not.exist"));
        }

        if (!share.getShareCode().equals(cmd.getShareCode())) {
            throw new BusinessException(I18nUtils.getMessage("share.code.incorrect"));
        }
        return true;
    }

    @Override
    public FileShareThinVO getFileShareThinVO(String shareId) {
        FileShare fileShare = this.getById(shareId);
        if (fileShare == null) {
            throw new BusinessException(I18nUtils.getMessage("share.not.exist"));
        }
        FileShareThinVO vo = converter.convert(fileShare, FileShareThinVO.class);
        vo.setHasCheckCode(StringUtils.isNotEmpty(fileShare.getShareCode()));
        // 查询有几个文件
        vo.setFileCount(fileShareItemService.countByShareId(shareId));
        // 判断是否到期
        LocalDateTime expireTime = fileShare.getExpireTime();
        if (expireTime == null) {
            // 永久有效
            vo.setIsExpire(false);
        } else {
            LocalDateTime now = LocalDateTime.now();
            vo.setIsExpire(now.isAfter(expireTime));
        }
        return vo;
    }

    @Override
    public List<FileVO> getShareFileItems(String shareId, String parentId) {
        FileShare fileShare = getValidShare(shareId);

        List<String> shareFileIds = fileShareItemService.getShareFileIds(shareId);
        List<FileInfo> fileInfos;

        if (StringUtils.isNotEmpty(parentId)) {
            FileInfo parent = getShareAccessibleFile(fileShare, shareFileIds, parentId);
            if (!Boolean.TRUE.equals(parent.getIsDir())) {
                throw new BusinessException(I18nUtils.getMessage("file.not.directory"));
            }

            fileInfos = fileInfoService.list(new QueryWrapper()
                    .where(FILE_INFO.PARENT_ID.eq(parentId))
                    .and(FILE_INFO.USER_ID.eq(fileShare.getUserId()))
                    .and(FILE_INFO.IS_DELETED.eq(false))
                    .orderBy(FILE_INFO.IS_DIR.desc(), FILE_INFO.UPDATE_TIME.desc()));
        } else {
            if (CollUtil.isEmpty(shareFileIds)) {
                fileInfos = List.of();
            } else {
                fileInfos = fileInfoService.list(new QueryWrapper()
                        .where(FILE_INFO.ID.in(shareFileIds))
                        .and(FILE_INFO.USER_ID.eq(fileShare.getUserId()))
                        .and(FILE_INFO.IS_DELETED.eq(false)));
            }
        }

        //记录访问日志
        recordShareAccessLog(shareId);
        //访问计数 + 1
//        incrementViewCount(qry.getShareId());

        return converter.convert(fileInfos, FileVO.class);
    }

    @Override
    public FileDownloadVO downloadFiles(String shareId, String fileId) {
        FileShare fileShare = getValidShare(shareId);
        List<String> shareFileIds = fileShareItemService.getShareFileIds(shareId);
        FileInfo fileInfo = getShareAccessibleFile(fileShare, shareFileIds, fileId);

        IStorageOperationService storageService = storageServiceFacade.getStorageService(fileInfo.getStoragePlatformSettingId());

        if (!storageService.isFileExist(fileInfo.getObjectKey())) {
            throw new BusinessException(I18nUtils.getMessage("file.download.failed.not.exist"));
        }

        InputStream inputStream = storageService.downloadFile(fileInfo.getObjectKey());
        // 将 InputStream 包装成 Resource
        InputStreamResource resource = new InputStreamResource(inputStream);

        FileDownloadVO downloadVO = new FileDownloadVO();
        downloadVO.setFileName(fileInfo.getDisplayName());
        downloadVO.setFileSize(fileInfo.getSize());
        downloadVO.setResource(resource);
        return downloadVO;
    }

    @Override
    public FolderDownloadTaskVO createFolderDownloadTask(String shareId, String folderId) {
        FileShare fileShare = getValidShare(shareId);
        List<String> shareFileIds = fileShareItemService.getShareFileIds(shareId);
        FileInfo folder = getShareAccessibleFile(fileShare, shareFileIds, folderId);
        if (!Boolean.TRUE.equals(folder.getIsDir())) {
            throw new BusinessException(I18nUtils.getMessage("file.not.directory"));
        }
        return fileTransferTaskService.createFolderDownloadTask(folderId);
    }

    @Override
    public FolderDownloadTaskVO getFolderDownloadTask(String shareId, String taskId) {
        FileShare fileShare = getValidShare(shareId);
        FolderDownloadTaskVO task = fileTransferTaskService.getFolderDownloadTask(taskId);
        List<String> shareFileIds = fileShareItemService.getShareFileIds(shareId);
        getShareAccessibleFile(fileShare, shareFileIds, task.getFolderId());
        return task;
    }

    @Override
    public void cancelFolderDownloadTask(String shareId, String taskId) {
        FileShare fileShare = getValidShare(shareId);
        FolderDownloadTaskVO task = fileTransferTaskService.getFolderDownloadTask(taskId);
        List<String> shareFileIds = fileShareItemService.getShareFileIds(shareId);
        getShareAccessibleFile(fileShare, shareFileIds, task.getFolderId());
        fileTransferTaskService.cancelFolderDownloadTask(taskId);
    }

    @Override
    public FileDownloadVO downloadFolderTaskFile(String shareId, String taskId) {
        FileShare fileShare = getValidShare(shareId);
        FolderDownloadTaskVO task = fileTransferTaskService.getFolderDownloadTask(taskId);
        List<String> shareFileIds = fileShareItemService.getShareFileIds(shareId);
        getShareAccessibleFile(fileShare, shareFileIds, task.getFolderId());
        return fileTransferTaskService.downloadFolderTaskFile(taskId);
    }

    private FileShare getValidShare(String shareId) {
        FileShare fileShare = this.getById(shareId);
        if (fileShare == null) {
            throw new BusinessException(I18nUtils.getMessage("share.not.exist.or.deleted"));
        }
        validateShareNotExpired(fileShare);
        return fileShare;
    }

    private FileInfo getShareAccessibleFile(FileShare share, List<String> shareFileIds, String fileId) {
        FileInfo target = fileInfoService.getOne(new QueryWrapper()
                .where(FILE_INFO.ID.eq(fileId))
                .and(FILE_INFO.USER_ID.eq(share.getUserId()))
                .and(FILE_INFO.IS_DELETED.eq(false)));
        FileInfo current = target;
        Set<String> visited = new HashSet<>();

        while (current != null && visited.add(current.getId())) {
            if (shareFileIds.contains(current.getId())) {
                return target;
            }
            if (StringUtils.isEmpty(current.getParentId())) {
                break;
            }
            current = fileInfoService.getOne(new QueryWrapper()
                    .where(FILE_INFO.ID.eq(current.getParentId()))
                    .and(FILE_INFO.USER_ID.eq(share.getUserId()))
                    .and(FILE_INFO.IS_DELETED.eq(false)));
        }

        throw new BusinessException(I18nUtils.getMessage("share.file.not.in.share"));
    }

    private void validateShareNotExpired(FileShare share) {
        if (share.getExpireTime() != null && LocalDateTime.now().isAfter(share.getExpireTime())) {
            throw new BusinessException(I18nUtils.getMessage("share.expired"));
        }
    }

    /**
     * 记录分享访问日志
     *
     * @param shareId 分享ID
     */
    private void recordShareAccessLog(String shareId) {
        String ip = IpUtils.getIpAddr();
        String address = Ip2RegionUtils.search(ip);
        String browser = IpUtils.getBrowser();
        String os = IpUtils.getOs();
        CreateFileShareAccessRecordCmd cmd = new CreateFileShareAccessRecordCmd();
        cmd.setShareId(shareId);
        cmd.setAccessIp(ip);
        cmd.setAccessAddress(address);
        cmd.setBrowser(browser);
        cmd.setOs(os);
        eventPublisher.publishEvent(new CreateFileShareAccessRecordEvent(this, cmd));
    }

    /**
     * 原子递增访问次数
     */
//    private void incrementViewCount(String shareId) {
//        String key = VIEW_COUNT_KEY + shareId;
//        Long count = redisTemplate.opsForValue().increment(key);
//
//        if (count == null) {
//            return;
//        }
//        // 第一次访问时设置过期时间（与分享有效期一致，或者永不过期）
//        if (count == 1) {
//            // 选项1: 永不过期
//            // redisTemplate.persist(key);
//
//            // 选项2: 与分享有效期同步（推荐）
//            setExpireTimeByShareExpiry(shareId, key);
//        }
//    }
}
