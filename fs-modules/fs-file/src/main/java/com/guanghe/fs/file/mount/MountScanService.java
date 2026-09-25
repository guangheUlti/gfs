package com.guanghe.fs.file.mount;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.service.FileInfoService;
import com.guanghe.fs.framework.common.utils.FileUtils;
import com.guanghe.fs.storage.domain.StorageSetting;
import com.guanghe.fs.storage.facade.StorageServiceFacade;
import com.guanghe.fs.storage.plugin.core.IStorageOperationService;
import com.guanghe.fs.storage.plugin.core.model.StorageObjectEntry;
import com.guanghe.fs.storage.service.StorageSettingService;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static com.guanghe.fs.file.domain.table.FileInfoTableDef.FILE_INFO;

/**
 * 挂载同步器
 * <p>
 * 把真实文件系统的变化同步回 file_info：
 * - 外部新增：导入目录/文件记录（object_key=真实相对路径，md5=NULL）；
 * - 外部修改：size/mtime 不一致才更新（md5 保持 NULL）；
 * - 外部删除：DB 记录硬删（不入回收站——内容已没了，进回收站会给用户"可恢复"错觉）。
 * <p>
 * 安全红线：根目录列举失败即放弃本轮（绝不大面积误删）；删除段异常时保留已收集增改、放弃删除；
 * 保险丝限制单轮最大条目数；深度超过 32 停止下钻。全流程持 MountLocks，与写穿透互斥。
 *
 * @Author: guangheUlti
 * @Date: 2026/09/09
 */
@Slf4j
@Component
public class MountScanService {

    private static final int MAX_DEPTH = 32;

    /** 手动/启用触发扫描的异步执行线程池：单线程串行化所有扫描，避免 HTTP 线程被大目录扫描长时间占用导致请求超时 */
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mount-scan-async");
        t.setDaemon(true);
        return t;
    });

    private final StorageSettingService storageSettingService;
    private final StorageServiceFacade storageServiceFacade;
    private final FileInfoService fileInfoService;
    private final MountManager mountManager;
    private final MountPathResolver mountPathResolver;
    private final MountPointService mountPointService;

    @Value("${fs.file.mount.scan-enabled:true}")
    private boolean scanEnabled;

    @Value("${fs.file.mount.scan-max-entries:50000}")
    private int scanMaxEntries;

    @Autowired
    public MountScanService(StorageSettingService storageSettingService,
                            StorageServiceFacade storageServiceFacade,
                            FileInfoService fileInfoService,
                            MountManager mountManager,
                            MountPathResolver mountPathResolver,
                            MountPointService mountPointService) {
        this.storageSettingService = storageSettingService;
        this.storageServiceFacade = storageServiceFacade;
        this.fileInfoService = fileInfoService;
        this.mountManager = mountManager;
        this.mountPathResolver = mountPathResolver;
        this.mountPointService = mountPointService;
    }

    /** 定时扫描（默认 5 分钟，可被 fs.file.mount.scan-interval 覆盖）；与手动触发共用同一串行线程池 */
    @Scheduled(fixedDelayString = "${fs.file.mount.scan-interval:300000}", initialDelay = 60000)
    public void scheduledScan() {
        scanExecutor.execute(this::scanAll);
    }

    /** 启动后先跑一次（对齐 cleanupFolderDownloadTasksOnStartup 模式），异步避免阻塞就绪流程 */
    @EventListener(ApplicationReadyEvent.class)
    public void scanOnStartup() {
        scanExecutor.execute(this::scanAll);
    }

    /** 扫描所有启用的挂载式设置（能力位驱动，覆盖 LocalMount/SMB 等一切 isMountMode 平台） */
    public void scanAll() {
        if (!scanEnabled) {
            return;
        }
        List<StorageSetting> settings = storageSettingService.listEnabledSettings();
        for (StorageSetting setting : settings) {
            try {
                IStorageOperationService instance = storageServiceFacade.getStorageService(setting.getId());
                if (!instance.isMountMode()) {
                    continue;
                }
                scanSetting(setting);
            } catch (Exception e) {
                log.error("挂载扫描失败: settingId={}", setting.getId(), e);
            }
        }
    }    /**
     * 异步触发单个挂载设置扫描（手动接口/启用配置入口）：立即返回，避免大目录扫描占住 HTTP 线程导致请求超时；
     * 完成后挂载点与索引均已可见。userId 为触发时的登录用户（用于挂载点懒创建），可空。
     */
    public void scanSettingAsync(String settingId, String userId) {
        scanExecutor.execute(() -> {
            try {
                StorageSetting setting = storageSettingService.getById(settingId);
                if (setting == null) {
                    log.warn("挂载设置不存在，跳过扫描: settingId={}", settingId);
                    return;
                }
                scanSetting(setting, userId);
            } catch (Exception e) {
                log.error("异步扫描失败: settingId={}", settingId, e);
            }
        });
    }

    /**
     * 扫描单个挂载设置（定时扫描入口；挂载点缺失时跳过本轮，等待文件页浏览时懒创建）
     */
    public void scanSetting(StorageSetting setting) {
        scanSetting(setting, null);
    }

    /**
     * 扫描单个挂载设置；userId 非空时先懒创建挂载点（手动/启用触发时允许尚未浏览过文件页）
     */
    private void scanSetting(StorageSetting setting, String userId) {
        mountManager.locks().callWithLock(setting.getId(), () -> {
            IStorageOperationService instance;
            try {
                instance = storageServiceFacade.getStorageService(setting.getId());
            } catch (Exception e) {
                log.warn("挂载实例不可用，跳过本轮扫描: settingId={}", setting.getId(), e.getMessage());
                return;
            }
            if (!instance.isMountMode()) {
                return;
            }
            if (userId != null) {
                try {
                    Map<String, Object> settingMap = new HashMap<>();
                    settingMap.put("id", setting.getId());
                    settingMap.put("configData", setting.getConfigData());
                    mountPointService.ensureMountPoint(userId, settingMap);
                } catch (Exception e) {
                    log.warn("扫描前懒创建挂载点失败，继续尝试扫描: settingId={}", setting.getId(), e);
                }
            }
            // 1. 根目录列举失败 → 本轮放弃（不删任何 DB 数据）
            List<StorageObjectEntry> rootEntries;
            try {
                rootEntries = instance.listObjects("");
            } catch (Exception e) {
                log.warn("挂载根目录列举失败，放弃本轮扫描: settingId={}, error={}", setting.getId(), e.getMessage());
                return;
            }

            // 2. 载 DB 侧记录（挂载点除外）并计算相对路径
            List<FileInfo> dbRecords = fileInfoService.list(new QueryWrapper()
                    .where(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.eq(setting.getId()))
                    .and(FILE_INFO.IS_DELETED.eq(false)));
            Map<String, FileInfo> dbByPath = new HashMap<>();
            FileInfo mountPoint = null;
            for (FileInfo record : dbRecords) {
                if (Boolean.TRUE.equals(record.getIsDir()) && StrUtil.isEmpty(record.getParentId())) {
                    mountPoint = record; // 挂载点
                    continue;
                }
                try {
                    String path = mountPathResolver.resolveRelativeKey(record, setting.getId());
                    if (!path.isEmpty()) {
                        dbByPath.put(path, record);
                    }
                } catch (Exception e) {
                    // 解析失败的记录不参与本轮比对，也不删除（宁多留勿误删）
                    log.warn("挂载记录无法解析路径，本轮跳过: fileId={}, error={}", record.getId(), e.getMessage());
                }
            }
            if (mountPoint == null) {
                log.warn("挂载点记录不存在，跳过本轮扫描（等待懒创建）: settingId={}", setting.getId());
                return;
            }

            // 3. 软删占用集：回收站中同 setting 的记录路径，外部新建路径命中则跳过导入
            Set<String> softDeletedPaths = fileInfoService.list(new QueryWrapper()
                            .where(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.eq(setting.getId()))
                            .and(FILE_INFO.IS_DELETED.eq(true)))
                    .stream()
                    .map(f -> {
                        try {
                            return mountPathResolver.resolveRelativeKey(f, setting.getId());
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(StrUtil::isNotEmpty)
                    .collect(Collectors.toSet());

            // 4. DFS 真实树
            ScanContext ctx = new ScanContext();
            try {
                visit(instance, setting.getId(), "", rootEntries, mountPoint, dbByPath, ctx, 0);
            } catch (Exception e) {
                // 删除段异常：保留已收集增/改，放弃本轮删除（宁多留勿误删）
                log.warn("扫描 DFS 异常，放弃本轮删除段: settingId={}", setting.getId(), e);
                applyChanges(ctx, false);
                return;
            }

            // 真实树中已不存在的 DB 记录 → 硬删（外部删除场景，③④规则；子树整体硬删）
            for (Map.Entry<String, FileInfo> e : dbByPath.entrySet()) {
                if (!ctx.seenPaths.contains(e.getKey())) {
                    ctx.deleteIds.add(e.getValue().getId());
                }
            }

            // 5. 批量落库
            applyChanges(ctx, true);
        });
    }

    private void visit(IStorageOperationService instance, String settingId, String dirKey,
                       List<StorageObjectEntry> entries, FileInfo dbDir,
                       Map<String, FileInfo> dbByPath, ScanContext ctx, int depth) {
        if (depth > MAX_DEPTH) {
            log.warn("挂载目录深度超过 {}，停止下钻: settingId={}, dir={}", MAX_DEPTH, settingId, dirKey);
            return;
        }
        // 保险丝：单轮导入+删除超限即中止
        if (ctx.inserts.size() + ctx.deleteIds.size() > scanMaxEntries) {
            throw new IllegalStateException("单轮扫描条目超过上限 " + scanMaxEntries);
        }
        for (StorageObjectEntry entry : entries) {
            String rel = entry.getKey();
            String name = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
            // 真实 FS 是命名权威；仅强制：含 / 或 \ 的名字跳过导入并告警
            if (!MountManager.isValidNameSegment(name)) {
                log.warn("挂载扫描发现非法文件名，跳过导入: {}", rel);
                continue;
            }
            if (ctx.softDeletedPaths.contains(rel)) {
                // ⑥ 与回收站软删记录路径冲突：跳过不导入，旧记录永久删除后下轮再导入
                ctx.seenPaths.add(rel);
                continue;
            }
            FileInfo dbChild = dbByPath.get(rel);
            if (entry.isDir()) {
                if (dbChild != null && Boolean.TRUE.equals(dbChild.getIsDir())) {
                    // 已有同名目录：mtime 变则更新 update_time
                    LocalDateTime remoteMtime = toLocalDateTime(entry.getLastModified());
                    if (remoteMtime != null && !remoteMtime.equals(dbChild.getUpdateTime())) {
                        dbChild.setUpdateTime(remoteMtime);
                        ctx.updates.add(dbChild);
                    }
                } else {
                    if (dbChild != null) {
                        // ⑫ 类型不符（文件->目录）：旧记录按"旧删"处理，下方按新增导入
                        ctx.deleteIds.add(dbChild.getId());
                        dbByPath.remove(rel);
                    }
                    FileInfo dirRecord = buildDirRecord(dbDir, name, settingId,
                            toLocalDateTime(entry.getLastModified()));
                    ctx.inserts.add(dirRecord);
                    dbChild = dirRecord;
                }
                ctx.seenPaths.add(rel);
                // 递归下钻；子目录列举失败时跳过该子树（保 DB 数据，下轮再试）
                List<StorageObjectEntry> childEntries;
                try {
                    childEntries = instance.listObjects(rel);
                } catch (Exception e) {
                    log.warn("子目录列举失败，跳过该子树: settingId={}, dir={}, error={}",
                            settingId, rel, e.getMessage());
                    continue;
                }
                visit(instance, settingId, rel, childEntries, dbChild, dbByPath, ctx, depth + 1);
            } else {
                if (dbChild != null && !Boolean.TRUE.equals(dbChild.getIsDir())) {
                    // 已有同名文件：size/mtime 不一致才更新（md5 保持 NULL）
                    boolean sizeChanged = dbChild.getSize() == null
                            || !dbChild.getSize().equals(entry.getSize());
                    LocalDateTime remoteMtime = toLocalDateTime(entry.getLastModified());
                    boolean mtimeChanged = remoteMtime != null && !remoteMtime.equals(dbChild.getUpdateTime());
                    if (sizeChanged || mtimeChanged) {
                        dbChild.setSize(entry.getSize());
                        if (remoteMtime != null) {
                            dbChild.setUpdateTime(remoteMtime);
                        }
                        ctx.updates.add(dbChild);
                    }
                } else {
                    if (dbChild != null) {
                        // ⑫ 类型不符（目录->文件）：旧记录按"旧删"处理
                        ctx.deleteIds.add(dbChild.getId());
                        dbByPath.remove(rel);
                    }
                    ctx.inserts.add(buildFileRecord(dbDir, name, rel, settingId, entry));
                }
                ctx.seenPaths.add(rel);
            }
        }
    }

    private void applyChanges(ScanContext ctx, boolean applyDeletes) {
        if (!ctx.inserts.isEmpty()) {
            fileInfoService.saveBatch(ctx.inserts);
        }
        if (!ctx.updates.isEmpty()) {
            fileInfoService.updateBatch(ctx.updates);
        }
        if (applyDeletes && !ctx.deleteIds.isEmpty()) {
            fileInfoService.removeByIds(ctx.deleteIds);
        }
        if (!ctx.inserts.isEmpty() || !ctx.updates.isEmpty()
                || (applyDeletes && !ctx.deleteIds.isEmpty())) {
            log.info("挂载扫描落库完成: 新增={}, 更新={}, 硬删={}",
                    ctx.inserts.size(), ctx.updates.size(),
                    applyDeletes ? ctx.deleteIds.size() : 0);
        }
    }

    private FileInfo buildDirRecord(FileInfo parent, String name, String settingId, LocalDateTime mtime) {
        FileInfo dir = new FileInfo();
        dir.setId(IdUtil.fastSimpleUUID());
        dir.setOriginalName(name);
        dir.setDisplayName(name);
        dir.setIsDir(true);
        dir.setParentId(parent.getId());
        dir.setUserId(parent.getUserId());
        dir.setStoragePlatformSettingId(settingId);
        LocalDateTime now = mtime != null ? mtime : LocalDateTime.now();
        dir.setUploadTime(now);
        dir.setUpdateTime(now);
        dir.setIsDeleted(false);
        return dir;
    }

    private FileInfo buildFileRecord(FileInfo parent, String name, String relKey, String settingId,
                                     StorageObjectEntry entry) {
        FileInfo file = new FileInfo();
        file.setId(IdUtil.fastSimpleUUID());
        file.setObjectKey(relKey);
        file.setOriginalName(name);
        file.setDisplayName(name);
        file.setSuffix(FileUtils.extName(name));
        file.setSize(entry.getSize());
        file.setMimeType(mimeOf(name));
        file.setIsDir(false);
        file.setParentId(parent.getId());
        file.setUserId(parent.getUserId());
        file.setStoragePlatformSettingId(settingId);
        LocalDateTime mtime = toLocalDateTime(entry.getLastModified());
        file.setUploadTime(mtime != null ? mtime : LocalDateTime.now());
        file.setUpdateTime(mtime);
        file.setIsDeleted(false);
        return file;
    }

    private LocalDateTime toLocalDateTime(Long epochMillis) {
        if (epochMillis == null || epochMillis <= 0) {
            return null;
        }
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    private String mimeOf(String name) {
        try {
            Object mimeType = cn.hutool.core.io.FileUtil.getMimeType(name);
            return mimeType == null ? null : mimeType.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 单轮扫描上下文 */
    private static class ScanContext {
        List<FileInfo> inserts = new ArrayList<>();
        List<FileInfo> updates = new ArrayList<>();
        List<String> deleteIds = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();
        Set<String> softDeletedPaths = new HashSet<>();
    }
}
