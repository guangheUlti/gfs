package com.guanghe.fs.file.mount;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.framework.common.utils.FileUtils;
import com.guanghe.fs.framework.common.utils.I18nUtils;
import com.guanghe.fs.framework.common.utils.JsonUtils;
import com.guanghe.fs.storage.domain.StorageSetting;
import com.guanghe.fs.storage.facade.StorageServiceFacade;
import com.guanghe.fs.storage.plugin.core.IStorageOperationService;
import com.guanghe.fs.storage.plugin.core.model.StorageObjectEntry;
import com.guanghe.fs.storage.service.StorageSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * 无状态远程挂载服务（SFTP / SMB / FTP / WebDAV / 内存 / 本地存储）
 * <p>
 * 文件条目完全以远端服务器为唯一真相源，不写 file_info。前端拿到的 ID 是自解释的虚拟路径 ID：
 * {@code vfs:<settingId>:<d|f>:<payload>}，其中 payload 为 base64url 编码的相对路径（较长时 deflate 压缩）。
 * 所有解析、列目录、命名冲突判断都直接访问远端存储。
 *
 * @Author: guangheUlti
 * @Date: 2026/10/27
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemoteMountService {

    public static final String VIRTUAL_ID_PREFIX = "vfs:";

    /** 虚拟 ID 长度上限，需与 SQL 中 file_share_items.file_id / file_transfer_task.* 列宽 512 对齐 */
    private static final int MAX_ID_LENGTH = 500;

    private static final String DEFAULT_ROOT_NAME = "远程目录";

    private final StorageServiceFacade storageServiceFacade;

    private final StorageSettingService storageSettingService;

    /** 是否为无状态远程配置（能力位判断，勿比较 identifier 字符串） */
    public boolean isStatelessSetting(String settingId) {
        if (StrUtil.isBlank(settingId)) {
            return false;
        }
        try {
            return storageServiceFacade.getStorageService(settingId).isStatelessRemote();
        } catch (Exception e) {
            log.warn("判断无状态远程存储失败，按普通存储处理: settingId={}", settingId, e);
            return false;
        }
    }

    public boolean isVirtualId(String fileId) {
        return fileId != null && fileId.startsWith(VIRTUAL_ID_PREFIX);
    }

    public boolean isVirtualFile(FileInfo file) {
        return file != null && isVirtualId(file.getId());
    }

    /** 相对路径解析结果 */
    public record VirtualPath(String settingId, boolean isDir, String path) {
    }

    /**
     * 把远端相对路径编码为虚拟 ID
     */
    public String buildId(String settingId, boolean isDir, String path) {
        if (StrUtil.isBlank(settingId)) {
            throw new BusinessException(I18nUtils.getMessage("mount.not.in.scope"));
        }
        String normalized = normalizePath(path);
        String id = VIRTUAL_ID_PREFIX + settingId + (isDir ? ":d:" : ":f:") + encodePayload(normalized);
        if (id.length() > MAX_ID_LENGTH) {
            throw new BusinessException(I18nUtils.getMessage("mount.key.too.long"));
        }
        return id;
    }

    public String rootId(String settingId) {
        return buildId(settingId, true, "");
    }

    public VirtualPath parse(String virtualId) {
        if (!isVirtualId(virtualId)) {
            throw new BusinessException(I18nUtils.getMessage("file.not.found"));
        }
        int typeIndex = virtualId.indexOf(':', VIRTUAL_ID_PREFIX.length());
        if (typeIndex <= VIRTUAL_ID_PREFIX.length() || typeIndex + 3 > virtualId.length()) {
            throw new BusinessException(I18nUtils.getMessage("file.not.found"));
        }
        String settingId = virtualId.substring(VIRTUAL_ID_PREFIX.length(), typeIndex);
        String type = virtualId.substring(typeIndex + 1, typeIndex + 2);
        if (!":".equals(virtualId.substring(typeIndex + 2, typeIndex + 3))) {
            throw new BusinessException(I18nUtils.getMessage("file.not.found"));
        }
        if (!"d".equals(type) && !"f".equals(type)) {
            throw new BusinessException(I18nUtils.getMessage("file.not.found"));
        }
        String path = normalizePath(decodePayload(virtualId.substring(typeIndex + 3)));
        if (path.isEmpty() && "f".equals(type)) {
            throw new BusinessException(I18nUtils.getMessage("file.not.found"));
        }
        return new VirtualPath(settingId, "d".equals(type), path);
    }

    /** 校验并规范化相对路径：posix 分隔、无前导/末尾斜杠、拒绝 . 与 .. */
    public String normalizePath(String path) {
        if (StrUtil.isBlank(path)) {
            return "";
        }
        String value = path.trim();
        if (value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0) {
            throw new BusinessException(I18nUtils.getMessage("mount.invalid.name"));
        }
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) {
            return "";
        }
        List<String> segments = new ArrayList<>();
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new BusinessException(I18nUtils.getMessage("mount.invalid.name"));
            }
            segments.add(segment);
        }
        return String.join("/", segments);
    }

    public String parentPath(String path) {
        String normalized = normalizePath(path);
        int idx = normalized.lastIndexOf('/');
        return idx <= 0 ? "" : normalized.substring(0, idx);
    }

    public String nameOf(String path) {
        String normalized = normalizePath(path);
        int idx = normalized.lastIndexOf('/');
        return idx < 0 ? normalized : normalized.substring(idx + 1);
    }

    public String childPath(String parentPath, String childName) {
        if (!MountManager.isValidNameSegment(childName)
                || ".".equals(childName) || "..".equals(childName)) {
            throw new BusinessException(I18nUtils.getMessage("mount.invalid.name"));
        }
        String parent = normalizePath(parentPath);
        return parent.isEmpty() ? childName : parent + "/" + childName;
    }

    /** 存储设置对应的挂载根显示名（面包屑/根目录详情用） */
    public String rootDisplayName(String settingId) {
        try {
            StorageSetting setting = storageSettingService.getById(settingId);
            if (setting != null) {
                String name = readConfigName(setting);
                if (StrUtil.isNotBlank(name)) {
                    return name;
                }
                if (StrUtil.isNotBlank(setting.getRemark())) {
                    return setting.getRemark().trim();
                }
            }
        } catch (Exception e) {
            log.warn("读取远程挂载名称失败: settingId={}", settingId, e);
        }
        return DEFAULT_ROOT_NAME;
    }

    private String readConfigName(StorageSetting setting) {
        if (StrUtil.isBlank(setting.getConfigData())) {
            return null;
        }
        try {
            Map<String, Object> config = JsonUtils.parseObject(setting.getConfigData(),
                    new TypeReference<Map<String, Object>>() {
                    });
            if (config == null) {
                return null;
            }
            Object mountName = config.get("mountName");
            if (isValidName(mountName)) {
                return String.valueOf(mountName).trim();
            }
            Object share = config.get("smbShare");
            if (isValidName(share)) {
                return String.valueOf(share).trim();
            }
            Object root = config.get("sftpRootPath");
            if (root != null) {
                String path = String.valueOf(root).trim().replace('\\', '/');
                while (path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                String base = path.substring(path.lastIndexOf('/') + 1);
                if (isValidName(base)) {
                    return base;
                }
                if (path.isEmpty()) {
                    return "SFTP 根目录";
                }
            }
        } catch (Exception e) {
            log.warn("解析远程挂载配置失败: settingId={}", setting.getId(), e);
        }
        return null;
    }

    private boolean isValidName(Object value) {
        return value != null && MountManager.isValidNameSegment(String.valueOf(value).trim());
    }

    public FileInfo rootFile(String settingId) {
        String userId = currentUserId();
        FileInfo root = new FileInfo();
        root.setId(rootId(settingId));
        root.setObjectKey("");
        root.setOriginalName(rootDisplayName(settingId));
        root.setDisplayName(root.getOriginalName());
        root.setIsDir(true);
        root.setParentId(null);
        root.setUserId(userId);
        root.setStoragePlatformSettingId(settingId);
        root.setIsDeleted(false);
        return root;
    }

    /** 列出远端一层目录的条目（按目录在前、名称自然排序） */
    public List<StorageObjectEntry> list(String settingId, String dirPath) {
        IStorageOperationService storage = storageServiceFacade.getStorageService(settingId);
        List<StorageObjectEntry> entries = storage.listObjects(normalizePath(dirPath));
        List<StorageObjectEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator
                .comparing(StorageObjectEntry::isDir).reversed()
                .thenComparing(entry -> nameOf(entry.getKey()), String.CASE_INSENSITIVE_ORDER));
        return sorted;
    }

    /** 列出远端目录下的子条目并转换为虚拟 FileInfo */
    public List<FileInfo> listChildren(String settingId, String dirPath) {
        String parent = normalizePath(dirPath);
        String parentId = buildId(settingId, true, parent);
        String userId = currentUserId();
        List<FileInfo> result = new ArrayList<>();
        for (StorageObjectEntry entry : list(settingId, parent)) {
            result.add(fromEntry(settingId, entry, parentId, userId));
        }
        return result;
    }

    /**
     * 解析虚拟 ID：校验远端条目存在并返回带真实大小/时间的 FileInfo。
     * 不校验登录用户（分享页匿名访问也走此方法）。
     */
    public FileInfo resolve(String virtualId) {
        return resolve(virtualId, currentUserId());
    }

    public FileInfo resolve(String virtualId, String userId) {
        VirtualPath virtualPath = parse(virtualId);
        if (virtualPath.path().isEmpty()) {
            FileInfo root = rootFile(virtualPath.settingId());
            root.setUserId(userId);
            return root;
        }
        String parent = parentPath(virtualPath.path());
        for (StorageObjectEntry entry : list(virtualPath.settingId(), parent)) {
            if (entry.getKey().equals(virtualPath.path())) {
                return fromEntry(virtualPath.settingId(), entry, buildId(virtualPath.settingId(), true, parent), userId);
            }
        }
        throw new BusinessException(I18nUtils.getMessage("file.not.found"));
    }

    public FileInfo fromEntry(String settingId, StorageObjectEntry entry, String parentId, String userId) {
        String name = nameOf(entry.getKey());
        FileInfo file = new FileInfo();
        file.setId(buildId(settingId, entry.isDir(), entry.getKey()));
        file.setObjectKey(normalizePath(entry.getKey()));
        file.setOriginalName(name);
        file.setDisplayName(name);
        file.setIsDir(entry.isDir());
        file.setSuffix(entry.isDir() ? null : StrUtil.emptyToNull(StrUtil.nullToEmpty(
                FileUtils.getSuffix(name)).toLowerCase(Locale.ROOT)));
        file.setSize(entry.isDir() ? null : entry.getSize());
        file.setMimeType(entry.isDir() || file.getSuffix() == null ? null
                : MediaTypeFactory.getMediaType(name).map(Object::toString)
                .orElse("application/octet-stream"));
        file.setParentId(parentId);
        file.setUserId(userId);
        file.setStoragePlatformSettingId(settingId);
        LocalDateTime modified = entry.getLastModified() == null ? null
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(entry.getLastModified()), ZoneId.systemDefault());
        file.setUploadTime(modified);
        file.setUpdateTime(modified);
        file.setIsDeleted(false);
        return file;
    }

    /** 构造不会校验远端存在性的目录记录（新建目录后的返回值） */
    public FileInfo buildDir(String settingId, String path, String userId) {
        String normalized = normalizePath(path);
        FileInfo dir = new FileInfo();
        dir.setId(buildId(settingId, true, normalized));
        dir.setObjectKey(normalized);
        dir.setOriginalName(nameOf(normalized));
        dir.setDisplayName(dir.getOriginalName());
        dir.setIsDir(true);
        dir.setParentId(buildId(settingId, true, parentPath(normalized)));
        dir.setUserId(userId);
        dir.setStoragePlatformSettingId(settingId);
        dir.setUploadTime(LocalDateTime.now());
        dir.setUpdateTime(dir.getUploadTime());
        dir.setIsDeleted(false);
        return dir;
    }

    /** 构造不会校验远端存在性的文件记录（新建文本/上传完成后） */
    public FileInfo buildFile(String settingId, String path, Long size, String userId) {
        String normalized = normalizePath(path);
        String name = nameOf(normalized);
        FileInfo file = new FileInfo();
        file.setId(buildId(settingId, false, normalized));
        file.setObjectKey(normalized);
        file.setOriginalName(name);
        file.setDisplayName(name);
        file.setIsDir(false);
        file.setSuffix(StrUtil.emptyToNull(StrUtil.nullToEmpty(
                FileUtils.getSuffix(name)).toLowerCase(Locale.ROOT)));
        file.setSize(size);
        file.setMimeType(file.getSuffix() == null ? null
                : MediaTypeFactory.getMediaType(name).map(Object::toString)
                .orElse("application/octet-stream"));
        file.setParentId(buildId(settingId, true, parentPath(normalized)));
        file.setUserId(userId);
        file.setStoragePlatformSettingId(settingId);
        file.setUploadTime(LocalDateTime.now());
        file.setUpdateTime(file.getUploadTime());
        file.setIsDeleted(false);
        return file;
    }

    /**
     * 远端同目录唯一命名：与本地规则一致（name(1).ext）。
     *
     * @param excludePath 需要排除的自身路径（重命名/移动场景），可为 null
     */
    public String uniqueName(String settingId, String parentPath, String desiredName,
                             boolean isDir, String excludePath) {
        Set<String> usedNames = new LinkedHashSet<>();
        for (StorageObjectEntry entry : list(settingId, parentPath)) {
            if (excludePath != null && entry.getKey().equals(excludePath)) {
                continue;
            }
            usedNames.add(nameOf(entry.getKey()).toLowerCase(Locale.ROOT));
        }
        String nameWithoutExt = desiredName;
        String extension = "";
        if (!isDir && desiredName.contains(".")) {
            int dot = desiredName.lastIndexOf('.');
            nameWithoutExt = desiredName.substring(0, dot);
            extension = desiredName.substring(dot);
        }
        String exact = nameWithoutExt + (isDir ? "" : extension);
        if (!usedNames.contains(exact.toLowerCase(Locale.ROOT))) {
            return exact;
        }
        int suffix = 0;
        while (true) {
            suffix++;
            String candidate = nameWithoutExt + "(" + suffix + ")"
                    + (isDir ? "" : extension);
            if (!usedNames.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
    }

    /** 从记录取远端相对路径（仅虚拟记录） */
    public String relativePath(FileInfo file) {
        if (file == null) {
            throw new BusinessException(I18nUtils.getMessage("file.not.found"));
        }
        String key = file.getObjectKey();
        return normalizePath(key == null ? "" : key);
    }

    public void rename(FileInfo file, String destPath) {
        String settingId = file.getStoragePlatformSettingId();
        IStorageOperationService storage = storageServiceFacade.getStorageService(settingId);
        storage.rename(relativePath(file), normalizePath(destPath));
    }

    public void delete(FileInfo file) {
        IStorageOperationService storage = storageServiceFacade.getStorageService(file.getStoragePlatformSettingId());
        String path = relativePath(file);
        if (Boolean.TRUE.equals(file.getIsDir())) {
            if (!path.isEmpty()) {
                storage.deleteDirectory(path);
            }
        } else {
            storage.deleteFile(path);
        }
    }

    /** 目录本身（目录时）与所有祖先目录的虚拟 ID（含根），用于分享可见性判断 */
    public Set<String> ancestorIds(VirtualPath virtualPath) {
        Set<String> ids = new LinkedHashSet<>();
        String path = virtualPath.path();
        ids.add(rootId(virtualPath.settingId()));
        if (path.isEmpty()) {
            return ids;
        }
        String[] segments = path.split("/");
        int limit = virtualPath.isDir() ? segments.length : segments.length - 1;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            if (current.length() > 0) {
                current.append('/');
            }
            current.append(segments[i]);
            ids.add(buildId(virtualPath.settingId(), true, current.toString()));
        }
        return ids;
    }

    /** 目录（或文件）自身及祖先的虚拟 ID，用于直链复用匹配 */
    public Set<String> selfAndAncestorIds(String virtualId) {
        VirtualPath virtualPath = parse(virtualId);
        Set<String> ids = ancestorIds(virtualPath);
        if (!virtualPath.isDir() && !virtualPath.path().isEmpty()) {
            ids.add(virtualId);
        }
        return ids;
    }

    public String currentUserId() {
        try {
            Object loginId = StpUtil.getLoginIdDefaultNull();
            return loginId == null ? null : String.valueOf(loginId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 目录详情用的直接子项统计（不做全树递归，避免根目录大树上产生风暴） */
    public long[] countChildren(String settingId, String dirPath) {
        long files = 0;
        long folders = 0;
        long size = 0;
        for (StorageObjectEntry entry : list(settingId, dirPath)) {
            if (entry.isDir()) {
                folders++;
            } else {
                files++;
                size += entry.getSize() == null ? 0 : entry.getSize();
            }
        }
        return new long[]{files, folders, size};
    }

    private String encodePayload(String path) {
        byte[] raw = path.getBytes(StandardCharsets.UTF_8);
        String plain = "p" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        if (raw.length < 48) {
            return plain;
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DeflaterOutputStream deflater = new DeflaterOutputStream(buffer)) {
                deflater.write(raw);
            }
            String compressed = "z" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(buffer.toByteArray());
            return compressed.length() < plain.length() ? compressed : plain;
        } catch (Exception e) {
            log.debug("虚拟路径压缩失败，使用明文编码: {}", e.getMessage());
            return plain;
        }
    }

    private String decodePayload(String payload) {
        if (StrUtil.isEmpty(payload)) {
            throw new BusinessException(I18nUtils.getMessage("file.not.found"));
        }
        char marker = payload.charAt(0);
        String body = payload.substring(1);
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(body);
            if (marker == 'z') {
                try (InflaterInputStream inflater = new InflaterInputStream(
                        new java.io.ByteArrayInputStream(bytes))) {
                    bytes = inflater.readAllBytes();
                }
            } else if (marker != 'p') {
                throw new BusinessException(I18nUtils.getMessage("file.not.found"));
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(I18nUtils.getMessage("file.not.found"));
        }
    }
}
