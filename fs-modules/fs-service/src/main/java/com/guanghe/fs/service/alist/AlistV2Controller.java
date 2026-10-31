package com.guanghe.fs.service.alist;

import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.file.domain.vo.FileVO;
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
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AList v2 只读接口：{@code POST /api/public/{path|search}}。
 * <p>
 * 影视仓的「Alist网页」入口按 v2 协议工作（不探测 {@code /api/public/settings}，
 * 直接调 {@code /api/public/path}），与 v3 的 {@code /api/fs/*} 并行提供。
 * 只实现读：v2 的写接口不在兼容范围内，写操作请走 v3 或 Web 端。
 */
@Slf4j
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class AlistV2Controller {

    /** 递归搜索上限：与 v3 保持一致，防止一次请求扫穿整个网盘 */
    private static final int SEARCH_LIMIT = 500;

    private final DavPathResolver pathResolver;
    private final ProtocolReadOnlyService protocolReadOnlyService;
    private final AlistTokenService tokenService;

    /**
     * 列目录：{path, password, page_num, page_size} → {files:[], total, write}
     * <p>
     * path 指向文件时按 AList v2 语义返回该文件的单条记录（影视仓点击文件后会再调一次本接口
     * 取 {@code url} 播放直链），所以这里不能把文件当参数错误拒掉。
     */
    @PostMapping("/path")
    public void path(@RequestBody(required = false) String body,
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
            Map<String, Object> item = v2Item(AlistFiles.item(resolved.file()));
            item.put("url", rawUrl(request, rel));
            AlistResponse.ok(response, envelope(List.of(item), 1));
            return;
        }
        String dirId = resolved.file() == null ? null : resolved.file().getId();
        List<Map<String, Object>> files = new ArrayList<>();
        for (FileVO vo : pathResolver.listChildren(dirId)) {
            files.add(v2Item(AlistFiles.item(vo)));
        }
        int page = Math.max(intOf(form, "page_num", 1), 1);
        int perPage = intOf(form, "page_size", 0);
        List<Map<String, Object>> paged = files;
        if (perPage > 0) {
            int from = Math.min((page - 1) * perPage, files.size());
            int to = Math.min(from + perPage, files.size());
            paged = files.subList(from, to);
        }
        AlistResponse.ok(response, envelope(paged, files.size()));
    }

    /** 搜索：{keyword, path} → {files:[], total, write} */
    @PostMapping("/search")
    public void search(@RequestBody(required = false) String body,
                       HttpServletRequest request, HttpServletResponse response) throws IOException {
        Dict form = AlistAuthController.parseBody(body);
        String keyword = StrUtil.trim(strOf(form, "keyword", ""));
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
        List<Map<String, Object>> files = new ArrayList<>();
        if (StrUtil.isNotEmpty(keyword)) {
            searchRecursive(resolved.file() == null ? null : resolved.file().getId(),
                    AlistFiles.toAlistPath(rel), keyword, files);
        }
        AlistResponse.ok(response, envelope(files, files.size()));
    }

    // ---------- helpers ----------

    /** v2 条目：v3 字段 + 影视仓读取的 updated_at */
    private static Map<String, Object> v2Item(Map<String, Object> item) {
        item.put("updated_at", item.get("modified"));
        return item;
    }

    /** 文件播放直链：与 v3 {@code /api/fs/get} 相同，带上绑定当前登录身份的签名 */
    private String rawUrl(HttpServletRequest request, String rel) {
        String userId = cn.dev33.satoken.stp.StpUtil.getLoginIdAsString();
        return AlistFsController.baseUrl(request) + "/d/" + AlistFsController.encodePath(rel)
                + "?sign=" + tokenService.signPath(userId, AlistFiles.toAlistPath(rel));
    }

    private Map<String, Object> envelope(List<Map<String, Object>> files, int total) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("files", files);
        data.put("total", total);
        data.put("write", !protocolReadOnlyService.isReadOnly(ServiceSettingService.TYPE_ALIST));
        return data;
    }

    private void searchRecursive(String parentId, String parentPath, String keyword,
                                 List<Map<String, Object>> out) {
        if (out.size() >= SEARCH_LIMIT) {
            return;
        }
        String lower = keyword.toLowerCase(Locale.ROOT);
        for (FileVO vo : pathResolver.listChildren(parentId)) {
            boolean isDir = Boolean.TRUE.equals(vo.getIsDir());
            String name = vo.getDisplayName() == null ? "" : vo.getDisplayName();
            if (name.toLowerCase(Locale.ROOT).contains(lower)) {
                Map<String, Object> item = v2Item(AlistFiles.item(vo));
                // 搜索结果带 parent，客户端才能拼出可播放的完整路径
                item.put("parent", parentPath);
                out.add(item);
                if (out.size() >= SEARCH_LIMIT) {
                    return;
                }
            }
            if (isDir) {
                searchRecursive(vo.getId(),
                        parentPath + (parentPath.endsWith("/") ? "" : "/") + name, keyword, out);
            }
        }
    }

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

    private static boolean missing(String rel, DavPathResolver.ResolvedPath resolved) {
        return resolved == null || (resolved.file() == null && StrUtil.isNotEmpty(rel));
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
}
