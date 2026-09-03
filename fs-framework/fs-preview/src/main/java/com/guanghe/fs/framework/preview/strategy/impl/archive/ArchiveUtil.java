package com.guanghe.fs.framework.preview.strategy.impl.archive;

import lombok.extern.slf4j.Slf4j;
import net.sf.sevenzipjbinding.IInArchive;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream;
import net.sf.sevenzipjbinding.simple.ISimpleInArchive;
import net.sf.sevenzipjbinding.simple.ISimpleInArchiveItem;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

@Slf4j
public class ArchiveUtil {

    public static List<ArchiveFileInfo> parseArchive(InputStream inputStream) throws Exception {
        List<ArchiveFileInfo> fileInfos = new ArrayList<>();
        java.nio.file.Path tempPath = Files.createTempFile("preview_", ".tmp");

        try {
            Files.copy(inputStream, tempPath, StandardCopyOption.REPLACE_EXISTING);

            try (RandomAccessFile raf = new RandomAccessFile(tempPath.toFile(), "r");
                 IInArchive inArchive = SevenZip.openInArchive(null, new RandomAccessFileInStream(raf))) {

                ISimpleInArchive simpleInArchive = inArchive.getSimpleInterface();
                for (ISimpleInArchiveItem item : simpleInArchive.getArchiveItems()) {
                    String rawPath = item.getPath();
                    String decodedPath = fixFileNameEncoding(rawPath);
                    fileInfos.add(buildFileInfo(
                            decodedPath,
                            item.getSize() == null ? 0L : item.getSize(),
                            item.isFolder()
                    ));
                }
            }
        } finally {
            Files.deleteIfExists(tempPath);
        }
        return fileInfos;
    }

    /**
     * 修复文件名乱码的核心逻辑
     */
    public static String fixFileNameEncoding(String path) {
        if (path == null || path.isEmpty()) return "";

        // 如果字符串包含连续的问号，说明 Java 在用默认编码解析 JNI 传回的字节时失败了
        if (path.contains("??")) {
            try {
                // 尝试将路径退回到原始字节流（通常底层是 ISO_8859_1 强制转的），然后用 GBK 重新解码
                // 这种方式能解决 90% 以上 Windows 环境压缩包的乱码问题
                byte[] bytes = path.getBytes(StandardCharsets.ISO_8859_1);
                String gbkStr = new String(bytes, "GBK");

                // 如果重新解码后不再包含大量问号，说明修复成功
                if (!gbkStr.contains("??")) {
                    return gbkStr;
                }
            } catch (Exception e) {
                log.debug("尝试 GBK 解码失败: {}", path);
            }
        }
        return path;
    }

    private static ArchiveFileInfo buildFileInfo(String fullPath, long size, boolean isDir) {
        String name = getFileNameFromPath(fullPath);
        return ArchiveFileInfo.builder()
                .name(name)
                .path(fullPath)
                .isDirectory(isDir)
                .extension(isDir ? "" : getExtension(name))
                .build();
    }

    /**
     * 将反斜杠统一为正斜杠，并去掉首尾斜杠，用于压缩包内路径比对（与前端 innerPath 对齐）
     */
    public static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String p = path.replace("\\", "/");
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /**
     * 压缩包内预览路径是否安全：禁止路径段为 {@code ..}、盘符绝对路径等；不把 {@code ..} 当作子串判断，避免误伤 {@code foo..txt} 等合法名。
     */
    public static boolean isSafeArchiveInnerPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String p = path.replace("\\", "/");
        if (p.contains("://")) {
            return false;
        }
        for (String segment : p.split("/")) {
            if ("..".equals(segment)) {
                return false;
            }
        }
        String tail = p.startsWith("/") ? p.substring(1) : p;
        if (tail.length() >= 2 && tail.charAt(1) == ':') {
            return false;
        }
        return true;
    }

    public static String getFileNameFromPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        path = normalizePath(path);
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    public static String getExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        int lastDot = fileName.lastIndexOf('.');
        return (lastDot >= 0) ? fileName.substring(lastDot + 1) : "";
    }

    /**
     * 将扁平的文件列表转换为树形结构
     */
    public static List<ArchiveTreeNode> convertToTree(List<ArchiveFileInfo> flatFiles) {
        if (flatFiles == null || flatFiles.isEmpty()) {
            return Collections.emptyList();
        }

        // 按路径排序，确保父目录在子文件之前
        flatFiles.sort(Comparator.comparing(ArchiveFileInfo::getPath));

        List<ArchiveTreeNode> roots = new ArrayList<>();
        // 用于快速查找父节点的 Map：key=fullPath（统一去掉末尾斜杠）, value=TreeNode
        Map<String, ArchiveTreeNode> folderMap = new HashMap<>();

        for (ArchiveFileInfo file : flatFiles) {
            String fullPath = file.getPath();
            boolean isDir = file.getIsDirectory();

            // 统一处理：去掉末尾的斜杠，作为标准化路径
            String normalizedPath = fullPath;
            if (normalizedPath.endsWith("/") || normalizedPath.endsWith("\\")) {
                normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
            }

            // 获取当前节点的名称（最后一级）
            String nodeName = isDir ? getFolderNameOnly(fullPath) : file.getName();

            // 创建当前 TreeNode
            ArchiveTreeNode currentNode = ArchiveTreeNode.builder()
                    .name(nodeName)
                    .fullPath(normalizedPath)
                    .isDirectory(isDir)
                    .build();

            // 寻找父节点
            String parentPath = getParentPath(normalizedPath);

            if (parentPath == null || parentPath.isEmpty() || !folderMap.containsKey(parentPath)) {
                // 是根节点（或没有父目录节点，直接挂在根下）
                roots.add(currentNode);
            } else {
                // 有父节点，挂在父节点下
                ArchiveTreeNode parentNode = folderMap.get(parentPath);
                parentNode.addChild(currentNode);
            }

            // 如果是目录，加入 Map 以供子节点查找（使用标准化路径作为 key）
            if (isDir) {
                folderMap.put(normalizedPath, currentNode);
            }
        }

        return roots;
    }

    // 获取目录本身的名字 (如 "a/b/" -> "b")
    private static String getFolderNameOnly(String path) {
        if (path.endsWith("/") || path.endsWith("\\")) {
            path = path.substring(0, path.length() - 1);
        }
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    // 获取父级路径 (如 "a/b/c.txt" -> "a/b", "a/b/" -> "a")
    private static String getParentPath(String path) {
        // 统一处理掉末尾的斜杠
        if (path.endsWith("/") || path.endsWith("\\")) {
            path = path.substring(0, path.length() - 1);
        }
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(0, lastSlash) : null;
    }
}