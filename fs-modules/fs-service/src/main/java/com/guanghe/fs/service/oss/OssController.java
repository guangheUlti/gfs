package com.guanghe.fs.service.oss;

import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.domain.vo.FileVO;
import com.guanghe.fs.file.service.FileInfoService;
import com.guanghe.fs.framework.common.domain.PageResult;
import com.guanghe.fs.framework.common.exception.BusinessException;
import com.guanghe.fs.service.webdav.DavPathResolver;
import com.guanghe.fs.file.domain.dto.CreateDirectoryCmd;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * S3 兼容网关（path-style，虚拟桶 gfs）：/oss/** 按 method + 子资源分发。
 * <p>
 * 映射约定：客户端把 GFS 网盘当 S3 的 {@code gfs} 桶使用——
 * {@code GET /oss/} = ListBuckets；{@code GET /oss/gfs?list-type=2} = ListObjectsV2；
 * {@code PUT /oss/gfs/a/b.txt} = 上传；{@code GET /oss/gfs/a/b.txt} = 下载；
 * {@code DELETE /oss/gfs/a/b.txt} = 删除（进回收站）；分片上传三件套按 uploadId 透传。
 * <p>
 * 目录语义：S3 无目录；带 key 的目录在 ListObjects 里以 CommonPrefixes（key 以 / 结尾）呈现，
 * 由客户端侧拼装（ListObjects 直接展开网盘目录树）。
 */
@Slf4j
@RestController
@RequestMapping("/oss")
@RequiredArgsConstructor
public class OssController {

    /** S3 固定虚拟桶名 */
    public static final String BUCKET = "gfs";

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                    .withZone(ZoneId.of("GMT"));

    private final FileInfoService fileInfoService;
    private final DavPathResolver pathResolver;

    @RequestMapping("/**")
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StrUtil.isNotEmpty(contextPath) && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        // 相对路径：/oss → ""，/oss/ → ""，/oss/gfs → gfs，/oss/gfs/a/b.txt → gfs/a/b.txt
        String path = uri.length() > 4 ? uri.substring(4) : "";
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        String key = "";
        // 作用域：/oss 或 /oss/ = Service（ListBuckets）；/oss/gfs[/key] = Bucket/Object；其余桶名 404
        boolean serviceScope = path.isEmpty();
        if (!serviceScope) {
            if (!path.equals(BUCKET) && !path.startsWith(BUCKET + "/")) {
                writeXmlError(response, 404, "NoSuchBucket", "The specified bucket does not exist: " + path);
                return;
            }
            key = path.equals(BUCKET) ? "" : trimSlashes(path.substring(BUCKET.length() + 1));
        }

        String uploadId = request.getParameter("uploadId");
        String subResource = firstSubResource(request);

        switch (method) {
            case "OPTIONS" -> handleOptions(response);
            case "GET" -> {
                if (serviceScope) {
                    handleListBuckets(response);                       // GET Service
                } else if (subResource != null && "uploadId".equals(subResource)) {
                    handleListParts(request, response, key, uploadId); // GET ?uploadId=
                } else if (key.isEmpty() || isListRequest(request)) {
                    handleListObjects(request, response);              // Bucket 列举（带 key 时按 prefix 过滤）
                } else {
                    handleGetObject(request, response, key, false);    // 对象读取
                }
            }
            case "HEAD" -> {
                if (serviceScope) {
                    response.setStatus(HttpServletResponse.SC_OK);
                } else if (key.isEmpty()) {
                    response.setStatus(HttpServletResponse.SC_OK);
                } else {
                    handleGetObject(request, response, key, true);
                }
            }
            case "PUT" -> {
                if ("partNumber".equals(subResource)) {
                    handleUploadPart(request, response, key, uploadId);  // PUT ?partNumber&uploadId
                } else if (key.isEmpty()) {
                    writeXmlError(response, 400, "InvalidRequest", "object key required");
                } else {
                    handlePutObject(request, response, key);
                }
            }
            case "POST" -> {
                if ("uploads".equals(subResource)) {
                    handleInitiateMultipart(request, response, key);          // POST ?uploads
                } else if ("uploadId".equals(subResource)) {
                    handleCompleteMultipart(request, response, key, uploadId); // POST ?uploadId=
                } else {
                    // 网关不支持 POST Object（表单上传）与 ?delete 批量删
                    writeXmlError(response, 501, "NotImplemented", "POST object is not supported");
                }
            }
            case "DELETE" -> {
                if ("uploadId".equals(subResource)) {
                    handleAbortMultipart(request, response, key, uploadId);   // DELETE ?uploadId=
                } else if (key.isEmpty()) {
                    writeXmlError(response, 400, "InvalidRequest", "object key required");
                } else {
                    handleDeleteObject(response, key);
                }
            }
            default -> {
                response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                response.setHeader("Allow", "OPTIONS, GET, HEAD, PUT, POST, DELETE");
            }
        }
    }

    // ---------- service / bucket ----------

    /** GET Service：ListBuckets，固定返回虚拟桶 gfs */
    private void handleListBuckets(HttpServletResponse response) throws IOException {
        String owner = cn.dev33.satoken.stp.StpUtil.getLoginIdAsString();
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<ListAllMyBucketsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + "<Owner><ID>" + escapeXml(owner) + "</ID><DisplayName>" + escapeXml(owner) + "</DisplayName></Owner>"
                + "<Buckets><Bucket><Name>" + BUCKET + "</Name>"
                + "<CreationDate>" + httpDate(LocalDateTime.now()) + "</CreationDate></Bucket></Buckets>"
                + "</ListAllMyBucketsResult>";
        writeXml(response, 200, xml);
    }

    /**
     * ListObjects（V1 + V2 通用处理）：递归展开用户目录树为扁平 key 列表，
     * prefix 服务端过滤；无分页 token（单次最多 10000 条，超大网盘建议客户端按 prefix 分前缀遍历）。
     */
    private void handleListObjects(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String prefix = StrUtil.emptyToDefault(request.getParameter("prefix"), "");
        StringBuilder contents = new StringBuilder();
        int[] count = {0};
        expand(null, "", prefix, contents, count);

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + "<Name>" + BUCKET + "</Name><Prefix>" + escapeXml(prefix) + "</Prefix>"
                + "<Marker></Marker><MaxKeys>10000</MaxKeys><IsTruncated>false</IsTruncated>"
                + contents
                + "</ListBucketResult>";
        writeXml(response, 200, xml);
    }

    /** 递归展开目录树：文件输出 <Contents>（key = 父路径 + displayName），目录递归下钻 */
    private void expand(String parentId, String parentKey, String prefix, StringBuilder contents, int[] count)
            throws IOException {
        if (count[0] >= 10000) {
            return;
        }
        com.guanghe.fs.file.domain.qry.FileQry qry = new com.guanghe.fs.file.domain.qry.FileQry();
        qry.setParentId(parentId);
        qry.setPage(1);
        qry.setPageSize(10000);
        PageResult<FileVO> page = fileInfoService.getList(qry);
        if (page == null || page.getData() == null || page.getData().getRecords() == null) {
            return;
        }
        for (FileVO vo : page.getData().getRecords()) {
            String key = parentKey + vo.getDisplayName();
            if (Boolean.TRUE.equals(vo.getIsDir())) {
                expand(vo.getId(), key + "/", prefix, contents, count);
            } else {
                if (!prefix.isEmpty() && !key.startsWith(prefix)) {
                    continue;
                }
                if (count[0] >= 10000) {
                    return;
                }
                count[0]++;
                contents.append("<Contents><Key>").append(escapeXml(key))
                        .append("</Key><LastModified>").append(toIso8601(vo.getUploadTime()))
                        .append("</LastModified><ETag>&quot;").append(vo.getId()).append("&quot;</ETag>")
                        .append("<Size>").append(vo.getSize() == null ? 0 : vo.getSize()).append("</Size>")
                        .append("<StorageClass>STANDARD</StorageClass></Contents>");
            }
        }
    }

    // ---------- object ----------

    private void handleGetObject(HttpServletRequest request, HttpServletResponse response,
                                 String key, boolean head) throws IOException {
        DavPathResolver.ResolvedPath resolved = resolveQuietly(response, key);
        if (resolved == null) {
            return;
        }
        FileInfo file = resolved.file();
        if (file == null || Boolean.TRUE.equals(file.getIsDir())) {
            writeXmlError(response, 404, "NoSuchKey", "The specified key does not exist: " + key);
            return;
        }
        long size = file.getSize() == null ? 0 : file.getSize();
        response.setContentType(StrUtil.blankToDefault(file.getMimeType(), "application/octet-stream"));
        response.setHeader("ETag", "\"" + file.getId() + "\"");
        response.setHeader("Last-Modified", httpDate(file.getUploadTime()));
        response.setHeader("Accept-Ranges", "bytes");

        // Range：S3 标准单段 bytes=start-end
        String rangeHeader = request.getHeader("Range");
        long start = 0;
        long end = size - 1;
        boolean partial = false;
        if (!head && StrUtil.isNotBlank(rangeHeader)) {
            long[] range = parseRange(rangeHeader, size);
            if (range == null) {
                response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                response.setHeader("Content-Range", "bytes */" + size);
                return;
            }
            start = range[0];
            end = Math.min(range[1], size - 1);
            partial = true;
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
        try (InputStream in = partial
                ? fileInfoService.openRangeStream(file.getId(), start, end)
                : fileInfoService.downloadFile(file.getId());
             OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
            out.flush();
        } catch (Exception e) {
            log.warn("OSS 下载失败: key={}", key, e);
            if (!response.isCommitted()) {
                writeXmlError(response, 500, "InternalError", "download failed");
            }
        }
    }

    /** PUT Object：整体上传/覆盖（S3 语义：父目录不存在时自动逐级创建） */
    private void handlePutObject(HttpServletRequest request, HttpServletResponse response, String key)
            throws IOException {
        try {
            String parentId = resolveOrCreateParent(key);
            String displayName = lastName(key);
            fileInfoService.writeFileContent(parentId, displayName, request.getInputStream(),
                    parseContentLength(request));
            response.setStatus(HttpServletResponse.SC_OK);
            response.setHeader("ETag", "\"" + cn.hutool.crypto.digest.DigestUtil.md5Hex(displayName + key) + "\"");
        } catch (BusinessException e) {
            writeXmlError(response, 409, "InvalidArgument", e.getMessage());
        } catch (Exception e) {
            log.warn("OSS PUT 失败: key={}", key, e);
            writeXmlError(response, 500, "InternalError", "upload failed");
        }
    }

    /** DELETE Object：进回收站（与 Web 端语义一致） */
    private void handleDeleteObject(HttpServletResponse response, String key) throws IOException {
        DavPathResolver.ResolvedPath resolved = resolveQuietly(response, key);
        if (resolved == null) {
            return;
        }
        if (resolved.file() == null) {
            // S3 幂等删除：不存在也 204
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        try {
            fileInfoService.moveFilesToRecycleBin(List.of(resolved.file().getId()));
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        } catch (BusinessException e) {
            writeXmlError(response, 409, "InvalidArgument", e.getMessage());
        }
    }

    // ---------- multipart ----------

    /** POST ?uploads：InitiateMultipartUpload（父目录不自动创建，Complete 时若缺失由 writeFileContent 报错） */
    private void handleInitiateMultipart(HttpServletRequest request, HttpServletResponse response,
                                         String key) throws IOException {
        String id = cn.hutool.core.util.IdUtil.fastSimpleUUID();
        OssMultipartRegistry.create(id, key, resolveOrCreateParent(key), lastName(key));
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<InitiateMultipartUploadResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + "<Bucket>" + BUCKET + "</Bucket><Key>" + escapeXml(key) + "</Key>"
                + "<UploadId>" + id + "</UploadId></InitiateMultipartUploadResult>";
        writeXml(response, 200, xml);
    }

    /** PUT ?partNumber&uploadId：UploadPart（分片落临时文件，Complete 时按序合并写入） */
    private void handleUploadPart(HttpServletRequest request, HttpServletResponse response,
                                  String key, String uploadId) throws IOException {
        OssMultipartRegistry.Session session = OssMultipartRegistry.get(uploadId);
        if (session == null || !key.equals(session.key())) {
            writeXmlError(response, 404, "NoSuchUpload", "uploadId not found: " + uploadId);
            return;
        }
        int partNumber;
        try {
            partNumber = Integer.parseInt(request.getParameter("partNumber"));
        } catch (NumberFormatException e) {
            writeXmlError(response, 400, "InvalidArgument", "invalid partNumber");
            return;
        }
        try {
            OssMultipartRegistry.storePart(uploadId, partNumber, request.getInputStream(),
                    parseContentLength(request));
            // S3 要求每个 part 返回 ETag
            response.setStatus(HttpServletResponse.SC_OK);
            response.setHeader("ETag", "\"part-" + partNumber + "-" + uploadId + "\"");
        } catch (Exception e) {
            log.warn("OSS UploadPart 失败: key={}, part={}", key, partNumber, e);
            writeXmlError(response, 500, "InternalError", "upload part failed");
        }
    }

    /** GET ?uploadId：ListParts */
    private void handleListParts(HttpServletRequest request, HttpServletResponse response,
                                 String key, String uploadId) throws IOException {
        OssMultipartRegistry.Session session = OssMultipartRegistry.get(uploadId);
        if (session == null || !key.equals(session.key())) {
            writeXmlError(response, 404, "NoSuchUpload", "uploadId not found: " + uploadId);
            return;
        }
        StringBuilder parts = new StringBuilder();
        for (OssMultipartRegistry.Part part : OssMultipartRegistry.listParts(uploadId)) {
            parts.append("<Part><PartNumber>").append(part.number())
                    .append("</PartNumber><LastModified>").append(toIso8601(part.storedAt()))
                    .append("</LastModified><ETag>&quot;part-").append(part.number()).append('-').append(uploadId)
                    .append("&quot;</ETag><Size>").append(part.size()).append("</Size></Part>");
        }
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<ListPartsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + "<Bucket>" + BUCKET + "</Bucket><Key>" + escapeXml(key) + "</Key>"
                + "<UploadId>" + uploadId + "</UploadId><IsTruncated>false</IsTruncated>"
                + parts + "</ListPartsResult>";
        writeXml(response, 200, xml);
    }

    /** POST ?uploadId：CompleteMultipartUpload，按 partNumber 升序合并写网盘 */
    private void handleCompleteMultipart(HttpServletRequest request, HttpServletResponse response,
                                        String key, String uploadId) throws IOException {
        OssMultipartRegistry.Session session = OssMultipartRegistry.get(uploadId);
        if (session == null || !key.equals(session.key())) {
            writeXmlError(response, 404, "NoSuchUpload", "uploadId not found: " + uploadId);
            return;
        }
        // 请求体里的 <Part> 顺序声明仅作校验参考，服务端按 partNumber 升序合并
        try (InputStream body = request.getInputStream()) {
            body.readAllBytes();
        }
        try {
            fileInfoService.writeFileContent(session.parentId(), session.displayName(),
                    OssMultipartRegistry.assemble(uploadId), null);
        } catch (BusinessException e) {
            OssMultipartRegistry.abort(uploadId);
            writeXmlError(response, 409, "InvalidArgument", e.getMessage());
            return;
        } catch (Exception e) {
            log.warn("OSS CompleteMultipartUpload 失败: key={}", key, e);
            OssMultipartRegistry.abort(uploadId);
            writeXmlError(response, 500, "InternalError", "complete multipart upload failed");
            return;
        }
        OssMultipartRegistry.abort(uploadId);
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<CompleteMultipartUploadResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + "<Location>/oss/" + BUCKET + "/" + escapeXml(key) + "</Location>"
                + "<Bucket>" + BUCKET + "</Bucket><Key>" + escapeXml(key) + "</Key>"
                + "<ETag>&quot;" + cn.hutool.crypto.digest.DigestUtil.md5Hex(key) + "&quot;</ETag>"
                + "</CompleteMultipartUploadResult>";
        writeXml(response, 200, xml);
    }

    /** DELETE ?uploadId：AbortMultipartUpload */
    private void handleAbortMultipart(HttpServletRequest request, HttpServletResponse response,
                                      String key, String uploadId) throws IOException {
        OssMultipartRegistry.Session session = OssMultipartRegistry.get(uploadId);
        if (session == null || !key.equals(session.key())) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        OssMultipartRegistry.abort(uploadId);
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    // ---------- helpers ----------

    /** 分发用：返回第一个出现的 sub-resource 名（uploads / uploadId / partNumber） */
    private String firstSubResource(HttpServletRequest request) {
        if (request.getParameter("uploads") != null) {
            return "uploads";
        }
        // UploadPart 同时携带 partNumber 与 uploadId，必须先判 partNumber
        if (request.getParameter("partNumber") != null) {
            return "partNumber";
        }
        if (request.getParameter("uploadId") != null) {
            return "uploadId";
        }
        return null;
    }

    private boolean isListRequest(HttpServletRequest request) {
        return request.getParameter("list-type") != null || request.getParameter("marker") != null
                || request.getParameter("continuation-token") != null
                || request.getParameter("prefix") != null || request.getParameter("delimiter") != null;
    }

    private String trimSlashes(String s) {
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private String lastName(String key) {
        int idx = key.lastIndexOf('/');
        return idx < 0 ? key : key.substring(idx + 1);
    }

    /** 按网盘目录树逐段解析 key 的父目录，缺失的中间目录自动创建；key 在根时返回 null */
    private String resolveOrCreateParent(String key) {
        if (!key.contains("/")) {
            return null;
        }
        String userId = cn.dev33.satoken.stp.StpUtil.getLoginIdAsString();
        String[] segments = key.substring(0, key.lastIndexOf('/')).split("/");
        String parentId = null;
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            FileInfo child = pathResolver.findChild(userId, parentId, segment);
            if (child != null && Boolean.TRUE.equals(child.getIsDir())) {
                parentId = child.getId();
            } else {
                CreateDirectoryCmd cmd = new CreateDirectoryCmd();
                cmd.setParentId(parentId);
                cmd.setFolderName(segment);
                FileInfo dir = fileInfoService.createDirectory(cmd);
                parentId = dir.getId();
            }
        }
        return parentId;
    }

    private DavPathResolver.ResolvedPath resolveQuietly(HttpServletResponse response, String key) throws IOException {
        try {
            return pathResolver.resolve(key);
        } catch (IllegalArgumentException e) {
            writeXmlError(response, 400, "InvalidArgument", e.getMessage());
            return null;
        } catch (BusinessException e) {
            writeXmlError(response, 500, "InternalError", e.getMessage());
            return null;
        }
    }

    private Long parseContentLength(HttpServletRequest request) {
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

    /** 单段 Range：bytes=start-end / bytes=N- / bytes=-N */
    private long[] parseRange(String header, long size) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("bytes=(\\d*)-(\\d*)").matcher(header.trim());
        if (!m.matches()) {
            return null;
        }
        String s = m.group(1);
        String e = m.group(2);
        if (s.isEmpty() && e.isEmpty()) {
            return null;
        }
        if (s.isEmpty()) {
            long suffix = Long.parseLong(e);
            if (suffix <= 0) {
                return null;
            }
            return new long[]{Math.max(size - suffix, 0), size - 1};
        }
        long start = Long.parseLong(s);
        long end = e.isEmpty() ? size - 1 : Long.parseLong(e);
        if (start > end || start >= size) {
            return null;
        }
        return new long[]{start, end};
    }

    private void handleOptions(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader("Allow", "OPTIONS, GET, HEAD, PUT, POST, DELETE");
        String reqHeaders = "Authorization,Content-Type,Content-Length,x-amz-content-sha256,x-amz-date,Range";
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "GET, HEAD, PUT, POST, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", reqHeaders);
        response.setHeader("Access-Control-Expose-Headers", "ETag, Content-Range, x-amz-request-id");
        response.setContentLength(0);
    }

    private void writeXml(HttpServletResponse response, int status, String xml) throws IOException {
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setContentType("application/xml; charset=UTF-8");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private void writeXmlError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        String safe = message == null ? "" : message
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        writeXml(response, status, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>" + code
                + "</Code><Message>" + safe + "</Message></Error>");
    }

    private String escapeXml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private String httpDate(LocalDateTime time) {
        return HTTP_DATE.format(time.atZone(ZoneId.systemDefault()));
    }

    private String toIso8601(LocalDateTime time) {
        return time == null ? "" : time.atZone(ZoneId.systemDefault())
                .toInstant().toString();
    }
}
