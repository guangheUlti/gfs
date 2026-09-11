package com.guanghe.fs.service.sftp.nio;

import java.io.File;
import java.net.URI;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * GFS 逻辑路径，支持 nio 的绝对/相对两种形态：
 * <ul>
 *   <li>绝对路径 "/a/b/c"（以 / 开头）：挂在 GfsFileSystem 上参与解析，根为 "/"</li>
 *   <li>相对路径 "a/b"（名字元素）：getFileName/getName/subpath/iterator 的产物，
 *       toString() 直接是段名——MINA SFTP 的 readdir 用 getFileName().toString()
 *       作为客户端可见文件名，带 "/" 会被客户端判为 suspect path</li>
 * </ul>
 * 惰性解析：查询文件属性/子项时才走 GfsPathResolver 下钻 file_info 表（仅绝对路径）。
 */
public class GfsPath implements Path {

    /** 规范化后的路径文本：绝对路径带前导 /，相对路径不带 */
    private final String path;
    private final boolean absolute;
    private final GfsFileSystem fileSystem;

    GfsPath(GfsFileSystem fileSystem, String raw) {
        this.fileSystem = fileSystem;
        boolean abs = raw != null && raw.startsWith("/");
        if (abs) {
            String normalized = GfsPathResolver.normalize(raw);
            this.path = "/" + normalized;
            this.absolute = true;
        } else {
            this.path = joinRelative(raw);
            this.absolute = false;
        }
    }

    private GfsPath(GfsFileSystem fileSystem, String path, boolean absolute) {
        this.fileSystem = fileSystem;
        this.path = path;
        this.absolute = absolute;
    }

    /** 相对路径的轻量规范化：去空段、折叠 "."；段内不允许 "/" 之外的转义 */
    private static String joinRelative(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String segment : raw.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("invalid path segment: ..");
            }
            kept.add(segment);
        }
        return String.join("/", kept);
    }

    public String getAbsolutePath() {
        if (!absolute) {
            throw new IllegalArgumentException("relative path has no absolute form: " + path);
        }
        return path;
    }

    /** 解析为目标文件记录（根目录返回 null；不存在返回 null；相对路径不解析） */
    public com.guanghe.fs.file.domain.FileInfo toFileInfo() {
        // 此方法会被 MINA 线程从多个入口裸调（checkAccess/stat/READDIR 属性解析/读写通道），
        // 而下游 FileInfoService.getList 内部依赖 StpUtil 线程上下文。
        // bridge 已可重入：已处于 runAs 内时不会重复 set/clear，未处于时在此补齐。
        if (!absolute) {
            return null;
        }
        return fileSystem.bridge().runAs(fileSystem.userId(),
                () -> fileSystem.pathResolver().resolve(path, fileSystem.userId()));
    }

    public boolean exists() {
        if (!absolute) {
            return false;
        }
        if (isRoot()) {
            return true;
        }
        return toFileInfo() != null;
    }

    public boolean isRoot() {
        return absolute && "/".equals(path);
    }

    @Override
    public GfsFileSystem getFileSystem() {
        return fileSystem;
    }

    @Override
    public boolean isAbsolute() {
        return absolute;
    }

    @Override
    public Path getRoot() {
        return absolute ? fileSystem.getPath("/") : null;
    }

    @Override
    public Path getFileName() {
        List<String> segments = segments();
        // 根路径没有文件名（nio 约定返回 null）
        return segments.isEmpty() ? null : new GfsPath(fileSystem, segments.get(segments.size() - 1), false);
    }

    @Override
    public Path getParent() {
        List<String> segments = segments();
        if (segments.isEmpty()) {
            // 根路径没有父
            return null;
        }
        if (!absolute) {
            // 相对路径：单段无父，多段去掉最后一段
            return segments.size() == 1 ? null : new GfsPath(fileSystem, String.join("/", segments.subList(0, segments.size() - 1)), false);
        }
        if (segments.size() == 1) {
            return fileSystem.getPath("/");
        }
        return fileSystem.getPath("/" + String.join("/", segments.subList(0, segments.size() - 1)));
    }

    @Override
    public int getNameCount() {
        return segments().size();
    }

    @Override
    public Path getName(int index) {
        List<String> segments = segments();
        if (index < 0 || index >= segments.size()) {
            throw new IllegalArgumentException("name index out of range: " + index);
        }
        return new GfsPath(fileSystem, segments.get(index), false);
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        List<String> segments = segments();
        if (beginIndex < 0 || endIndex > segments.size() || beginIndex >= endIndex) {
            throw new IllegalArgumentException("subpath range invalid: " + beginIndex + ".." + endIndex);
        }
        return new GfsPath(fileSystem, String.join("/", segments.subList(beginIndex, endIndex)), false);
    }

    @Override
    public boolean startsWith(Path other) {
        String otherText = other instanceof GfsPath gp ? gp.path : other.toString();
        boolean otherAbsolute = other instanceof GfsPath gp ? gp.absolute : otherText.startsWith("/");
        if (otherAbsolute != absolute) {
            return false;
        }
        if (otherText.equals(path)) {
            return true;
        }
        return path.startsWith(otherText.endsWith("/") ? otherText : otherText + "/");
    }

    @Override
    public boolean startsWith(String other) {
        return startsWith(fileSystem.getPath(other));
    }

    @Override
    public boolean endsWith(Path other) {
        String otherText = other instanceof GfsPath gp ? gp.path : other.toString();
        boolean otherAbsolute = other instanceof GfsPath gp ? gp.absolute : otherText.startsWith("/");
        if (otherAbsolute) {
            return path.equals(otherText);
        }
        if (otherText.isEmpty()) {
            // 空相对路径只匹配自身（nio 约定根 endsWith 根）
            return segments().isEmpty();
        }
        return path.equals(otherText) || path.endsWith("/" + otherText);
    }

    @Override
    public boolean endsWith(String other) {
        return endsWith(fileSystem.getPath(other));
    }

    @Override
    public Path normalize() {
        return this; // 构造时已规范化
    }

    @Override
    public Path resolve(Path other) {
        return resolve(other.toString(), other.isAbsolute());
    }

    @Override
    public Path resolve(String other) {
        return resolve(other, other != null && other.startsWith("/"));
    }

    private Path resolve(String other, boolean otherAbsolute) {
        String on = otherAbsolute ? GfsPathResolver.normalize(other) : joinRelative(other);
        if (otherAbsolute) {
            return fileSystem.getPath("/" + on);
        }
        if (on.isEmpty()) {
            return this;
        }
        // 绝对 + 相对名 = 绝对子路径；相对 + 相对名 = 拼接（仍相对）
        if (absolute) {
            return fileSystem.getPath(isRoot() ? "/" + on : path + "/" + on);
        }
        return new GfsPath(fileSystem, path.isEmpty() ? on : path + "/" + on, false);
    }

    @Override
    public Path resolveSibling(Path other) {
        Path parent = getParent();
        return parent == null ? other : parent.resolve(other);
    }

    @Override
    public Path resolveSibling(String other) {
        Path parent = getParent();
        return parent == null ? fileSystem.getPath(other) : parent.resolve(other);
    }

    @Override
    public Path relativize(Path other) {
        boolean otherAbsolute = other instanceof GfsPath gp ? gp.absolute : other.toString().startsWith("/");
        String o = other instanceof GfsPath gp ? gp.path : other.toString();
        if (otherAbsolute != absolute) {
            throw new IllegalArgumentException("cannot relativize between absolute and relative paths: "
                    + path + " vs " + o);
        }
        if (!o.equals(path) && !o.startsWith(path.endsWith("/") ? path : path + "/")) {
            throw new IllegalArgumentException("cannot relativize " + o + " against " + path);
        }
        String rel = o.equals(path) ? "" : o.substring(path.length());
        return new GfsPath(fileSystem, rel.startsWith("/") ? rel.substring(1) : rel, false);
    }

    @Override
    public URI toUri() {
        if (!absolute) {
            throw new IllegalArgumentException("relative path has no URI: " + path);
        }
        return URI.create("gfs://" + path);
    }

    @Override
    public Path toAbsolutePath() {
        return absolute ? this : fileSystem.getPath("/" + path);
    }

    @Override
    public Path toRealPath(LinkOption... options) {
        return toAbsolutePath();
    }

    @Override
    public File toFile() {
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) {
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>... events) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterator<Path> iterator() {
        List<Path> parts = new ArrayList<>();
        for (String segment : segments()) {
            parts.add(new GfsPath(fileSystem, segment, false));
        }
        return parts.iterator();
    }

    @Override
    public int compareTo(Path other) {
        String o = other instanceof GfsPath gp ? gp.path : other.toString();
        return path.compareTo(o);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof GfsPath gp
                && gp.fileSystem == this.fileSystem
                && gp.absolute == this.absolute
                && gp.path.equals(this.path);
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }

    @Override
    public String toString() {
        return path;
    }

    /** 名字元素列表（根路径为空列表） */
    private List<String> segments() {
        if (absolute) {
            String normalized = path.length() <= 1 ? "" : path.substring(1);
            return normalized.isEmpty() ? List.of() : List.of(normalized.split("/"));
        }
        return path.isEmpty() ? List.of() : List.of(path.split("/"));
    }
}
