package com.guanghe.fs.file.serving;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP 内容交付管道：把「构建响应头 + Range 解析 + 流式输出」从各控制器收敛到一处。
 * <p>
 * 边界约定：<b>鉴权留在控制器</b>（各出口的凭证语义完全不同：用户 token / 分享凭证 /
 * 任务归属），管道只负责鉴权之后的「交付段」。控制器用 {@link Request} 描述交付意图，
 * 管道产出最终 ResponseEntity。
 * <p>
 * 统一能力：
 * <ul>
 *   <li>Range 断点续传（206 / Content-Range / 416），maxRangeSize 守卫；</li>
 *   <li>Content-Disposition（inline 安全类型判断含 svg 排除 / attachment）+ nosniff；</li>
 *   <li>Content-Type 按文件名推断、可选转换后缀重写；</li>
 *   <li>Accept-Ranges / Cache-Control（inline 媒体可缓存，附件不缓存）。</li>
 * </ul>
 */
@Slf4j
@Component
public class FileServingPipeline {

    private static final Pattern RANGE_PATTERN = Pattern.compile("bytes=(\\d*)-(\\d*)");

    /** 默认单次 Range 上限：10MB（与 FilePreviewConfig.maxRangeSize 默认一致，可被覆盖） */
    private static final long DEFAULT_MAX_RANGE = 10L * 1024 * 1024;
    private static final int BUFFER_SIZE = 8192;

    /**
     * 交付请求：描述「这次要交付什么」
     */
    public record Request(
            /** 文件名（决定 Content-Type 与 Content-Disposition） */
            String fileName,
            /** 客户端可见大小（转换流场景为输出大小；未知传 -1） */
            long size,
            /** 打开 [start, end] 闭区间流的工厂；start=0 且 end=-1 表示全量 */
            StreamOpener streamOpener,
            /** inline（内联展示，带安全类型判断）或 attachment（强制下载） */
            Disposition disposition,
            /** 是否允许 Range（附件 zip 一般无意义可关；媒体/直链开） */
            boolean rangeAllowed,
            /** 单次 Range 上限；<=0 用默认 10MB */
            long maxRangeSize,
            /** 响应扩展名覆盖（转换流如 docx→pdf）；null 不重写 */
            String responseExtensionOverride,
            /** Cache-Control 值；null 用默认（inline 媒体缓存一周，附件 no-cache） */
            String cacheControl) {

        /** 全量流开器（无 Range 场景） */
        public interface StreamOpener {
            InputStream open(long start, long end) throws Exception;
        }

        public static Request inline(String fileName, long size, StreamOpener opener) {
            return new Request(fileName, size, opener, Disposition.INLINE_SAFE, true, 0, null, null);
        }

        public static Request attachment(String fileName, long size, StreamOpener opener) {
            return new Request(fileName, size, opener, Disposition.ATTACHMENT, false, 0, null, null);
        }

        public Request withDisposition(Disposition d) {
            return new Request(fileName, size, streamOpener, d, rangeAllowed, maxRangeSize,
                    responseExtensionOverride, cacheControl);
        }

        public Request withRangeAllowed(boolean allowed) {
            return new Request(fileName, size, streamOpener, disposition, allowed, maxRangeSize,
                    responseExtensionOverride, cacheControl);
        }

        public Request withMaxRangeSize(long v) {
            return new Request(fileName, size, streamOpener, disposition, rangeAllowed, v,
                    responseExtensionOverride, cacheControl);
        }

        public Request withResponseExtension(String ext) {
            return new Request(fileName, size, streamOpener, disposition, rangeAllowed, maxRangeSize,
                    ext, cacheControl);
        }

        public Request withCacheControl(String cacheControl) {
            return new Request(fileName, size, streamOpener, disposition, rangeAllowed, maxRangeSize,
                    responseExtensionOverride, cacheControl);
        }
    }

    /** 交付形态：inline 安全类型（排除 svg/html 等可执行内容，文本补 charset）或附件下载 */
    public enum Disposition {
        INLINE_SAFE, ATTACHMENT
    }

    /**
     * 执行交付。调用方已完成鉴权与业务校验。
     *
     * @param rangeHeader 客户端 Range 头（可 null）
     */
    public ResponseEntity<StreamingResponseBody> serve(Request req, String rangeHeader) {
        boolean isRangeRequest = req.rangeAllowed()
                && rangeHeader != null && rangeHeader.startsWith("bytes=");

        if (isRangeRequest) {
            Range range = parseRange(rangeHeader, req.size());
            if (range == null) {
                // 坏 Range：416（size 已知才报 */size）
                HttpHeaders headers = baseHeaders(req, false);
                if (req.size() >= 0) {
                    headers.add(HttpHeaders.CONTENT_RANGE, "bytes */" + req.size());
                }
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .headers(headers).build();
            }
            return serveRange(req, range);
        }

        // 全量交付
        long size = req.size();
        StreamingResponseBody body = out -> {
            try (InputStream in = req.streamOpener().open(0, size > 0 ? size - 1 : -1)) {
                copy(in, out);
            } catch (Exception e) {
                // 客户端中断是常态，降为 debug
                log.debug("[serving] 流传输中断: {} - {}", req.fileName(), e.getMessage());
            }
        };
        HttpHeaders headers = baseHeaders(req, false);
        if (size >= 0) {
            headers.setContentLength(size);
        }
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private ResponseEntity<StreamingResponseBody> serveRange(Request req, Range range) {
        long size = req.size();
        long start = range.start();
        long end = Math.min(range.end(), size - 1);

        long maxRange = req.maxRangeSize() > 0 ? req.maxRangeSize() : DEFAULT_MAX_RANGE;
        if (end - start + 1 > maxRange) {
            end = start + maxRange - 1;
        }
        long contentLength = end - start + 1;
        long finalEnd = end; // lambda 捕获变量需 effectively final

        StreamingResponseBody body = out -> {
            try (InputStream in = req.streamOpener().open(start, finalEnd)) {
                copy(in, out, contentLength);
            } catch (Exception e) {
                log.debug("[serving] Range 流传输中断: {} - {}", req.fileName(), e.getMessage());
            }
        };

        HttpHeaders headers = baseHeaders(req, true);
        headers.setContentLength(contentLength);
        headers.add(HttpHeaders.CONTENT_RANGE, String.format("bytes %d-%d/%d", start, end, size));
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).headers(headers).body(body);
    }

    /**
     * 公共响应头：Content-Type / Content-Disposition（含安全判断）/ nosniff / Accept-Ranges / Cache-Control
     */
    private HttpHeaders baseHeaders(Request req, boolean isRange) {
        HttpHeaders headers = new HttpHeaders();

        String fileName = req.responseExtensionOverride() != null
                ? changeExtension(req.fileName(), req.responseExtensionOverride())
                : req.fileName();

        headers.setContentType(MediaTypeFactory.getMediaType(fileName)
                .orElse(MediaType.APPLICATION_OCTET_STREAM));

        // X-Content-Type-Options：防 MIME 嗅探，所有出口统一
        headers.add("X-Content-Type-Options", "nosniff");

        boolean inline = req.disposition() == Disposition.INLINE_SAFE
                && isSafeInlineType(headers.getContentType());
        if (inline) {
            MediaType mt = headers.getContentType();
            if (MediaType.TEXT_PLAIN_VALUE.equals(mt.toString())) {
                mt = new MediaType(mt, StandardCharsets.UTF_8);
                headers.setContentType(mt);
            }
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    "inline; filename*=UTF-8''" + encodeFileName(fileName));
        } else {
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename*=UTF-8''" + encodeFileName(fileName));
        }

        headers.set(HttpHeaders.ACCEPT_RANGES, isRange || req.rangeAllowed() ? "bytes" : "none");
        if (req.cacheControl() != null) {
            headers.setCacheControl(req.cacheControl());
        } else {
            headers.setCacheControl(inline && !isRange ? "public, max-age=604800" : "no-cache");
        }
        return headers;
    }

    /**
     * inline 白名单：图片（排除 svg）/视频/音频/PDF/文本/JSON。
     * 与原 FileShareController.isSafeInlineType 语义一致。
     */
    private boolean isSafeInlineType(MediaType mediaType) {
        String type = mediaType.toString();
        if (type.startsWith("image/")) {
            return !type.contains("svg");
        }
        return type.startsWith("video/") || type.startsWith("audio/")
                || MediaType.APPLICATION_PDF_VALUE.equals(type)
                || MediaType.TEXT_PLAIN_VALUE.equals(type)
                || MediaType.APPLICATION_JSON_VALUE.equals(type);
    }

    /**
     * 解析 Range：支持 bytes=N-M / N- / -N 三种形态。
     *
     * @return null 表示语法坏或 start 越界（调用方回 416）
     */
    private Range parseRange(String header, long size) {
        Matcher m = RANGE_PATTERN.matcher(header);
        if (!m.matches()) {
            return null;
        }
        String s = m.group(1);
        String e = m.group(2);
        try {
            if (s.isEmpty()) {
                // -N：末尾 N 字节
                long suffix = Long.parseLong(e);
                if (suffix <= 0) {
                    return null;
                }
                if (size <= 0) {
                    return null;
                }
                long start = Math.max(0, size - suffix);
                return new Range(start, size - 1);
            }
            long start = Long.parseLong(s);
            if (size >= 0 && start >= size) {
                return null; // 越界
            }
            long end = e.isEmpty() ? (size >= 0 ? size - 1 : Long.MAX_VALUE / 2) : Long.parseLong(e);
            if (end < start) {
                return null;
            }
            return new Range(start, end);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[BUFFER_SIZE];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        out.flush();
    }

    private void copy(InputStream in, OutputStream out, long limit) throws Exception {
        byte[] buf = new byte[BUFFER_SIZE];
        long total = 0;
        while (total < limit) {
            int toRead = (int) Math.min(buf.length, limit - total);
            int n = in.read(buf, 0, toRead);
            if (n == -1) {
                break;
            }
            out.write(buf, 0, n);
            total += n;
        }
        out.flush();
    }

    private String changeExtension(String fileName, String newExtension) {
        int dot = fileName.lastIndexOf('.');
        if (dot == -1) {
            return fileName + "." + newExtension;
        }
        String original = fileName.substring(dot + 1);
        if (original.equalsIgnoreCase(newExtension)) {
            return fileName;
        }
        return fileName.substring(0, dot) + "." + newExtension;
    }

    private String encodeFileName(String name) {
        try {
            return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 解析后的闭区间 Range */
    private record Range(long start, long end) {
    }
}
