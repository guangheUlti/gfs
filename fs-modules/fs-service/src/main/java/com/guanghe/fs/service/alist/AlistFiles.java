package com.guanghe.fs.service.alist;

import cn.hutool.core.util.StrUtil;
import com.guanghe.fs.file.domain.FileInfo;
import com.guanghe.fs.file.domain.vo.FileVO;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * AList v3 的字段形态转换（路径、文件条目、类型枚举）。
 * <p>
 * AList 用 snake_case 与绝对路径（{@code /目录/文件}），网盘内部用 parentId 树与
 * {@link com.guanghe.fs.service.webdav.DavPathResolver} 的相对路径（{@code 目录/文件}）。
 * 转换集中在这里，控制器只做分发，避免两处各拼一次路径导致「多一个斜杠」这类问题。
 */
final class AlistFiles {

    /** AList 文件类型：0 未知 1 目录 2 视频 3 音频 4 文本 5 图片 6 办公文档 7 其他 */
    private static final int TYPE_DIR = 1;
    private static final int TYPE_VIDEO = 2;
    private static final int TYPE_AUDIO = 3;
    private static final int TYPE_TEXT = 4;
    private static final int TYPE_IMAGE = 5;
    private static final int TYPE_OFFICE = 6;
    private static final int TYPE_OTHER = 7;

    private static final DateTimeFormatter ISO_OFFSET =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);

    private AlistFiles() {
    }

    /** 相对路径 → AList 绝对路径（空 = 根 "/"） */
    static String toAlistPath(String relative) {
        return StrUtil.isEmpty(relative) ? "/" : "/" + relative;
    }

    /** AList 绝对路径 → 相对路径（去掉前导 /，交给 DavPathResolver 做 .. 校验） */
    static String toRelative(String alistPath) {
        String path = alistPath == null ? "" : alistPath;
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }

    /** FileVO → AList content 条目 */
    static Map<String, Object> item(FileVO vo) {
        return item(vo.getDisplayName(), vo.getSize(), Boolean.TRUE.equals(vo.getIsDir()),
                vo.getUploadTime(), vo.getUpdateTime(), vo.getMimeType());
    }

    /** FileInfo → AList content 条目 */
    static Map<String, Object> item(FileInfo file) {
        return item(file.getDisplayName(), file.getSize(), Boolean.TRUE.equals(file.getIsDir()),
                file.getUploadTime(), file.getUpdateTime(), file.getMimeType());
    }

    static Map<String, Object> item(String name, Long size, boolean isDir,
                                    LocalDateTime created, LocalDateTime modified, String mimeType) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("size", size == null ? 0L : size);
        item.put("is_dir", isDir);
        item.put("modified", iso(modified == null ? created : modified));
        item.put("created", iso(created));
        item.put("sign", "");
        item.put("thumb", "");
        item.put("type", typeOf(name, isDir, mimeType));
        item.put("hashinfo", "");
        item.put("hash_info", null);
        return item;
    }

    /** 扩展名/MIME → AList 类型编号（客户端仅用于挑图标，判错不影响播放） */
    static int typeOf(String name, boolean isDir, String mimeType) {
        if (isDir) {
            return TYPE_DIR;
        }
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (mime.startsWith("video/")) {
            return TYPE_VIDEO;
        }
        if (mime.startsWith("audio/")) {
            return TYPE_AUDIO;
        }
        if (mime.startsWith("image/")) {
            return TYPE_IMAGE;
        }
        if (mime.startsWith("text/") || mime.contains("json") || mime.contains("xml")) {
            return TYPE_TEXT;
        }
        String ext = extOf(name);
        return switch (ext) {
            case "mp4", "mkv", "avi", "mov", "wmv", "flv", "ts", "m2ts", "rmvb", "webm", "m4v", "mpg", "mpeg" ->
                    TYPE_VIDEO;
            case "mp3", "flac", "wav", "aac", "m4a", "ogg", "ape", "wma" -> TYPE_AUDIO;
            case "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "heic" -> TYPE_IMAGE;
            case "txt", "md", "log", "json", "xml", "yml", "yaml", "ini", "conf", "srt", "ass", "vtt" ->
                    TYPE_TEXT;
            case "doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf", "csv" -> TYPE_OFFICE;
            default -> TYPE_OTHER;
        };
    }

    /** 时间戳转 RFC3339（带本机时区偏移），AList 客户端按此格式展示 */
    static String iso(LocalDateTime time) {
        return time == null ? "" : ISO_OFFSET.format(time.atZone(ZoneId.systemDefault()));
    }

    private static String extOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
