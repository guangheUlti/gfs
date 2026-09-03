package com.guanghe.fs.framework.preview.office;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "fs.preview.office")
public class OfficeToPdfConfig {

    /**
     * LibreOffice 安装路径，用于将 Office 文档（Word、Excel、PPT）转换为 PDF 进行预览
     * 
     * <p><b>Windows 环境（开箱即用）：</b></p>
     * <ul>
     *   <li>项目根目录已包含 LibreOfficePortable 便携版（位于 项目根目录/LibreOfficePortable）</li>
     *   <li>无需安装 LibreOffice，系统会自动使用内置版本</li>
     *   <li>如需使用自定义路径，可通过此配置项覆盖</li>
     * </ul>
     * 
     * <p><b>Linux/Mac 环境（需要安装）：</b></p>
     * <ul>
     *   <li>需要手动安装 LibreOffice</li>
     *   <li>如果未配置此项，系统会尝试使用默认路径：
     *     <ul>
     *       <li>Linux: /usr/lib/libreoffice</li>
     *       <li>Mac: /Applications/LibreOffice.app/Contents</li>
     *     </ul>
     *   </li>
     *   <li>如果 LibreOffice 安装在其他位置，必须通过此配置项指定</li>
     * </ul>
     * 
     * <p><b>配置示例：</b></p>
     * <pre>
     * # application.yml
     * fs:
     *   preview:
     *     office:
     *       # Windows 环境通常不需要配置（使用内置版本）
     *       # Linux/Mac 环境如果安装在默认路径也不需要配置
     *       
     *       # 仅在以下情况需要配置：
     *       # 1. Windows 环境想使用本地安装的 LibreOffice
     *       # 2. Linux/Mac 环境 LibreOffice 安装在非默认路径
     *       office-home: /opt/libreoffice
     * </pre>
     * 
     * <p><b>注意事项：</b></p>
     * <ul>
     *   <li>Windows 开发者：确保项目根目录下有 LibreOfficePortable 文件夹，然后直接运行即可</li>
     *   <li>Linux/Mac 开发者：首次运行前请确保已安装 LibreOffice</li>
     *   <li>如果路径配置错误或 LibreOffice 未安装，系统启动时会抛出异常并提供安装指引</li>
     * </ul>
     */
    private String officeHome;

    /**
     * 任务执行超时（毫秒）
     */
    private Long taskExecutionTimeout = 120000L;

    /**
     * 任务队列超时（毫秒）
     */
    private Long taskQueueTimeout = 30000L;

    /**
     * 最大任务数
     */
    private Integer maxTasksPerProcess = 200;

    /**
     * 是否启用转换
     */
    private Boolean enabled = true;

    /**
     * 转换缓存目录
     */
    private String cachePath = "/tmp/office-convert";
}
