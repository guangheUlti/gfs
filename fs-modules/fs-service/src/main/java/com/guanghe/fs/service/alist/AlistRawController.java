package com.guanghe.fs.service.alist;

import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.service.FileInfoService;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.service.webdav.DavPathResolver;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AList 播放直链：{@code GET/HEAD /d/<路径>?sign=...}
 * <p>
 * 与 WebDAV 的 {@code /dav/**} GET 是两条独立出口：
 * <ul>
 *   <li>WebDAV 每个请求重放 Basic 密码（BCrypt 缓存命中也要查一次用户）；</li>
 *   <li>这里的 {@code sign} 是 HMAC 短签名（绑定用户 + 路径 + 12 小时），
 *       验签后直接查文件并发流，播放器连发几十个 Range 请求也不会打认证逻辑。</li>
 * </ul>
 * 刻意不用 {@code ResponseEntity<StreamingResponseBody>}：Spring 会把 body 放到
 * 异步线程执行，而 Sa-Token 的上下文是 ThreadLocal，跨线程后 {@code StpUtil} 直接抛
 * 「SaTokenContext 上下文尚未初始化」，表现为响应头 206 已发出、body 一个字节都不来。
 * 这里与 {@link com.guanghe.fs.service.webdav.DavController} 一样在当前请求线程直接写流。
 */
@Slf4j
@RestController
@RequestMapping("/d")
@RequiredArgsConstructor
public class AlistRawController {

    /** 仅匹配单段 bytes 范围：bytes=start-end / bytes=N- / bytes=-N */
    private static final Pattern RANGE_PATTERN = Pattern.compile("bytes=(\\d*)-(\\d*)");

    private final FileInfoService fileInfoService;
    private final DavPathResolver pathResolver;

    @RequestMapping("/**")
    public void download(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String relative = relativePath(request);
        DavPathResolver.ResolvedPath resolved;
        try {
            resolved = pathResolver.resolve(relative);
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        } catch (BusinessException e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }
        FileInfo file = resolved.file();
        if (file == null || Boolean.TRUE.equals(file.getIsDir())) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        long size = file.getSize() == null ? 0 : file.getSize();
        boolean head = "HEAD".equalsIgnoreCase(request.getMethod());

        response.setContentType(StrUtil.blankToDefault(file.getMimeType(), "application/octet-stream"));
        // 播放场景用 inline：attachment 会让部分播放器/浏览器改成先整包下载
        response.setHeader("Content-Disposition", "inline; filename*=UTF-8''"
                + URLEncoder.encode(file.getDisplayName(), StandardCharsets.UTF_8).replace("+", "%20"));
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Cache-Control", "private, max-age=3600");
        if (file.getUploadTime() != null) {
            response.setHeader("ETag", "\"" + file.getId() + "-"
                    + file.getUploadTime().atZone(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli() + "\"");
        }

        String rangeHeader = request.getHeader("Range");
        long start = 0;
        long end = size - 1;
        if (!head && StrUtil.isNotBlank(rangeHeader) && rangeHeader.startsWith("bytes=")) {
            long[] range = parseRange(rangeHeader, size);
            if (range == null) {
                response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                response.setHeader("Content-Range", "bytes */" + size);
                return;
            }
            start = range[0];
            end = Math.min(range[1], size - 1);
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
            response.setContentLengthLong(end - start + 1);
        } else {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentLengthLong(size);
            if (head) {
                return;
            }
        }

        String fileId = file.getId();
        boolean partial = rangeHeader != null && !head && response.getStatus() == 206;
        try (InputStream in = partial
                ? fileInfoService.openRangeStream(fileId, start, end)
                : fileInfoService.downloadFile(fileId);
             OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
            out.flush();
        } catch (Exception e) {
            log.warn("AList 直链传输失败: path={}, range={}-{}", relative, start, end, e);
            if (!response.isCommitted()) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }

    /**
     * 解析单段 Range。保留 AList/播放器常用的三种形态；
     * 终点越界按文件末字节裁剪（播放器常按固定块大小请求，末尾块必然越界）。
     *
     * @return null 表示语法错或起点越界（调用方回 416）
     */
    private long[] parseRange(String header, long size) {
        Matcher matcher = RANGE_PATTERN.matcher(header.trim());
        if (!matcher.matches()) {
            return null;
        }
        String startGroup = matcher.group(1);
        String endGroup = matcher.group(2);
        try {
            if (startGroup.isEmpty()) {
                if (endGroup.isEmpty() || size <= 0) {
                    return null;
                }
                long suffix = Long.parseLong(endGroup);
                if (suffix <= 0) {
                    return null;
                }
                return new long[]{Math.max(size - suffix, 0), size - 1};
            }
            long start = Long.parseLong(startGroup);
            if (size > 0 && start >= size) {
                return null;
            }
            long end = endGroup.isEmpty() ? size - 1 : Long.parseLong(endGroup);
            if (end < start) {
                return null;
            }
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** /d/a/b.mp4 → a/b.mp4（+ 号先转义再解码，避免文件名里的加号被当成空格） */
    private String relativePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StrUtil.isNotEmpty(contextPath) && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        String path = uri.length() > 2 ? uri.substring(3) : "";
        return AlistAuthFilter.decode(path);
    }
}
