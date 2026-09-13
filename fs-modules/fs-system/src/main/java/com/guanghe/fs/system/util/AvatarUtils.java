package com.guanghe.fs.system.util;

import com.guanghe.fs.storage.plugin.core.IStorageOperationService;

import java.io.InputStream;
import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 头像展示值处理：数据库存 objectKey（或旧版完整 URL），返回给前端统一转为
 * data URI（<img> 无法携带 Authorization 头，禁止任何匿名文件直链）
 */
public final class AvatarUtils {

    private static final String AVATAR_KEY_PREFIX = "avatar/";

    private static final Map<String, String> MIME_BY_SUFFIX = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp",
            "bmp", "image/bmp",
            "svg", "image/svg+xml"
    );

    private AvatarUtils() {
    }

    /**
     * 转换为可直接用于 <img src> 的展示值；读取失败一律返回 null（前端回退姓名首字）
     *
     * @param stored        库内存储值：objectKey / 旧版完整 URL / 外部图片 URL
     * @param localSupplier 内置本地存储实例（懒加载，仅在需要读文件时创建）
     */
    public static String resolve(String stored, Supplier<IStorageOperationService> localSupplier) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        String objectKey = toObjectKey(stored);
        if (objectKey == null) {
            // 外部图片 URL（注册自填等场景）原样返回
            return stored;
        }
        try (InputStream in = localSupplier.get().downloadFile(objectKey)) {
            byte[] bytes = in.readAllBytes();
            String mime = MIME_BY_SUFFIX.getOrDefault(suffix(objectKey), "application/octet-stream");
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 库内存储值 → Local 存储 objectKey；非本系统直链的外部 URL 返回 null
     */
    private static String toObjectKey(String stored) {
        if (stored.startsWith("http://") || stored.startsWith("https://")) {
            int idx = stored.indexOf(AVATAR_KEY_PREFIX);
            return idx < 0 ? null : stored.substring(idx);
        }
        return stored.startsWith("/") ? stored.substring(1) : stored;
    }

    private static String suffix(String objectKey) {
        int dot = objectKey.lastIndexOf('.');
        return dot < 0 ? "" : objectKey.substring(dot + 1).toLowerCase();
    }
}
