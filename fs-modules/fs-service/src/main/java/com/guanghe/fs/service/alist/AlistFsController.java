package com.guanghe.fs.service.alist;

import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.domain.dto.CopyFileCmd;
import com.guanghe.fs.file.domain.dto.CreateDirectoryCmd;
import com.guanghe.fs.file.domain.dto.MoveFileCmd;
import com.guanghe.fs.file.domain.dto.RenameFileCmd;
import com.guanghe.fs.file.domain.vo.FileVO;
import com.guanghe.fs.file.service.FileInfoService;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.service.manager.ProtocolReadOnlyService;
import com.guanghe.fs.service.service.ServiceSettingService;
import com.guanghe.fs.service.webdav.DavPathResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AList v3 文件接口：{@code POST /api/fs/{op}}（上传另有 PUT /api/fs/put 与 multipart /api/fs/form）。
 * <p>
 * 路径语义与 WebDAV 一致：以用户根目录为 {@code /}，逐段下钻；{@code ..} 与空段被拒绝
 * （{@link DavPathResolver#normalize}）。写操作进回收站（与 Web 端一致，可恢复），
 * 并且全部受「只读模式」约束：过滤器先拦一道，这里再兜一次（开关是内存快照，成本可忽略）。
 */
@Slf4j
@RestController
@RequestMapping("/api/fs")
@RequiredArgsConstructor
public class AlistFsController {

    /** 递归搜索上限：防止一次请求把整个网盘扫穿 */
    private static final int SEARCH_LIMIT = 500;

    /** 目录树列举上限 */
    private static final int DIRS_LIMIT = 2000;

    private final FileInfoService fileInfoService;
    private final DavPathResolver pathResolver;
    private final ProtocolReadOnlyService protocolReadOnlyService;
    private final AlistTokenService tokenService;

    // ---------- 读 ----------

    /** 列目录：{path, page, per_page（0=全部）} → {content:[], total} */
    @PostMapping("/list")
    public void list(@RequestBody(required = false) String body,
                     HttpServletRequest request, HttpServletResponse response) throws IOException {
        Dict form = AlistAuthController.parseBody(body);
        String rel = resolveRelative(strOf(form, "path", "/"));
        if (rel == null) {
            AlistResponse.error(response, AlistResponse.BAD_REQUEST, "invalid path");
            return;
        }
        DavPathResolver.ResolvedPath resolved = resolveQuietly(rel);
        if (missing(rel, resolved)) {
            AlistResponse.error(response, AlistResponse.NOT_FOUND, "object not found");
            return;
        }
        if (resolved.file() != null && !Boolean.TRUE.equals(resolved.file().getIsDir())) {
            AlistResponse.error(response, AlistResponse.ERROR, "not a folder");
            return;
        }
        String dirId = resolved.file() == null ? null : resolved.file().getId();
        List<FileVO> children = pathResolver.listChildren(dirId);

        List<Map<String, Object>> content = new ArrayList<>(children.size());
        for (FileVO vo : children) {
            content.add(AlistFiles.item(vo));
        }
        int page = Math.max(intOf(form, "page", 1), 1);
        int perPage = intOf(form, "per_page", 0);
        List<Map<String, Object>> paged = content;
        if (perPage > 0) {
            int from = Math.min((page - 1) * perPage, content.size());
            int to = Math.min(from + perPage, content.size());
            paged = content.subList(from, to);
        }
        AlistResponse.ok(response, listEnvelope(paged, content.size(), page, perPage));
    }

    /** 取单个对象（含播放直链） */
    @PostMapping("/get")
    public void get(@RequestBody(required = false) String body,
                    HttpServletRequest request, HttpServletResponse response) throws IOException {
        Dict form = AlistAuthController.parseBody(body);
        String rel = resolveRelative(strOf(form, "path", "/"));
        if (rel == null || rel.isEmpty()) {
            AlistResponse.error(response, AlistResponse.NOT_FOUND, "object not found");
            return;
        }
        DavPathResolver.ResolvedPath resolved = resolveQuietly(rel);
        if (resolved == null || resolved.file() == null) {
            AlistResponse.error(response, AlistResponse.NOT_FOUND, "object not found");
            return;
        }
        FileInfo file = resolved.file();
        if (Boolean.TRUE.equals(file.getIsDir())) {
            AlistResponse.error(response, AlistResponse.ERROR, "object is a folder");
            return;
        }
        Map<String, Object> data = AlistFiles.item(file);
        String alistPath = AlistFiles.toAlistPath(rel);
        data.put("raw_url", baseUrl(request) + "/d/" + encodePath(rel) + "?sign="
                + tokenService.signPath(cn.dev33.satoken.stp.StpUtil.getLoginIdAsString(), alistPath));
        data.put("readme", "");
        data.put("header", "");
        data.put("provider", "Local");
        // AList v3 的 get 响应带 related（同目录其它文件），客户端可能据此做「下一集」；无此概念时给 null
        data.put("related", null);
        AlistResponse.ok(response, data);
    }

    /** 子目录树：{path} → [{name, path}] */
    @PostMapping("/dirs")
    public void dirs(@RequestBody(required = false) String body,
                     HttpServletRequest request, HttpServletResponse response) throws IOException {
        Dict form = AlistAuthController.parseBody(body);
        String rel = resolveRelative(strOf(form, "path", "/"));
        if (rel == null) {
            AlistResponse.error(response, AlistResponse.BAD_REQUEST, "invalid path");
            return;
        }
        DavPathResolver.ResolvedPath resolved = resolveQuietly(rel);
        if (missing(rel, resolved)
                || (resolved.file() != null && !Boolean.TRUE.equals(resolved.file().getIsDir()))) {
            AlistResponse.error(response, AlistResponse.NOT_FOUND, "object not found");
            return;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        collectDirs(resolved.file() == null ? null : resolved.file().getId(),
                AlistFiles.toAlistPath(rel), result);
        AlistResponse.ok(response, result);
    }

    /** 递归搜索：{parent, keywords, page, per_page} → {content:[], total} */
    @PostMapping("/search")
    public void search(@RequestBody(required = false) String body,
                       HttpServletRequest request, HttpServletResponse response) throws IOException {
        Dict form = AlistAuthController.parseBody(body);
        String keyword = StrUtil.trim(strOf(form, "keywords", ""));
        if (StrUtil.isEmpty(keyword)) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("content", List.of());
            empty.put("total", 0);
            AlistResponse.ok(response, empty);
            return;
        }
        String rel = resolveRelative(strOf(form, "parent", "/"));
        if (rel == null) {
            AlistResponse.error(response, AlistResponse.BAD_REQUEST, "invalid path");
            return;
        }
        DavPathResolver.ResolvedPath resolved = resolveQuietly(rel);
        if (missing(rel, resolved)) {
            AlistResponse.error(response, AlistResponse.NOT_FOUND, "object not found");
            return;
        }
        List<Map<String, Object>> content = new ArrayList<>();
        // scope：0 全部 / 1 仅目录 / 2 仅文件（AList 约定）
        searchRecursive(resolved.file() == null ? null : resolved.file().getId(),
                AlistFiles.toAlistPath(rel), keyword, intOf(form, "scope", 0), content);
        int page = Math.max(intOf(form, "page", 1), 1);
        int perPage = intOf(form, "per_page", 0);
        List<Map<String, Object>> paged = content;
        if (perPage > 0) {
            int from = Math.min((page - 1) * perPage, content.size());
            int to = Math.min(from + perPage, content.size());
            paged = content.subList(from, to);
        }
        AlistResponse.ok(response, listEnvelope(paged, content.size(), page, perPage));
    }

    // ---------- 写 ----------

    /** 建目录：{path:"/a/b"} */
    @PostMapping("/mkdir")
    public void mkdir(@RequestBody(required = false) String body, HttpServletResponse response)
            throws IOException {
        if (readOnly(response)) {
            return;
        }
        Dict form = AlistAuthController.parseBody(body);
        String rel = resolveRelative(strOf(form, "path", ""));
        if (StrUtil.isEmpty(rel)) {
            AlistResponse.error(response, AlistResponse.BAD_REQUEST, "invalid path");
            return;
        }
        FileInfo parent = requireParentDir(rel, response);
        if (parent == PARENT_INVALID) {
            return;
        }
        if (pathResolver.findChild(currentUserId(), parent == null ? null : parent.getId(), lastName(rel)) != null) {
            AlistResponse.error(response, AlistResponse.ERROR, "object already exists");
            return;
        }
        try {
            CreateDirectoryCmd cmd = new CreateDirectoryCmd();
            cmd.setParentId(parent == null ? null : parent.getId());
            cmd.setFolderName(lastName(rel));
            fileInfoService.createDirectory(cmd);
            AlistResponse.ok(response, null);
        } catch (BusinessException e) {
            AlistResponse.error(response, AlistResponse.ERROR, e.getMessage());
        }
    }

    /** 重命名：{path:"/a/old", name:"new"} */
    @PostMapping("/rename")
    public void rename(@RequestBody(required = false) String body, HttpServletResponse response)
            throws IOException {
        if (readOnly(response)) {
            return;
        }
        Dict form = AlistAuthController.parseBody(body);
        String rel = resolveRelative(strOf(form, "path", ""));
        String name = StrUtil.trim(strOf(form, "name", ""));
        if (StrUtil.isEmpty(rel) || StrUtil.isEmpty(name) || name.contains("/") || name.contains("\\")) {
            AlistResponse.error(response, AlistResponse.BAD_REQUEST, "invalid name");
            return;
        }
        DavPathResolver.ResolvedPath resolved = resolveQuietly(rel);
        if (resolved == null || resolved.file() == null) {
            AlistResponse.error(response, AlistResponse.NOT_FOUND, "object not found");
            return;
        }
        try {
            RenameFileCmd cmd = new RenameFileCmd();
            cmd.setDisplayName(name);
            fileInfoService.renameFile(resolved.file().getId(), cmd);
            AlistResponse.ok(response, null);
        } catch (BusinessException e) {
            AlistResponse.error(response, AlistResponse.ERROR, e.getMessage());
        }
    }

    /** 删除：{dir:"/a", names:["x","y"]}（进回收站，可恢复） */
    @PostMapping("/remove")
    public void remove(@RequestBody(required = false) String body, HttpServletResponse response)
            throws IOException {
        if (readOnly(response)) {
            return;
        }
        Dict form = AlistAuthController.parseBody(body);
        String rel = resolveRelative(strOf(form, "dir", "/"));
        if (rel == null) {
            AlistResponse.error(response, AlistResponse.BAD_REQUEST, "invalid path");
            return;
        }
        DavPathResolver.ResolvedPath dir = resolveQuietly(rel);
        if (missing(rel, dir) || (dir.file() != null && !Boolean.TRUE.equals(dir.file().getIsDir()))) {
            AlistResponse.error(response, AlistResponse.NOT_FOUND, "object not found");
            return;
        }
        List<String> ids = new ArrayList<>();
        for (String name : namesOf(form)) {
            FileInfo child = pathResolver.findChild(currentUserId(),
                    dir.file() == null ? null : dir.file().getId(), name);
            if (child != null) {
                ids.add(child.getId());
            }
        }
        if (ids.isEmpty()) {
            AlistResponse.error(response, AlistResponse.NOT_FOUND, "object not found");
            return;
        }
        try {
            fileInfoService.moveFilesToRecycleBin(ids);
            AlistResponse.ok(response, null);
        } catch (BusinessException e) {
            AlistResponse.error(response, AlistResponse.ERROR, e.getMessage());
        }
    }

    /** 移动：{src_dir, dst_dir, names:[]} */
    @PostMapping("/move")
    public void move(@RequestBody(required = false) String body, HttpServletResponse response)
            throws IOException {
        transfer(body, response, false);
    }

    /** 复制：{src_dir, dst_dir, names:[]}（只加引用，不复制物理对象） */
    @PostMapping("/copy")
    public void copy(@RequestBody(required = false) String body, HttpServletResponse response)
            throws IOException {
        transfer(body, response, true);
    }

    /**
     * 上传（裸流）：路径来自 {@code File-Path}/{@code X-File-Path} 头或 {@code ?path=}。
     * AList 官方客户端与 rclone 用这个入口传大文件。
     */
    @RequestMapping(value = "/put", method = {RequestMethod.POST, RequestMethod.PUT})
    public void put(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (readOnly(response)) {
            return;
        }
        String path = StrUtil.blankToDefault(
                StrUtil.blankToDefault(request.getHeader("File-Path"), request.getHeader("X-File-Path")),
                request.getParameter("path"));
        if (StrUtil.isBlank(path)) {
            AlistResponse.error(response, AlistResponse.BAD_REQUEST, "path is required");
            return;
        }
        String rel;
        try {
            rel = DavPathResolver.normalize(AlistAuthFilter.decode(path));
        } catch (IllegalArgumentException e) {
            AlistResponse.error(response, AlistResponse.BAD_REQUEST, "invalid path");
            return;
        }
        upload(rel, request.getInputStream(), contentLength(request), response);
    }

    /**
     * 表单上传：multipart(file + path)。
     * <p>
     * AList 官方文档把 /api/fs/form 定义为 <b>PUT</b>（部分客户端沿用 PUT），
     * 也有客户端发 POST；两种都收，否则 POST 会直接 405。
     */
    @RequestMapping(value = "/form", method = {RequestMethod.POST, RequestMethod.PUT})
    public void form(@RequestParam("file") MultipartFile file,
                     @RequestParam("path") String path,
                     HttpServletResponse response) throws IOException {
        if (readOnly(response)) {
            return;
        }
        if (file == null || file.isEmpty() || StrUtil.isBlank(path)) {
            AlistResponse.error(response, AlistResponse.BAD_REQUEST, "file and path are required");
            return;
        }
        String rel;
        try {
            rel = DavPathResolver.normalize(path);
        } catch (IllegalArgumentException e) {
            AlistResponse.error(response, AlistResponse.BAD_REQUEST, "invalid path");
            return;
        }
        try (InputStream in = file.getInputStream()) {
            upload(rel, in, file.getSize(), response);
        }
    }

    // ---------- 公共流程 ----------

    /** 写入落库：父目录必须已存在（AList 语义不隐式建目录） */
    private void upload(String rel, InputStream in, Long size, HttpServletResponse response)
            throws IOException {
        if (StrUtil.isEmpty(rel)) {
            AlistResponse.error(response, AlistResponse.BAD_REQUEST, "invalid path");
            return;
        }
        FileInfo parent = requireParentDir(rel, response);
        if (parent == PARENT_INVALID) {
            return;
        }
        try {
            fileInfoService.writeFileContent(parent == null ? null : parent.getId(), lastName(rel), in, size);
            AlistResponse.ok(response, null);
        } catch (BusinessException e) {
            AlistResponse.error(response, AlistResponse.ERROR, e.getMessage());
        }
    }

    /** move / copy 共用：解析源目录与目标目录，逐个搬运 */
    private void transfer(String body, HttpServletResponse response, boolean copy) throws IOException {
        if (readOnly(response)) {
            return;
        }
        Dict form = AlistAuthController.parseBody(body);
        String srcRel = resolveRelative(strOf(form, "src_dir", "/"));
        String dstRel = resolveRelative(strOf(form, "dst_dir", "/"));
        if (srcRel == null || dstRel == null) {
            AlistResponse.error(response, AlistResponse.BAD_REQUEST, "invalid path");
            return;
        }
        DavPathResolver.ResolvedPath src = resolveQuietly(srcRel);
        DavPathResolver.ResolvedPath dst = resolveQuietly(dstRel);
        if (missing(srcRel, src) || missing(dstRel, dst)) {
            AlistResponse.error(response, AlistResponse.NOT_FOUND, "object not found");
            return;
        }
        if (dst.file() != null && !Boolean.TRUE.equals(dst.file().getIsDir())) {
            AlistResponse.error(response, AlistResponse.BAD_REQUEST, "target is not a folder");
            return;
        }
        String dstId = dst.file() == null ? null : dst.file().getId();
        List<String> ids = new ArrayList<>();
        for (String name : namesOf(form)) {
            FileInfo child = pathResolver.findChild(currentUserId(),
                    src.file() == null ? null : src.file().getId(), name);
            if (child != null) {
                ids.add(child.getId());
            }
        }
        if (ids.isEmpty()) {
            AlistResponse.error(response, AlistResponse.NOT_FOUND, "object not found");
            return;
        }
        try {
            if (copy) {
                CopyFileCmd cmd = new CopyFileCmd();
                cmd.setFileIds(ids);
                cmd.setDirId(dstId);
                fileInfoService.copyFiles(cmd);
            } else {
                MoveFileCmd cmd = new MoveFileCmd();
                cmd.setFileIds(ids);
                cmd.setDirId(dstId);
                fileInfoService.moveFile(cmd);
            }
            AlistResponse.ok(response, null);
        } catch (BusinessException e) {
            AlistResponse.error(response, AlistResponse.ERROR, e.getMessage());
        }
    }

    // ---------- helpers ----------

    /** 父目录校验失败的哨兵（区分「父目录是根 → null」） */
    private static final FileInfo PARENT_INVALID = new FileInfo();

    /**
     * 解析路径的父目录：必须存在且是目录。
     *
     * @return 父目录（根目录返回 null）；失败已写响应并返回 {@link #PARENT_INVALID}
     */
    private FileInfo requireParentDir(String rel, HttpServletResponse response) throws IOException {
        String parentRel = parentOf(rel);
        DavPathResolver.ResolvedPath parent = resolveQuietly(parentRel);
        if (parent == null) {
            AlistResponse.error(response, AlistResponse.NOT_FOUND, "parent folder not found");
            return PARENT_INVALID;
        }
        if (parent.file() != null && !Boolean.TRUE.equals(parent.file().getIsDir())) {
            AlistResponse.error(response, AlistResponse.BAD_REQUEST, "parent is not a folder");
            return PARENT_INVALID;
        }
        return parent.file();
    }

    /** AList 路径 → 相对路径；非法返回 null */
    private String resolveRelative(String alistPath) {
        try {
            return DavPathResolver.normalize(AlistFiles.toRelative(alistPath));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private DavPathResolver.ResolvedPath resolveQuietly(String rel) {
        try {
            return pathResolver.resolve(rel);
        } catch (IllegalArgumentException | BusinessException e) {
            return null;
        }
    }

    /**
     * 目标是否不存在。
     * <p>
     * {@link DavPathResolver#resolve} 对「根目录」和「缺失的子项」都返回 file=null
     * （根目录是虚拟节点，缺失子项要把期望名交给创建语义），必须结合路径是否为空判断，
     * 否则不存在的路径会被当成根目录处理——列目录时会出现「不存在的目录列出了根目录内容」。
     */
    private static boolean missing(String rel, DavPathResolver.ResolvedPath resolved) {
        return resolved == null || (resolved.file() == null && StrUtil.isNotEmpty(rel));
    }

    private boolean readOnly(HttpServletResponse response) throws IOException {
        if (protocolReadOnlyService.isReadOnly(ServiceSettingService.TYPE_ALIST)) {
            AlistResponse.readOnly(response);
            return true;
        }
        return false;
    }

    private String currentUserId() {
        return cn.dev33.satoken.stp.StpUtil.getLoginIdAsString();
    }

    private void collectDirs(String parentId, String parentPath, List<Map<String, Object>> result) {
        if (result.size() >= DIRS_LIMIT) {
            return;
        }
        for (FileVO vo : pathResolver.listChildren(parentId)) {
            if (!Boolean.TRUE.equals(vo.getIsDir())) {
                continue;
            }
            String path = parentPath + (parentPath.endsWith("/") ? "" : "/") + vo.getDisplayName();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", vo.getDisplayName());
            // AList 官方 dirs 元素只有 name + modified，客户端若不需要 path 会忽略多出来的字段
            item.put("modified", AlistFiles.iso(
                    vo.getUpdateTime() == null ? vo.getUploadTime() : vo.getUpdateTime()));
            item.put("path", path);
            result.add(item);
            if (result.size() >= DIRS_LIMIT) {
                return;
            }
            collectDirs(vo.getId(), path, result);
        }
    }

    private void searchRecursive(String parentId, String parentPath, String keyword, int scope,
                                List<Map<String, Object>> out) {
        if (out.size() >= SEARCH_LIMIT) {
            return;
        }
        String lower = keyword.toLowerCase(Locale.ROOT);
        for (FileVO vo : pathResolver.listChildren(parentId)) {
            boolean isDir = Boolean.TRUE.equals(vo.getIsDir());
            String name = vo.getDisplayName() == null ? "" : vo.getDisplayName();
            boolean scopeMatch = scope != 1 && scope != 2 || (scope == 1 && isDir) || (scope == 2 && !isDir);
            if (scopeMatch && name.toLowerCase(Locale.ROOT).contains(lower)) {
                Map<String, Object> item = AlistFiles.item(vo);
                // 搜索结果带 parent，客户端才能拼出可播放的完整路径
                item.put("parent", parentPath);
                out.add(item);
                if (out.size() >= SEARCH_LIMIT) {
                    return;
                }
            }
            if (isDir) {
                searchRecursive(vo.getId(),
                        parentPath + (parentPath.endsWith("/") ? "" : "/") + name, keyword, scope, out);
            }
        }
    }

    /**
     * 列表类响应的信封（兼容 AList v3.58+ 的分页元数据）。
     * <p>
     * 本服务的语义：{@code per_page<=0} 返回全部（而不是 AList 的默认 200 上限），
     * 即「只会多给、不会少给」——客户端即使不做分页也不会看到被截断的目录；
     * 传入 {@code per_page>0} 时按页切分，并用 {@code has_more/pages_total} 如实告知。
     */
    private Map<String, Object> listEnvelope(List<Map<String, Object>> content, int total,
                                             int page, int perPage) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", content);
        data.put("total", total);
        data.put("filtered_total", total);
        data.put("page", page);
        data.put("per_page", perPage > 0 ? perPage : total);
        data.put("has_more", perPage > 0 && (long) page * perPage < total);
        data.put("pages_total", perPage > 0 ? (total + perPage - 1) / perPage : 1);
        data.put("readme", "");
        data.put("header", "");
        data.put("provider", "Local");
        // write：当前是否可写（客户端据此决定是否展示上传/删除入口）
        data.put("write", !protocolReadOnlyService.isReadOnly(ServiceSettingService.TYPE_ALIST));
        return data;
    }

    private static String parentOf(String rel) {
        int idx = rel.lastIndexOf('/');
        return idx < 0 ? "" : rel.substring(0, idx);
    }

    private static String lastName(String rel) {
        int idx = rel.lastIndexOf('/');
        return idx < 0 ? rel : rel.substring(idx + 1);
    }

    private static Long contentLength(HttpServletRequest request) {
        String v = request.getHeader("Content-Length");
        if (StrUtil.isBlank(v)) {
            return null;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> namesOf(Dict form) {
        Object raw = form.get("names");
        List<String> names = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    names.add(String.valueOf(item));
                }
            }
        } else if (raw instanceof String text && StrUtil.isNotBlank(text)) {
            names.add(text);
        }
        return names;
    }

    private static String strOf(Dict form, String key, String def) {
        Object v = form.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static int intOf(Dict form, String key, int def) {
        Object v = form.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 直链用：逐段 URL 编码，空格转 %20（避免 + 被解码成空格导致路径不匹配） */
    static String encodePath(String rel) {
        StringBuilder sb = new StringBuilder();
        for (String segment : rel.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    /** 反代场景优先用 X-Forwarded-*：直链会被客户端直接请求，端口写错就放不出来 */
    static String baseUrl(HttpServletRequest request) {
        String proto = StrUtil.blankToDefault(request.getHeader("X-Forwarded-Proto"), request.getScheme());
        String host = request.getHeader("X-Forwarded-Host");
        String authority;
        if (StrUtil.isNotBlank(host)) {
            authority = host.trim();
        } else {
            int port = request.getServerPort();
            boolean defaultPort = ("http".equals(proto) && port == 80) || ("https".equals(proto) && port == 443);
            authority = request.getServerName() + (defaultPort ? "" : ":" + port);
        }
        return proto + "://" + authority;
    }
}
