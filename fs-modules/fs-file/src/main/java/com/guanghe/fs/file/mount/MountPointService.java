package com.guanghe.fs.file.mount;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.service.FileInfoService;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.framework.common.utils.JsonUtils;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.guanghe.fs.file.domain.table.FileInfoTableDef.FILE_INFO;

/**
 * 挂载点记录服务
 * <p>
 * 每个挂载设置在启用它的用户根目录下有一条挂载点记录：
 * is_dir=1, storage_platform_setting_id=设置id, parent_id=NULL, object_key=NULL, display_name=配置项 mountName。
 * 本类负责该记录的幂等懒创建与卸载时的索引清理。
 *
 * @Author: guangheUlti
 * @Date: 2026/09/09
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MountPointService {

    private static final String DEFAULT_MOUNT_NAME = "本地挂载";

    private final FileInfoService fileInfoService;

    /**
     * 幂等懒创建挂载点记录（按 user_id + storage_platform_setting_id + parent_id IS NULL + is_dir=1 判存）
     *
     * @return 挂载点记录
     */
    @Transactional(rollbackFor = Exception.class)
    public FileInfo ensureMountPoint(String userId, Map<String, Object> setting) {
        String settingId = String.valueOf(setting.get("id"));
        FileInfo existing = fileInfoService.getOne(new QueryWrapper()
                .where(FILE_INFO.USER_ID.eq(userId))
                .and(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.eq(settingId))
                .and(FILE_INFO.PARENT_ID.isNull())
                .and(FILE_INFO.IS_DIR.eq(true))
                .and(FILE_INFO.IS_DELETED.eq(false)));
        if (existing != null) {
            // 懒升级：早年逻辑对未配挂载名的设置统一用了通用默认名，配置里若存在更精确的名称（如 SMB 共享名）则就地改正
            String resolved = readMountName(setting);
            if (DEFAULT_MOUNT_NAME.equals(existing.getDisplayName())
                    && !DEFAULT_MOUNT_NAME.equals(resolved)) {
                String finalName = dedupeName(userId, resolved, settingId);
                existing.setDisplayName(finalName);
                existing.setOriginalName(finalName);
                existing.setUpdateTime(LocalDateTime.now());
                fileInfoService.updateById(existing);
                log.info("升级挂载点显示名: userId={}, settingId={}, name={}", userId, settingId, finalName);
            }
            return existing;
        }
        String mountName = readMountName(setting);
        // 与同用户根目录下其它挂载点名去重
        String finalName = dedupeName(userId, mountName, settingId);
        LocalDateTime now = LocalDateTime.now();
        FileInfo mountPoint = new FileInfo();
        mountPoint.setId(IdUtil.fastSimpleUUID());
        mountPoint.setOriginalName(finalName);
        mountPoint.setDisplayName(finalName);
        mountPoint.setIsDir(true);
        mountPoint.setParentId(null);
        mountPoint.setUserId(userId);
        mountPoint.setStoragePlatformSettingId(settingId);
        mountPoint.setUploadTime(now);
        mountPoint.setUpdateTime(now);
        mountPoint.setIsDeleted(false);
        fileInfoService.save(mountPoint);
        log.info("创建挂载点记录: userId={}, settingId={}, name={}", userId, settingId, finalName);
        return mountPoint;
    }

    /** 从配置 JSON 读取挂载显示名：优先显式 mountName，SMB 场景回退为共享名，兜底通用默认名 */
    private String readMountName(Map<String, Object> setting) {
        Object configData = setting.get("configData");
        if (configData instanceof String && StrUtil.isNotEmpty((String) configData)) {
            try {
                Map<String, Object> config = JsonUtils.parseObject((String) configData,
                        new TypeReference<Map<String, Object>>() { });
                if (config != null) {
                    // 1) 显式配置的挂载名（用户自定义）
                    Object name = config.get("mountName");
                    if (name != null && MountManager.isValidNameSegment(String.valueOf(name).trim())) {
                        return String.valueOf(name).trim();
                    }
                    // 2) SMB：未配挂载名时用共享名作为挂载名（动态共享模式下共享名为空，落到默认名）
                    Object share = config.get("smbShare");
                    if (share != null && StrUtil.isNotEmpty(String.valueOf(share).trim())
                            && MountManager.isValidNameSegment(String.valueOf(share).trim())) {
                        return String.valueOf(share).trim();
                    }
                }
            } catch (Exception e) {
                log.warn("解析挂载配置失败，使用默认挂载名: setting={}", setting.get("id"), e);
            }
        }
        return DEFAULT_MOUNT_NAME;
    }

    /**
     * 同用户根目录下挂载点名去重：与其它设置的挂载点或普通目录同名时加 (n)。
     * 本地存储（settingId 为 NULL）的普通根目录不参与去重（不同存储空间互不可见，同平台过滤已隔离）。
     */
    private String dedupeName(String userId, String desiredName, String settingId) {
        List<FileInfo> mountPoints = fileInfoService.list(new QueryWrapper()
                .where(FILE_INFO.USER_ID.eq(userId))
                .and(FILE_INFO.PARENT_ID.isNull())
                .and(FILE_INFO.IS_DIR.eq(true))
                .and(FILE_INFO.IS_DELETED.eq(false))
                .and(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.isNotNull()));
        boolean taken = mountPoints.stream()
                .anyMatch(r -> desiredName.equals(r.getDisplayName())
                        && !settingId.equals(r.getStoragePlatformSettingId()));
        if (!taken) {
            return desiredName;
        }
        int suffix = 1;
        while (true) {
            String candidate = desiredName + "(" + suffix + ")";
            boolean candidateTaken = mountPoints.stream()
                    .anyMatch(r -> candidate.equals(r.getDisplayName())
                            && !settingId.equals(r.getStoragePlatformSettingId()));
            if (!candidateTaken) {
                return candidate;
            }
            suffix++;
        }
    }

    /**
     * 卸载：删除该挂载设置的全部 file_info 索引（含挂载点与回收站记录）。
     * 绝不触碰真实文件（安全红线 8.4-⑧）。挂载记录 md5=NULL 不参与秒传引用，无引用计数副作用。
     * 调用方负责持 MountLocks 并包裹事务。
     */
    public void unmount(String settingId) {
        List<FileInfo> all = fileInfoService.list(new QueryWrapper()
                .where(FILE_INFO.STORAGE_PLATFORM_SETTING_ID.eq(settingId)));
        if (all.isEmpty()) {
            return;
        }
        // 断言：挂载子树记录 md5 恒为 NULL，不应存在对象引用行；如有异常数据先记日志再删
        long md5Count = all.stream().filter(f -> StrUtil.isNotEmpty(f.getContentMd5())).count();
        if (md5Count > 0) {
            log.warn("挂载卸载发现非空 md5 记录（异常数据，仍将硬删索引）: settingId={}, count={}", settingId, md5Count);
        }
        fileInfoService.removeByIds(all.stream().map(FileInfo::getId).collect(Collectors.toList()));
        log.info("挂载卸载完成，索引已清理（真实文件未动）: settingId={}, count={}", settingId, all.size());
    }
}
