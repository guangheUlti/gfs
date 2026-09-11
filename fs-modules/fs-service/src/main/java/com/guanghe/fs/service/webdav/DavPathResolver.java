package com.guanghe.fs.service.webdav;

import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.domain.qry.FileQry;
import com.guanghe.fs.file.domain.vo.FileVO;
import com.guanghe.fs.file.service.FileInfoService;
import com.guanghe.fs.framework.common.domain.PageResult;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * /dav 路径解析：从用户根目录开始逐段下钻，定位到目标 FileInfo。
 * 目录树中带 / 或 \ 的名称本就被挂载校验禁止，按段切分是安全的。
 */
@Component
@RequiredArgsConstructor
public class DavPathResolver {

    private final FileInfoService fileInfoService;

    /**
     * 解析结果
     *
     * @param parent     目标父目录（根目录时为 null）
     * @param file       目标文件/目录（不存在时为 null）
     * @param name       目标末段名（根路径时为空串）
     * @param existed    目标是否已存在
     */
    public record ResolvedPath(FileInfo parent, FileInfo file, String name, boolean existed) {
    }

    /**
     * 解析 /dav 下的相对路径（不含 /dav 前缀；空串 = 用户根目录）
     */
    public ResolvedPath resolve(String relativePath) {
        String userId = cn.dev33.satoken.stp.StpUtil.getLoginIdAsString();
        String normalized = normalize(relativePath);
        if (normalized.isEmpty()) {
            return new ResolvedPath(null, null, "", false);
        }
        String[] segments = normalized.split("/");
        FileInfo current = null;
        for (int i = 0; i < segments.length; i++) {
            boolean last = i == segments.length - 1;
            String segment = segments[i];
            FileInfo next = findChild(userId, current == null ? null : current.getId(), segment);
            if (next == null) {
                // 目标段不存在：若是最后一段，返回「父目录 + 期望名」供 PUT/MKCOL 等创建语义使用
                if (last) {
                    return new ResolvedPath(current, null, segment, false);
                }
                return new ResolvedPath(current, null, segment, false);
            }
            current = next;
            if (last) {
                return new ResolvedPath(parentOf(current), current, segment, true);
            }
        }
        return new ResolvedPath(null, current, "", current != null);
    }

    /**
     * 列出目录直接子项（不分页语义，取足够大的一页）
     */
    public List<FileVO> listChildren(String dirId) {
        FileQry qry = new FileQry();
        qry.setParentId(dirId);
        qry.setPage(1);
        qry.setPageSize(10000);
        PageResult<FileVO> page = fileInfoService.getList(qry);
        return page == null || page.getData() == null || page.getData().getRecords() == null
                ? new ArrayList<>() : page.getData().getRecords();
    }

    /**
     * 按「父目录 + 名称」精确查找子项（复用 getList 的用户归属过滤；找不到回退直接查库）
     */
    public FileInfo findChild(String userId, String parentId, String displayName) {
        FileQry qry = new FileQry();
        qry.setParentId(StrUtil.emptyToNull(parentId));
        qry.setKeyword(displayName);
        qry.setPage(1);
        qry.setPageSize(100);
        PageResult<FileVO> page = fileInfoService.getList(qry);
        if (page != null && page.getData() != null && page.getData().getRecords() != null) {
            for (FileVO vo : page.getData().getRecords()) {
                if (displayName.equals(vo.getDisplayName())) {
                    return fileInfoService.getById(vo.getId());
                }
            }
        }
        // keyword 走 LIKE，精确匹配失败（如名称含 % _ 等通配符）时按唯一索引直查
        return fileInfoService.getOne(new QueryWrapper()
                .where(com.guanghe.fs.file.domain.table.FileInfoTableDef.FILE_INFO.USER_ID.eq(userId))
                .and(com.guanghe.fs.file.domain.table.FileInfoTableDef.FILE_INFO.IS_DELETED.eq(false))
                .and(com.guanghe.fs.file.domain.table.FileInfoTableDef.FILE_INFO.PARENT_ID.eq(
                        StrUtil.emptyToNull(parentId)))
                .and(com.guanghe.fs.file.domain.table.FileInfoTableDef.FILE_INFO.DISPLAY_NAME.eq(displayName)));
    }

    /**
     * 规范化相对路径：去首尾 /、拒绝 .. 逃逸
     */
    public static String normalize(String relativePath) {
        if (StrUtil.isBlank(relativePath)) {
            return "";
        }
        String path = relativePath;
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            return "";
        }
        for (String segment : path.split("/")) {
            if ("..".equals(segment) || ".".equals(segment)) {
                throw new IllegalArgumentException("invalid path segment: " + segment);
            }
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("empty path segment");
            }
        }
        return path;
    }

    private FileInfo parentOf(FileInfo file) {
        if (file == null || StrUtil.isEmpty(file.getParentId())) {
            return null;
        }
        return fileInfoService.getById(file.getParentId());
    }
}
