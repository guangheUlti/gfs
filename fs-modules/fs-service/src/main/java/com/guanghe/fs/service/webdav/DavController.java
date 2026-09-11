package com.guanghe.fs.service.webdav;

import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.domain.dto.CopyFileCmd;
import com.guanghe.fs.file.domain.dto.CreateDirectoryCmd;
import com.guanghe.fs.file.domain.dto.MoveFileCmd;
import com.guanghe.fs.file.domain.dto.RenameFileCmd;
import com.guanghe.fs.file.domain.vo.FileVO;
import com.guanghe.fs.file.service.FileInfoService;
import com.guanghe.fs.framework.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 * WebDAV 单控制器：/dav/** 按 HTTP method 分发（不用 @RequestMapping 的 method 属性，
 * Spring 的 RequestMethod 枚举不含 PROPFIND/PROPPATCH/REPORT 等）。
 * <p>
 * 语义约定（§6 行为边界）：
 * - DELETE = 进回收站（与 Web 端一致，可恢复）
 * - PUT 覆盖同名文件；新建走精确重名语义（不自动改名）
 * - LOCK/UNLOCK 不实现（501）；PROPPATCH 返回 207 空 multistatus（Explorer 兼容）
 */
@Slf4j
@RestController
@RequestMapping("/dav")
@RequiredArgsConstructor
public class DavController {

    private static final String ALLOW = "OPTIONS, GET, HEAD, PUT, PROPFIND, MKCOL, DELETE, MOVE, COPY";

    private final FileInfoService fileInfoService;
    private final DavPathResolver pathResolver;

    @RequestMapping("/**")
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String method = request.getMethod().toUpperCase();
        String relativePath = extractRelativePath(request);
        switch (method) {
            case "OPTIONS" -> handleOptions(response);
            case "PROPFIND" -> handlePropfind(request, response, relativePath);
            case "PROPPATCH" -> handleProppatch(response, relativePath);
            case "GET", "HEAD" -> handleGet(request, response, relativePath);
            case "PUT" -> handlePut(request, response, relativePath);
            case "MKCOL" -> handleMkcol(response, relativePath);
            case "DELETE" -> handleDelete(response, relativePath);
            case "MOVE" -> handleMove(response, relativePath, request.getHeader("Destination"), false);
            case "COPY" -> handleMove(response, relativePath, request.getHeader("Destination"), true);
            case "LOCK", "UNLOCK" -> {
                response.setStatus(HttpServletResponse.SC_NOT_IMPLEMENTED);
                response.setContentType("text/plain; charset=UTF-8");
                response.getWriter().write("LOCK/UNLOCK is not supported (documented limitation)");
            }
            default -> {
                response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                response.setHeader("Allow", ALLOW);
            }
        }
    }

    private void handleOptions(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader("Allow", ALLOW);
        response.setHeader("DAV", "1, 2");
        response.setHeader("MS-Author-Via", "DAV");
        response.setContentLength(0);
    }

    /** PROPFIND：Depth 0=自身、1=一层子项；infinity 一律按 1 处理并返回 207 */
    private void handlePropfind(HttpServletRequest request, HttpServletResponse response, String relativePath)
            throws IOException {
        DavPathResolver.ResolvedPath resolved = resolveQuietly(response, relativePath);
        if (resolved == null) {
            return;
        }
        if (resolved.file() == null && !relativePath.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String depth = StrUtil.blankToDefault(request.getHeader("Depth"), "1");
        StringBuilder responses = new StringBuilder();

        if (StrUtil.isEmpty(relativePath)) {
            // 根目录：虚拟节点
            responses.append(PropfindXmlWriter.writeResponse(href(""),
                    "", true, null, LocalDateTime.now(), null));
            if (!"0".equals(depth)) {
                for (FileVO vo : pathResolver.listChildren(null)) {
                    responses.append(PropfindXmlWriter.writeResponse(href(vo.getDisplayName()),
                            vo.getDisplayName(), Boolean.TRUE.equals(vo.getIsDir()),
                            vo.getSize(), vo.getUploadTime(), vo.getMimeType()));
                }
            }
        } else {
            FileInfo file = resolved.file();
            responses.append(PropfindXmlWriter.writeResponse(href(relativePath),
                    file.getDisplayName(), Boolean.TRUE.equals(file.getIsDir()),
                    file.getSize(), file.getUploadTime(), file.getMimeType()));
            if (!"0".equals(depth) && Boolean.TRUE.equals(file.getIsDir())) {
                for (FileVO vo : pathResolver.listChildren(file.getId())) {
                    responses.append(PropfindXmlWriter.writeResponse(href(relativePath + "/" + vo.getDisplayName()),
                            vo.getDisplayName(), Boolean.TRUE.equals(vo.getIsDir()),
                            vo.getSize(), vo.getUploadTime(), vo.getMimeType()));
                }
            }
        }

        byte[] body = PropfindXmlWriter.wrapMultistatus(responses.toString()).getBytes(StandardCharsets.UTF_8);
        response.setStatus(207);
        response.setContentType("application/xml; charset=UTF-8");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private void handleProppatch(HttpServletResponse response, String relativePath) throws IOException {
        byte[] body = PropfindXmlWriter.emptyMultistatus(href(relativePath)).getBytes(StandardCharsets.UTF_8);
        response.setStatus(207);
        response.setContentType("application/xml; charset=UTF-8");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    /** GET/HEAD：流式输出文件内容 */
    private void handleGet(HttpServletRequest request, HttpServletResponse response, String relativePath)
            throws IOException {
        DavPathResolver.ResolvedPath resolved = resolveQuietly(response, relativePath);
        if (resolved == null) {
            return;
        }
        if (resolved.file() == null || Boolean.TRUE.equals(resolved.file().getIsDir())) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        FileInfo file = resolved.file();
        long size = file.getSize() == null ? 0 : file.getSize();
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(StrUtil.blankToDefault(file.getMimeType(), "application/octet-stream"));
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''"
                + URLEncoder.encode(file.getDisplayName(), StandardCharsets.UTF_8).replace("+", "%20"));
        response.setContentLengthLong(size);
        response.setHeader("Last-Modified", PropfindXmlWriter.formatHttpDate(file.getUploadTime()));
        response.setHeader("ETag", "\"" + file.getId() + "-" + (file.getUpdateTime() == null ? 0
                : file.getUpdateTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()) + "\"");
        if ("HEAD".equals(request.getMethod().toUpperCase())) {
            return;
        }
        try (InputStream in = fileInfoService.downloadFile(file.getId());
             OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
            out.flush();
        } catch (Exception e) {
            log.warn("WebDAV 下载失败: path={}", relativePath, e);
            if (!response.isCommitted()) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }

    /** PUT：上传/覆盖（目标同名文件存在 = 覆盖语义） */
    private void handlePut(HttpServletRequest request, HttpServletResponse response, String relativePath)
            throws IOException {
        if (StrUtil.isEmpty(relativePath)) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            return;
        }
        DavPathResolver.ResolvedPath resolved = resolveQuietly(response, relativePath);
        if (resolved == null) {
            return;
        }
        if (resolved.file() != null && Boolean.TRUE.equals(resolved.file().getIsDir())) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            return;
        }
        // 父目录不存在 → 409（WebDAV 语义：PUT 不隐式创建中间目录）
        if (resolved.file() == null && StrUtil.isNotEmpty(resolved.name())
                && resolved.parent() == null && relativePath.contains("/")) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            return;
        }
        try {
            String parentId = resolved.parent() == null ? null : resolved.parent().getId();
            fileInfoService.writeFileContent(parentId, resolved.name(), request.getInputStream(),
                    StrUtil.isBlank(request.getHeader("Content-Length")) ? null
                            : Long.parseLong(request.getHeader("Content-Length")));
            response.setStatus(resolved.existed() ? HttpServletResponse.SC_NO_CONTENT : HttpServletResponse.SC_CREATED);
        } catch (BusinessException e) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            response.setContentType("text/plain; charset=UTF-8");
            response.getWriter().write(e.getMessage());
        } catch (Exception e) {
            log.warn("WebDAV PUT 失败: path={}", relativePath, e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    /** MKCOL：建目录 */
    private void handleMkcol(HttpServletResponse response, String relativePath) throws IOException {
        if (StrUtil.isEmpty(relativePath)) {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        DavPathResolver.ResolvedPath resolved = resolveQuietly(response, relativePath);
        if (resolved == null) {
            return;
        }
        if (resolved.existed()) {
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED); // 405: already exists
            return;
        }
        // 父目录不存在 → 409
        if (resolved.parent() == null && relativePath.contains("/")) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            return;
        }
        try {
            CreateDirectoryCmd cmd = new CreateDirectoryCmd();
            cmd.setParentId(resolved.parent() == null ? null : resolved.parent().getId());
            cmd.setFolderName(resolved.name());
            fileInfoService.createDirectory(cmd);
            response.setStatus(HttpServletResponse.SC_CREATED);
        } catch (BusinessException e) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            response.setContentType("text/plain; charset=UTF-8");
            response.getWriter().write(e.getMessage());
        }
    }

    /** DELETE：进回收站（与 Web 端语义一致，可恢复） */
    private void handleDelete(HttpServletResponse response, String relativePath) throws IOException {
        DavPathResolver.ResolvedPath resolved = resolveQuietly(response, relativePath);
        if (resolved == null) {
            return;
        }
        if (resolved.file() == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        try {
            fileInfoService.moveFilesToRecycleBin(List.of(resolved.file().getId()));
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        } catch (BusinessException e) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            response.setContentType("text/plain; charset=UTF-8");
            response.getWriter().write(e.getMessage());
        }
    }

    /** MOVE = rename/move；COPY = 复制（只加引用不复制物理对象） */
    private void handleMove(HttpServletResponse response, String relativePath,
                            String destination, boolean copy) throws IOException {
        DavPathResolver.ResolvedPath resolved = resolveQuietly(response, relativePath);
        if (resolved == null) {
            return;
        }
        if (resolved.file() == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (StrUtil.isBlank(destination)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        String destRel = extractRelative(destination);
        if (destRel == null) {
            response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
            return;
        }
        String destNorm;
        try {
            destNorm = DavPathResolver.normalize(destRel);
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        DavPathResolver.ResolvedPath dest = pathResolver.resolve(destNorm);
        if (dest.existed()) {
            // 覆盖已有目标：先移入回收站再执行（保持 MOVE over = 覆盖语义）
            fileInfoService.moveFilesToRecycleBin(List.of(dest.file().getId()));
        }
        try {
            if (copy) {
                CopyFileCmd cmd = new CopyFileCmd();
                cmd.setFileIds(List.of(resolved.file().getId()));
                cmd.setDirId(dest.parent() == null ? null : dest.parent().getId());
                fileInfoService.copyFiles(cmd);
            } else {
                boolean sameDir = java.util.Objects.equals(
                        resolved.file().getParentId(),
                        dest.parent() == null ? null : dest.parent().getId());
                if (sameDir) {
                    RenameFileCmd cmd = new RenameFileCmd();
                    cmd.setDisplayName(dest.name());
                    fileInfoService.renameFile(resolved.file().getId(), cmd);
                } else {
                    MoveFileCmd cmd = new MoveFileCmd();
                    cmd.setFileIds(List.of(resolved.file().getId()));
                    cmd.setDirId(dest.parent() == null ? null : dest.parent().getId());
                    fileInfoService.moveFile(cmd);
                    // moveFile 不改名，目标名不同时补一次 rename
                    if (!dest.name().equals(resolved.file().getDisplayName())) {
                        RenameFileCmd rename = new RenameFileCmd();
                        rename.setDisplayName(dest.name());
                        fileInfoService.renameFile(resolved.file().getId(), rename);
                    }
                }
            }
            response.setStatus(dest.existed() ? HttpServletResponse.SC_NO_CONTENT : HttpServletResponse.SC_CREATED);
        } catch (BusinessException e) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            response.setContentType("text/plain; charset=UTF-8");
            response.getWriter().write(e.getMessage());
        }
    }

    // ---------- helpers ----------

    private DavPathResolver.ResolvedPath resolveQuietly(HttpServletResponse response, String relativePath)
            throws IOException {
        try {
            return pathResolver.resolve(relativePath);
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return null;
        } catch (BusinessException e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("text/plain; charset=UTF-8");
            response.getWriter().write(e.getMessage());
            return null;
        }
    }

    private String extractRelativePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StrUtil.isNotEmpty(contextPath) && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        if (uri.startsWith("/dav")) {
            uri = uri.substring(4);
        }
        // 去掉前导/尾部斜杠：/dav/ → ""（根），/dav/a/b.txt → a/b.txt，/dav/dir/ → dir
        while (uri.startsWith("/")) {
            uri = uri.substring(1);
        }
        while (uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() - 1);
        }
        // URL 解码（%20 等）
        try {
            uri = java.net.URLDecoder.decode(uri, StandardCharsets.UTF_8);
        } catch (Exception ignore) {
        }
        return uri;
    }

    private String extractRelative(String destination) {
        // Destination 可能是绝对 URL 或绝对路径
        String uri = destination;
        int schemeIdx = uri.indexOf("://");
        if (schemeIdx > 0) {
            int pathIdx = uri.indexOf('/', schemeIdx + 3);
            uri = pathIdx < 0 ? "/" : uri.substring(pathIdx);
        }
        String contextPath = "/dav";
        if (!uri.startsWith(contextPath)) {
            return null;
        }
        uri = uri.substring(contextPath.length());
        while (uri.startsWith("/")) {
            uri = uri.substring(1);
        }
        while (uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() - 1);
        }
        try {
            uri = java.net.URLDecoder.decode(uri, StandardCharsets.UTF_8);
        } catch (Exception ignore) {
        }
        return uri;
    }

    private String href(String relativePath) {
        String encoded = StrUtil.isEmpty(relativePath) ? "/" : relativePath;
        if (!encoded.equals("/")) {
            String[] segments = encoded.split("/");
            StringBuilder sb = new StringBuilder();
            for (String segment : segments) {
                sb.append('/');
                sb.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
            }
            encoded = sb.toString();
        }
        return "/dav" + encoded;
    }
}
