package com.guanghe.fs.service.webdav;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 手写拼装 WebDAV 207 Multi-Status XML（不引入 Milton 等三方库）。
 */
public final class PropfindXmlWriter {

    private static final DateTimeFormatter RFC1123 =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                    .withZone(ZoneId.of("GMT"));

    private PropfindXmlWriter() {
    }

    /** 207 响应头 */
    public static final String XML_DECLARATION =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";

    /** 转义 XML 特殊字符 */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** RFC 1123 日期（Last-Modified / getlastmodified 用） */
    public static String formatHttpDate(java.time.LocalDateTime time) {
        if (time == null) {
            return RFC1123.format(ZonedDateTime.now(ZoneId.of("GMT")));
        }
        return RFC1123.format(time.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneId.of("GMT")));
    }

    /**
     * 生成单节点 &lt;D:response&gt; 块
     *
     * @param href        /dav 相对 href（已 URL 编码）
     * @param displayName 显示名
     * @param isDirectory 是否目录
     * @param size        字节数（目录传 null）
     * @param lastModified 最后修改时间
     * @param mimeType    MIME 类型（目录传 null）
     */
    public static String writeResponse(String href, String displayName, boolean isDirectory,
                                       Long size, java.time.LocalDateTime lastModified, String mimeType) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("<D:response>\n");
        sb.append("  <D:href>").append(escape(href)).append("</D:href>\n");
        sb.append("  <D:propstat>\n");
        sb.append("    <D:prop>\n");
        sb.append("      <D:displayname>").append(escape(displayName)).append("</D:displayname>\n");
        if (isDirectory) {
            sb.append("      <D:resourcetype><D:collection/></D:resourcetype>\n");
        } else {
            sb.append("      <D:resourcetype/>\n");
            if (size != null) {
                sb.append("      <D:getcontentlength>").append(size).append("</D:getcontentlength>\n");
            }
            sb.append("      <D:getcontenttype>").append(escape(
                    mimeType == null ? "application/octet-stream" : mimeType)).append("</D:getcontenttype>\n");
        }
        sb.append("      <D:getlastmodified>").append(formatHttpDate(lastModified)).append("</D:getlastmodified>\n");
        sb.append("      <D:creationdate>").append(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
                .format(lastModified == null ? java.time.LocalDateTime.now() : lastModified))
                .append("</D:creationdate>\n");
        sb.append("      <D:supportedlock/>\n");
        sb.append("    </D:prop>\n");
        sb.append("    <D:status>HTTP/1.1 200 OK</D:status>\n");
        sb.append("  </D:propstat>\n");
        sb.append("</D:response>\n");
        return sb.toString();
    }

    /** 包裹 multistatus 根元素 */
    public static String wrapMultistatus(String responses) {
        return XML_DECLARATION
                + "<D:multistatus xmlns:D=\"DAV:\">\n"
                + responses
                + "</D:multistatus>\n";
    }

    /** PROPPATCH 兼容应答：空 multistatus */
    public static String emptyMultistatus(String href) {
        return wrapMultistatus("<D:response>\n  <D:href>" + escape(href) + "</D:href>\n"
                + "  <D:propstat><D:prop/><D:status>HTTP/1.1 200 OK</D:status></D:propstat>\n</D:response>\n");
    }
}
