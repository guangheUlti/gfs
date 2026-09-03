package com.guanghe.fs.system.constant;

/**
 * 工作空间角色：预设编码与类型。
 */
public final class WorkspaceRoleConstants {

    private WorkspaceRoleConstants() {
    }

    public static final String CODE_ADMIN = "admin";
    public static final String CODE_MEMBER = "member";
    public static final String CODE_VIEWER = "viewer";

    /** 系统预设（随空间创建，权限固定，不可删除） */
    public static final int TYPE_SYSTEM = 0;
    /** 用户自定义 */
    public static final int TYPE_CUSTOM = 1;

    public static boolean isReservedRoleCode(String code) {
        if (code == null) {
            return false;
        }
        String c = code.trim().toLowerCase();
        return CODE_ADMIN.equals(c) || CODE_MEMBER.equals(c) || CODE_VIEWER.equals(c);
    }
}
