package com.guanghe.fs.system.constant;

import java.util.List;

/**
 * 用户级权限定义：系统已移除工作空间与角色表，权限直接由「是否系统管理员」决定。
 * <p>
 * 这是权限编码的唯一事实来源，Sa-Token 的 {@code StpInterface} 与
 * {@code /apis/user/info} 返回给前端的权限列表都取自这里，避免两处定义漂移。
 */
public final class UserPermissions {

    private UserPermissions() {
    }

    /** 角色编码：系统管理员 */
    public static final String ROLE_ADMIN = "admin";
    /** 角色编码：普通用户 */
    public static final String ROLE_USER = "user";

    public static final String FILE_READ = "file:read";
    public static final String FILE_WRITE = "file:write";
    public static final String FILE_SHARE = "file:share";
    public static final String STORAGE_MANAGE = "storage:manage";
    public static final String LOG_READ = "log:read";

    /** 所有登录用户都拥有的权限：文件读写与分享 */
    private static final List<String> BASE_PERMISSIONS = List.of(FILE_READ, FILE_WRITE, FILE_SHARE);

    /** 仅系统管理员额外拥有的权限：存储平台管理与日志查看 */
    private static final List<String> ADMIN_EXTRA_PERMISSIONS = List.of(STORAGE_MANAGE, LOG_READ);

    /**
     * 按是否系统管理员返回权限编码列表
     *
     * @param superAdmin 是否系统管理员
     * @return 不可变的权限编码列表
     */
    public static List<String> of(boolean superAdmin) {
        if (!superAdmin) {
            return BASE_PERMISSIONS;
        }
        return List.of(FILE_READ, FILE_WRITE, FILE_SHARE, STORAGE_MANAGE, LOG_READ);
    }

    /**
     * 按是否系统管理员返回角色编码列表
     *
     * @param superAdmin 是否系统管理员
     * @return 不可变的角色编码列表
     */
    public static List<String> rolesOf(boolean superAdmin) {
        return List.of(superAdmin ? ROLE_ADMIN : ROLE_USER);
    }

    /** 管理员额外权限，供需要单独判断的场合使用 */
    public static List<String> adminExtraPermissions() {
        return ADMIN_EXTRA_PERMISSIONS;
    }
}
