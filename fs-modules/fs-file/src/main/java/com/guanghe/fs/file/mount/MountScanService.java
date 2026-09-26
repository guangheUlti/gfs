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
import java.util.concurrent.ConcurrentHashMap;
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

    /** 实时监听服务：扫描完成后对齐监听状态； setter 注入避免与监听服务的回调形成构造期环 */
    private volatile MountWatchService watchService;

    @Autowired(required = false)
    public void setWatchService(MountWatchService watchService) {
        this.watchService = watchService;
    }

    @Value("${fs.file.mount.scan-enabled:true}")
    private boolean scanEnabled;

    @Value("${fs.file.mount.scan-max-entries:50000}")
    private int scanMaxEntries;

    /** 全局兑底扫描间隔毫秒（fs.file.mount.scan-interval，默认 30 分钟）：挂载未配置间隔时使用 */
    @Value("${fs.file.mount.scan-interval:1800000}")
    private long globalScanIntervalMillis;

    /** 上次定时/手动扫描完成时间：settingId → epochMillis，避免「刚扫完 → tick 又到点」重复全扫 */
    private final Map<String, Long> lastScanFinishedAt = new ConcurrentHashMap<>();

    /**
     * 查询上次扫描完成时间（供存储卡片展示）。
     * 仅内存态：重启后由启动兑底全扫重新建立；尚未扫过返回 null。
     */
    public Long getLastScanFinishedAt(String settingId) {
        return lastScanFinishedAt.get(settingId);
    }

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

    /**
     * 每分钟调度 tick：按每个挂载各自配置的 rescanIntervalSeconds 独立定时全扫
     * （旧版全局一个 5 分钟 @Scheduled、用户配置的间隔不生效，已废弃）：
     * 实时监听关闭：到点即扫；实时监听开启：同一间隔作为兑底全扫周期
     * （覆盖 WatchService 异常/事件丢失/网络盘掉线）。fixedDelay 不叠加。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void schedulerTick() {
        if (!scanEnabled) {
            return;
        }
        List<StorageSetting> settings;
        try {
            settings = storageSettingService.listEnabledSettings();
        } catch (Exception e) {
            log.warn("调度 tick 读取启用设置失败: {}", e.getMessage());
            return;
        }
        long now = System.currentTimeMillis();
        for (StorageSetting setting : settings) {
            try {
                IStorageOperationService instance = storageServiceFacade.getStorageService(setting.getId());
                if (!instance.isMountMode() || instance.isDirectAccess()) {
                    // 本地挂载（直读）无后台扫描：浏览时由 reconcileDirectLevel 按层对账
                    continue;
                }
                long intervalMillis = resolveIntervalMillis(instance);
                Long last = lastScanFinishedAt.get(setting.getId());
                if (last == null || now - last >= intervalMillis) {
                    scanSettingAsync(setting.getId(), null);
                }
            } catch (Exception e) {
                // 实例不可用（禁用中/网络盘掉线）等：跳过本轮，下个 tick 再试
                log.debug("调度 tick 跳过挂载: settingId={}, error={}", setting.getId(), e.getMessage());
            }
        }
    }

    /** 解析单个挂载的定时扫描间隔毫秒：用户配置 > 全局兑底（实时监听开启时间隔语义不变，仅作兑底周期） */
    private long resolveIntervalMillis(IStorageOperationService instance) {
        Long configured = instance.rescanIntervalSeconds();
        if (configured != null && configured > 0) {
            return configured * 1000L;
        }
        return globalScanIntervalMillis;
    }

    /**
     * 启动后先跑一次兑底全扫（对齐 cleanupFolderDownloadTasksOnStartup 模式）：
     * 覆盖实时监听不可用/事件丢失场景，同时为各挂载建立扫描基线与实时监听；异步避免阻塞就绪流程。
     * （旧全局 @Scheduled 已被 schedulerTick 按每挂载独立间隔调度取代）
     */
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
                if (!instance.isMountMode() || instance.isDirectAccess()) {
                    continue;
                }
                scanSetting(setting);
                // 启动全扫同样计入「上次扫描完成」：避免启动 90 秒后 tick 对大目录重复全扫一轮
                lastScanFinishedAt.put(setting.getId(), System.currentTimeMillis());
            } catch (Exception e) {
                log.error("挂载扫描失败: settingId={}", setting.getId(), e);
            }
        }
    }

    /**
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
            } finally {
                lastScanFinishedAt.put(settingId, System.currentTimeMillis());
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
            if (instance.isDirectAccess()) {
                // 本地挂载（直读）：无后台扫描，浏览时按层实时对账
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

            // 6. 扫描完成后对齐实时监听（首次扫描/配置变更/启停后自然收敛：期望开启则启动，期望关闭则停止）
            MountWatchService watcher = this.watchService;
            if (watcher != null) {
                try {
                    watcher.alignWatch(setting.getId(), instance);
                } catch (Exception e) {
                    log.warn("对齐实时监听状态失败: settingId={}", setting.getId(), e);
                }
            }
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

    /**
     * 本地挂载（直读）单层实时对账：浏览某目录前列一次真实目录，
     * 以真实文件系统为唯一真相源把该层子行对齐（新增/更新/消失硬删），替代后台扫描。
     * <p>
     * 规则镜像 scan 单层逻辑：命中本设置软删（回收站）路径的条目跳过导入（墓碑永久删除后再导）；
     * 类型不符（文件<->目录）按「旧子树硬删 + 新插」；列举/解析失败保留现有索引（宁多留勿误删）。
     * 行按用户各自对账：共享存储下各用户浏览自己的父行时各自收敛，跨用户/外部改动在下次浏览时对齐。
     * 全程持 MountLocks，与写穿透、同用户并发浏览互斥。
     */
    public void reconcileDirectLevel(String settingId, FileInfo parentRow) {
        if (settingId == null || parentRow == null) {
            return;
        }
        try {
            mountManager.locks().callWithLock(settingId, () -> doReconcileDirectLevel(settingId, parentRow));
        } catch (Exception e) {
            log.warn("本地挂载（直读）对账失败（本次沿用现有索引）: settingId={}, parent={}, error={}",
                    settingId, parentRow.getId(), e.getMessage());
        }
    }

    private void doReconcileDirectLevel(String settingId, FileInfo parentRow) {
        IStorageOperationService instance;
        try {
            instance = storageServiceFacade.getStorageService(settingId);
        } catch (Exception e) {
            log.warn("本地挂载（直读）实例不可用，跳过对账: settingId={}, error={}", settingId, e.getMessage());
            return;
        }
        if (!instance.isDirectAccess()) {
            return;
        }
        String parentKey;
        try {
            parentKey = mountPathResolver.resolveRelativeKey(parentRow, settingId);
        } catch (Exception e) {
            log.warn("本地挂载（直读）父路径解析失败，跳过对账: fileId={}, error={}", parentRow.getId(), e.getMessage());
            return;
        }
        List<StorageObjectEntry> entries;
        try {
            entries = instance.listObjects(parentKey);
        } catch (Exception e) {
            log.warn("本地挂载（直读）列目录失败，本次沿用现有索引: settingId={}, dir={}, error={}",
                    settingId, parentKey, e.getMessage());
            return;
        }

        // 回收站墓碑路径集合：命中条目跳过导入（与 scan ⑥ 规则一致，墓碑永久删除后再导）
        Set<String> softDeletedPaths = fileInfoService.list(new QueryWrapper()
                        .where(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.eq(settingId))
                        .and(FILE_INFO.IS_DELETED.eq(true)))
                .stream()
                .map(f -> {
                    try {
                        return mountPathResolver.resolveRelativeKey(f, settingId);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(StrUtil::isNotEmpty)
                .collect(Collectors.toSet());

        // 该父目录下的现有非删子行（行按用户归属，此处对账的是浏览者自己的行）
        List<FileInfo> children = fileInfoService.list(new QueryWrapper()
                .where(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.eq(settingId))
                .and(FILE_INFO.PARENT_ID.eq(parentRow.getId()))
                .and(FILE_INFO.IS_DELETED.eq(false)));
        Map<String, FileInfo> byLowerName = new HashMap<>();
        for (FileInfo child : children) {
            if (child.getDisplayName() != null) {
                byLowerName.put(child.getDisplayName().toLowerCase(), child);
            }
        }

        List<FileInfo> inserts = new ArrayList<>();
        List<FileInfo> updates = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (StorageObjectEntry entry : entries) {
            String rel = entry.getKey();
            String name = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
            if (!MountManager.isValidNameSegment(name)) {
                log.warn("本地挂载（直读）对账发现非法文件名，跳过: {}", rel);
                continue;
            }
            if (softDeletedPaths.contains(rel)) {
                // 该路径在回收站：占用名字，不导入
                continue;
            }
            String nameKey = name.toLowerCase();
            FileInfo existing = byLowerName.get(nameKey);
            if (existing == null) {
                inserts.add(entry.isDir()
                        ? buildDirRecord(parentRow, name, settingId, toLocalDateTime(entry.getLastModified()))
                        : buildFileRecord(parentRow, name, rel, settingId, entry));
                seen.add(nameKey);
                continue;
            }
            if (Boolean.TRUE.equals(existing.getIsDir()) != entry.isDir()) {
                // 类型不符（与 scan ⑫ 一致）：旧子树硬删，按新类型重插
                hardDeleteSubtree(existing);
                inserts.add(entry.isDir()
                        ? buildDirRecord(parentRow, name, settingId, toLocalDateTime(entry.getLastModified()))
                        : buildFileRecord(parentRow, name, rel, settingId, entry));
                seen.add(nameKey);
                continue;
            }
            boolean dirty = false;
            LocalDateTime remoteMtime = toLocalDateTime(entry.getLastModified());
            if (entry.isDir()) {
                if (remoteMtime != null && !remoteMtime.equals(existing.getUpdateTime())) {
                    existing.setUpdateTime(remoteMtime);
                    dirty = true;
                }
            } else {
                if (existing.getSize() == null
                        ? entry.getSize() != null
                        : !existing.getSize().equals(entry.getSize())) {
                    existing.setSize(entry.getSize());
                    dirty = true;
                }
                if (remoteMtime != null && !remoteMtime.equals(existing.getUpdateTime())) {
                    existing.setUpdateTime(remoteMtime);
                    dirty = true;
                }
                if (!rel.equals(existing.getObjectKey())) {
                    existing.setObjectKey(rel);
                    dirty = true;
                }
            }
            if (!name.equals(existing.getDisplayName())) {
                // 外部改名（含大小写调整）：行名对齐真实 FS
                existing.setDisplayName(name);
                existing.setOriginalName(name);
                if (!entry.isDir()) {
                    existing.setSuffix(FileUtils.extName(name));
                    existing.setMimeType(mimeOf(name));
                }
                dirty = true;
            }
            if (dirty) {
                updates.add(existing);
            }
            seen.add(nameKey);
        }

        // 真实 FS 中已消失的非删子行：连同其非删子树硬删（墓碑行不动，与 scan 删除段一致）
        List<String> vanishIds = new ArrayList<>();
        for (FileInfo child : children) {
            if (child.getDisplayName() != null
                    && !seen.contains(child.getDisplayName().toLowerCase())) {
                hardDeleteSubtreeIds(child, vanishIds);
            }
        }

        if (!inserts.isEmpty()) {
            fileInfoService.saveBatch(inserts);
        }
        if (!updates.isEmpty()) {
            fileInfoService.updateBatch(updates);
        }
        if (!vanishIds.isEmpty()) {
            fileInfoService.removeByIds(vanishIds);
        }
        if (!inserts.isEmpty() || !updates.isEmpty() || !vanishIds.isEmpty()) {
            log.debug("本地挂载（直读）对账完成: dir={}, 新增={}, 更新={}, 硬删={}",
                    parentKey, inserts.size(), updates.size(), vanishIds.size());
        }
    }

    /** 硬删该行及其非删子树（收集到 ids 后由调用方批量删）；文件行无子节点，目录行按 parent 链递归 */
    private void hardDeleteSubtreeIds(FileInfo row, List<String> ids) {
        ids.add(row.getId());
        if (Boolean.TRUE.equals(row.getIsDir())) {
            for (FileInfo child : fileInfoService.list(new QueryWrapper()
                    .where(FILE_INFO.PARENT_ID.eq(row.getId()))
                    .and(FILE_INFO.IS_DELETED.eq(false)))) {
                hardDeleteSubtreeIds(child, ids);
            }
        }
    }

    private void hardDeleteSubtree(FileInfo row) {
        List<String> ids = new ArrayList<>();
        hardDeleteSubtreeIds(row, ids);
        if (!ids.isEmpty()) {
            fileInfoService.removeByIds(ids);
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
