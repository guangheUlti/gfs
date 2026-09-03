package com.guanghe.fs.framework.preview.office;

import lombok.extern.slf4j.Slf4j;

import java.io.File;

/**
 * LibreOffice 路径解析工具类
 * 负责检测操作系统类型、解析 LibreOffice 安装路径、验证路径有效性
 * 
 * <p>该工具类支持以下功能：</p>
 * <ul>
 *   <li>自动检测当前运行的操作系统类型（Windows、Linux、Mac）</li>
 *   <li>Windows 环境下自动检测并使用内置的 LibreOfficePortable</li>
 *   <li>根据操作系统类型提供默认的 LibreOffice 路径</li>
 *   <li>验证 LibreOffice 路径的有效性</li>
 *   <li>支持配置文件路径优先级</li>
 * </ul>
 *
 * @author system
 */
@Slf4j
public class LibreOfficePathResolver {

    /**
     * 解析 LibreOffice 路径
     * 
     * <p>路径解析优先级：</p>
     * <ol>
     *   <li>配置文件中明确指定的路径（最高优先级）</li>
     *   <li>Windows 环境下的内置 LibreOfficePortable</li>
     *   <li>根据操作系统的默认路径</li>
     * </ol>
     *
     * @param configuredPath 配置文件中指定的路径（可能为空或 null）
     * @return 最终使用的 LibreOffice 路径
     */
    public static String resolveOfficeHome(String configuredPath) {
        log.info("========== LibreOffice 路径解析开始 ==========");
        
        // 优先级 1: 如果配置路径不为空且不是默认的 Windows 路径，优先使用配置路径
        if (configuredPath != null && !configuredPath.trim().isEmpty() 
                && !"C:/Program Files/LibreOffice".equals(configuredPath)) {
            log.info("✓ 使用配置文件中指定的路径: {}", configuredPath);
            log.info("  来源: application.yml 配置 (fs.preview.office.office-home)");
            log.info("========== LibreOffice 路径解析完成 ==========");
            return configuredPath;
        }
        
        // 检测操作系统类型
        OSType osType = detectOperatingSystem();
        
        // 优先级 2 & 3: 根据操作系统类型获取默认路径
        String defaultPath = getDefaultOfficeHome(osType);
        
        if (defaultPath != null) {
            log.info("========== LibreOffice 路径解析完成 ==========");
            return defaultPath;
        }
        
        // 如果无法确定默认路径，回退到配置路径（即使是默认的 Windows 路径）
        if (configuredPath != null && !configuredPath.trim().isEmpty()) {
            log.warn("⚠ 无法确定默认路径，使用配置路径: {}", configuredPath);
            log.info("========== LibreOffice 路径解析完成 ==========");
            return configuredPath;
        }
        
        // 最后的回退方案
        log.error("✗ 无法解析 LibreOffice 路径，操作系统: {}", osType.getDisplayName());
        throw new IllegalStateException("无法解析 LibreOffice 路径，请在配置文件中明确指定 fs.preview.office.office-home");
    }

    /**
     * 检测操作系统类型
     * 
     * <p>通过读取系统属性 os.name 来判断当前运行的操作系统类型</p>
     *
     * @return 操作系统类型枚举
     */
    public static OSType detectOperatingSystem() {
        String osName = System.getProperty("os.name");
        if (osName == null) {
            log.warn("无法获取操作系统名称，返回 UNKNOWN");
            return OSType.UNKNOWN;
        }
        
        String osNameLower = osName.toLowerCase();
        OSType osType;
        
        if (osNameLower.contains("win")) {
            osType = OSType.WINDOWS;
        } else if (osNameLower.contains("nix") || osNameLower.contains("nux") || osNameLower.contains("aix")) {
            osType = OSType.LINUX;
        } else if (osNameLower.contains("mac")) {
            osType = OSType.MAC;
        } else {
            osType = OSType.UNKNOWN;
        }
        
        log.info("检测到操作系统: {} ({})", osType.getDisplayName(), osName);
        return osType;
    }

    /**
     * 获取内置 LibreOfficePortable 的路径（仅 Windows）
     * 
     * <p>检查项目工作目录下的 LibreOfficePortable 目录是否存在</p>
     *
     * @return 内置 LibreOffice 的绝对路径，如果不存在则返回 null
     */
    public static String getEmbeddedLibreOfficePath() {
        try {
            log.debug("开始检测内置 LibreOfficePortable...");
            
            // 获取项目工作目录（user.dir）
            String userDir = System.getProperty("user.dir");
            if (userDir == null || userDir.trim().isEmpty()) {
                log.warn("⚠ 无法获取项目工作目录（user.dir），跳过内置 LibreOffice 检测");
                return null;
            }
            
            log.debug("项目工作目录: {}", userDir);
            
            // 拼接 LibreOffice 路径
            String embeddedPath = userDir + File.separator + "LibreOffice";
            log.debug("检查内置路径: {}", embeddedPath);
            
            File embeddedDir = new File(embeddedPath);
            
            // 检查关键可执行文件是否存在
            File sofficeExe = new File(embeddedDir, "program/soffice.exe");
            
            if (embeddedDir.exists() && embeddedDir.isDirectory() && sofficeExe.exists()) {
                String absolutePath = embeddedDir.getAbsolutePath();
                log.info("✓ 检测到内置 LibreOfficePortable: {}", absolutePath);
                return absolutePath;
            } else {
                log.warn("⚠ 项目内置的 LibreOfficePortable 未找到");
                log.warn("  检查路径: {}", embeddedPath);
                log.warn("  目录存在: {}", embeddedDir.exists());
                log.warn("  是否目录: {}", embeddedDir.isDirectory());
                log.warn("  soffice.exe 存在: {}", sofficeExe.exists());
                log.warn("  将尝试使用本地安装的 LibreOffice");
                return null;
            }
        } catch (Exception e) {
            log.warn("⚠ 检测内置 LibreOfficePortable 时发生异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 验证 LibreOffice 路径是否有效
     * 
     * <p>验证规则：</p>
     * <ul>
     *   <li>路径必须存在且为目录</li>
     *   <li>必须包含关键文件（soffice.exe 或 soffice）</li>
     * </ul>
     *
     * @param path 待验证的路径
     * @return true 如果路径存在且包含必要的 LibreOffice 文件，否则返回 false
     */
    public static boolean validateOfficeHome(String path) {
        if (path == null || path.trim().isEmpty()) {
            log.debug("LibreOffice 路径为空");
            return false;
        }
        
        File officeHomeDir = new File(path);
        if (!officeHomeDir.exists()) {
            log.debug("LibreOffice 路径不存在: {}", path);
            return false;
        }
        
        if (!officeHomeDir.isDirectory()) {
            log.debug("LibreOffice 路径不是目录: {}", path);
            return false;
        }
        
        // 检查关键文件是否存在（Windows: soffice.exe, Linux/Mac: soffice）
        File sofficeExe = new File(officeHomeDir, "program/soffice.exe");
        File soffice = new File(officeHomeDir, "program/soffice");
        File sofficeMac = new File(officeHomeDir, "MacOS/soffice");
        
        boolean isValid = sofficeExe.exists() || soffice.exists() || sofficeMac.exists();
        
        if (isValid) {
            log.debug("LibreOffice 路径验证成功: {}", path);
        } else {
            log.debug("LibreOffice 路径缺少关键文件 (program/soffice.exe 或 program/soffice 或 MacOS/soffice): {}", path);
        }
        
        return isValid;
    }

    /**
     * 获取默认的 LibreOffice 路径（根据操作系统）
     * 
     * <p>不同操作系统的默认路径：</p>
     * <ul>
     *   <li>Windows: 项目根目录下的 LibreOfficePortable 或 C:/Program Files/LibreOffice</li>
     *   <li>Linux: /usr/lib/libreoffice</li>
     *   <li>Mac: /Applications/LibreOffice.app/Contents</li>
     * </ul>
     *
     * @param osType 操作系统类型
     * @return 默认路径
     */
    public static String getDefaultOfficeHome(OSType osType) {
        switch (osType) {
            case WINDOWS:
                // Windows 环境下优先使用项目根目录下的 LibreOfficePortable
                String embeddedPath = getEmbeddedLibreOfficePath();
                if (embeddedPath != null) {
                    log.info("✓ 使用项目内置的 LibreOffice");
                    log.info("  路径: {}", embeddedPath);
                    log.info("  来源: 项目根目录/LibreOffice");
                    return embeddedPath;
                }
                // 回退到默认安装路径
                log.info("✓ 使用本地安装的 LibreOffice");
                log.info("  路径: C:/Program Files/LibreOffice");
                log.info("  来源: 系统默认安装路径");
                log.warn("  提示: 如果本地未安装 LibreOffice，启动将失败");
                return "C:/Program Files/LibreOffice";
                
            case LINUX:
                log.info("✓ 使用 Linux 默认路径: /usr/lib/libreoffice");
                log.info("  来源: 系统默认安装路径");
                return "/usr/lib/libreoffice";
                
            case MAC:
                log.info("✓ 使用 macOS 默认路径: /Applications/LibreOffice.app/Contents");
                log.info("  来源: 系统默认安装路径");
                return "/Applications/LibreOffice.app/Contents";
                
            default:
                log.warn("✗ 未知操作系统类型，无法提供默认路径");
                return null;
        }
    }
}
