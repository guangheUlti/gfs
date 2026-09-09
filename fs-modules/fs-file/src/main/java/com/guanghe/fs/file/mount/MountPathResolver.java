package com.guanghe.fs.file.mount;

import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.service.FileInfoService;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedList;

import static com.guanghe.fs.file.domain.table.FileInfoTableDef.FILE_INFO;

/**
 * 挂载路径解析器
 * <p>
 * 把 file_info 目录树中的记录还原为相对挂载根的真实相对路径（posix）。
 * 挂载点判定：is_dir=1 且 parent_id IS NULL 且 storage_platform_setting_id=挂载设置ID。
 *
 * @Author: guangheUlti
 * @Date: 2026/09/09
 */
@Slf4j
@Component
public class MountPathResolver {

    /** 拼出的相对键长度上限（与 object_key 列宽 512 对齐并留余量） */
    private static final int MAX_KEY_LENGTH = 500;

    /** 反向依赖 FileInfoService，用 ObjectProvider 延迟获取避免启动期循环依赖 */
    @Autowired
    private ObjectProvider<FileInfoService> fileInfoServiceProvider;

    /**
     * 解析文件/目录记录相对挂载点的真实相对路径
     *
     * @param file          目标记录（可为文件或目录）
     * @param mountSettingId 挂载设置ID
     * @return posix 相对路径，如 a/b/c.ext；目标本身就是挂载点时返回空串
     */
    public String resolveRelativeKey(FileInfo file, String mountSettingId) {
        LinkedList<String> segments = new LinkedList<>();
        FileInfo current = file;
        int depthGuard = 0;
        while (current != null) {
            if (isMountPoint(current, mountSettingId)) {
                String joined = String.join("/", segments);
                if (joined.length() > MAX_KEY_LENGTH) {
                    throw new BusinessException(I18nUtils.getMessage("mount.key.too.long"));
                }
                return joined;
            }
            String name = current.getDisplayName();
            if (StrUtil.isEmpty(name) || name.contains("/") || name.contains("\\")) {
                throw new BusinessException(I18nUtils.getMessage("mount.invalid.name"));
            }
            segments.addFirst(name);
            if (StrUtil.isEmpty(current.getParentId())) {
                // 到根都没遇到挂载点：记录不在挂载子树内
                throw new BusinessException(I18nUtils.getMessage("mount.not.in.scope"));
            }
            current = queryById(current.getParentId());
            if (++depthGuard > 64) {
                // 目录树深度保护，避免脏数据成环导致死循环
                throw new BusinessException(I18nUtils.getMessage("mount.not.in.scope"));
            }
        }
        throw new BusinessException(I18nUtils.getMessage("mount.not.in.scope"));
    }

    /**
     * 从指定父目录记录出发拼出目标显示名的相对路径（上传/建目录前的预占场景）
     */
    public String resolveRelativeKeyForNewChild(FileInfo parentDir, String childName, String mountSettingId) {
        if (!MountManager.isValidNameSegment(childName)) {
            throw new BusinessException(I18nUtils.getMessage("mount.invalid.name"));
        }
        String parentKey = resolveRelativeKey(parentDir, mountSettingId);
        return parentKey.isEmpty() ? childName : parentKey + "/" + childName;
    }

    private boolean isMountPoint(FileInfo file, String mountSettingId) {
        return Boolean.TRUE.equals(file.getIsDir())
                && StrUtil.isEmpty(file.getParentId())
                && mountSettingId != null
                && mountSettingId.equals(file.getStoragePlatformSettingId());
    }

    private FileInfo queryById(String id) {
        return fileInfoServiceProvider.getObject().getOne(
                new QueryWrapper().where(FILE_INFO.ID.eq(id)));
    }
}
