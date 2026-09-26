package com.guanghe.fs.service.oss;

import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分片上传会话注册表（内存态）：Initiate → UploadPart → Complete/Abort。
 * <p>
 * 分片数据落临时文件（java.io.tmpdir），Complete 时按 partNumber 升序合并为单一 InputStream
 * 交给 {@code FileInfoService.writeFileContent} 走正常入库/去重/存储链路；孤儿会话 2 小时后清理。
 */
final class OssMultipartRegistry {

    private static final long SESSION_TTL_MS = 2 * 60 * 60 * 1000L;

    record Session(String key, String parentId, String displayName,
                   LocalDateTime createdAt, Map<Integer, Part> parts) {
    }

    record Part(int number, java.io.File file, long size, LocalDateTime storedAt) {
    }

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private OssMultipartRegistry() {
    }

    static void create(String uploadId, String key, String parentId, String displayName) {
        SESSIONS.put(uploadId, new Session(key, parentId, displayName,
                LocalDateTime.now(), new ConcurrentHashMap<>()));
    }

    static Session get(String uploadId) {
        Session session = SESSIONS.get(uploadId);
        if (session == null) {
            return null;
        }
        if (session.createdAt().plusSeconds(SESSION_TTL_MS / 1000).isBefore(LocalDateTime.now())) {
            abort(uploadId);
            return null;
        }
        return session;
    }

    static void storePart(String uploadId, int partNumber, InputStream in, Long expectedSize) throws IOException {
        Session session = SESSIONS.get(uploadId);
        if (session == null) {
            throw new IOException("upload session not found");
        }
        java.io.File tempFile = java.nio.file.Files.createTempFile("gfs-oss-part-", ".part").toFile();
        long size;
        try {
            size = java.nio.file.Files.copy(in, tempFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            tempFile.delete();
            throw e;
        }
        if (expectedSize != null && expectedSize >= 0 && expectedSize != size) {
            tempFile.delete();
            throw new IOException("part size mismatch, expect " + expectedSize + " actual " + size);
        }
        Part prev = session.parts().put(partNumber,
                new Part(partNumber, tempFile, size, LocalDateTime.now()));
        if (prev != null) {
            prev.file().delete();
        }
    }

    static List<Part> listParts(String uploadId) {
        Session session = SESSIONS.get(uploadId);
        if (session == null) {
            return List.of();
        }
        List<Part> parts = new ArrayList<>(session.parts().values());
        parts.sort(Comparator.comparingInt(Part::number));
        return parts;
    }

    /** 按 partNumber 升序合并为单一顺序流（Complete 用） */
    static InputStream assemble(String uploadId) throws IOException {
        List<Part> parts = listParts(uploadId);
        if (parts.isEmpty()) {
            throw new IOException("no parts uploaded");
        }
        List<InputStream> streams = new ArrayList<>(parts.size());
        for (Part part : parts) {
            streams.add(java.nio.file.Files.newInputStream(part.file().toPath()));
        }
        return new SequenceInputStream(java.util.Collections.enumeration(streams));
    }

    static void abort(String uploadId) {
        Session session = SESSIONS.remove(uploadId);
        if (session != null) {
            for (Part part : session.parts().values()) {
                part.file().delete();
            }
        }
    }
}
