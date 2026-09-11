package com.guanghe.fs.service.sftp.nio;

import cn.hutool.core.util.StrUtil;
import cn.dev33.satoken.stp.StpUtil;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.domain.qry.FileQry;
import com.guanghe.fs.file.domain.vo.FileVO;
import com.guanghe.fs.file.service.FileInfoService;
import com.guanghe.fs.framework.common.domain.PageResult;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * GFS 文件树路径解析（WebDAV DavPathResolver 与 SFTP GfsPath 共用的「逐段下钻」逻辑）。
 * 单会话内做逐段下钻缓存，避免同路径反复查询。
 */
@Component
public class GfsPathResolver {

    private final ObjectProvider<FileInfoService> fileInfoServiceProvider;

    public GfsPathResolver(ObjectProvider<FileInfoService> fileInfoServiceProvider) {
        this.fileInfoServiceProvider = fileInfoServiceProvider;
    }

    private FileInfoService files() {
        return fileInfoServiceProvider.getObject();
    }

    /**
     * 解析绝对路径（/a/b/c；/ = 根）为目标记录
     *
     * @return 目标记录；不存在返回 null；根目录返回 null（配合 existed 语义）
     */
    public FileInfo resolve(String absolutePath) {
        // 单参版本供已在 runAs/mock 上下文内的调用方使用；
        // MINA 线程上请走 resolve(path, userId)，否则 StpUtil 会抛上下文未初始化
        String userId = StpUtil.getLoginIdAsString();
        return resolve(absolutePath, userId);
    }

    /**
     * 显式 userId 版本：SFTP 会话内任何线程都安全（不依赖 sa-token 线程上下文）
     */
    public FileInfo resolve(String absolutePath, String userId) {
        String normalized = normalize(absolutePath);
        if (normalized.isEmpty()) {
            return null;
        }
        String[] segments = normalized.split("/");
        FileInfo current = null;
        for (String segment : segments) {
            current = findChild(userId, current == null ? null : current.getId(), segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /**
     * 解析父目录记录（不存在返回 null）
     */
    public FileInfo resolveParent(String absolutePath) {
        String normalized = normalize(absolutePath);
        int idx = normalized.lastIndexOf('/');
        String parentPath = idx <= 0 ? "" : normalized.substring(0, idx);
        if (parentPath.isEmpty()) {
            return null;
        }
        return resolve(parentPath);
    }

    /**
     * 取路径末段名
     */
    public static String baseName(String absolutePath) {
        String normalized = normalize(absolutePath);
        if (normalized.isEmpty()) {
            return "";
        }
        int idx = normalized.lastIndexOf('/');
        return idx < 0 ? normalized : normalized.substring(idx + 1);
    }

    /**
     * 列出目录直接子项
     */
    public List<FileVO> listChildren(String dirId) {
        FileQry qry = new FileQry();
        qry.setParentId(dirId);
        qry.setPage(1);
        qry.setPageSize(10000);
        PageResult<FileVO> page = files().getList(qry);
        return page == null || page.getData() == null || page.getData().getRecords() == null
                ? new ArrayList<>() : page.getData().getRecords();
    }

    /**
     * 按「父目录 + 名称」精确查找子项（先走 getList，通配符名称回退直查）
     */
    public FileInfo findChild(String userId, String parentId, String displayName) {
        FileQry qry = new FileQry();
        qry.setParentId(StrUtil.emptyToNull(parentId));
        qry.setKeyword(displayName);
        qry.setPage(1);
        qry.setPageSize(100);
        PageResult<FileVO> page = files().getList(qry);
        if (page != null && page.getData() != null && page.getData().getRecords() != null) {
            for (FileVO vo : page.getData().getRecords()) {
                if (displayName.equals(vo.getDisplayName())) {
                    return files().getById(vo.getId());
                }
            }
        }
        return files().getOne(new QueryWrapper()
                .where(com.guanghe.fs.file.domain.table.FileInfoTableDef.FILE_INFO.USER_ID.eq(userId))
                .and(com.guanghe.fs.file.domain.table.FileInfoTableDef.FILE_INFO.IS_DELETED.eq(false))
                .and(com.guanghe.fs.file.domain.table.FileInfoTableDef.FILE_INFO.PARENT_ID.eq(
                        StrUtil.emptyToNull(parentId)))
                .and(com.guanghe.fs.file.domain.table.FileInfoTableDef.FILE_INFO.DISPLAY_NAME.eq(displayName)));
    }

    /**
     * 规范化绝对路径：去首尾 /、折叠空段与 "." 段、拒绝 ".." 逃逸
     */
    public static String normalize(String path) {
        if (StrUtil.isBlank(path)) {
            return "";
        }
        String p = path;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.isEmpty()) {
            return "";
        }
        List<String> kept = new ArrayList<>(p.length() / 2 + 2);
        for (String segment : p.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                // 空段（连续 /）与 "." 段直接折叠为上一段：SFTP 客户端连接时第一步
                // realpath(".") 会送字面 "."，WebDAV 客户端也可能送冗余段，不能当非法路径
                continue;
            }
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("invalid path segment: ..");
            }
            kept.add(segment);
        }
        return String.join("/", kept);
    }
}
