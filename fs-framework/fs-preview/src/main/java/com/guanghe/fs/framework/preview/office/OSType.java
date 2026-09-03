package com.guanghe.fs.framework.preview.office;

/**
 * 操作系统类型枚举
 * 用于标识不同的操作系统平台，以便进行平台特定的配置
 *
 * @author system
 */
public enum OSType {
    /**
     * Windows 操作系统
     */
    WINDOWS("Windows"),
    
    /**
     * Linux 操作系统
     */
    LINUX("Linux"),
    
    /**
     * Mac OS X 操作系统
     */
    MAC("Mac OS X"),
    
    /**
     * 未知操作系统
     */
    UNKNOWN("Unknown");
    
    /**
     * 操作系统显示名称
     */
    private final String displayName;
    
    /**
     * 构造函数
     *
     * @param displayName 操作系统显示名称
     */
    OSType(String displayName) {
        this.displayName = displayName;
    }
    
    /**
     * 获取操作系统显示名称
     *
     * @return 操作系统显示名称
     */
    public String getDisplayName() {
        return displayName;
    }
}
