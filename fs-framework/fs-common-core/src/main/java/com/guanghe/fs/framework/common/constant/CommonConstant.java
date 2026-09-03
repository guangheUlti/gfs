package com.guanghe.fs.framework.common.constant;

/**
 * 全局公共常量
 *
 * @author dinghao
 * @date 2024/6/7 11:12
 */
public interface CommonConstant {

    /**
     * 状态-启用
     */
    Integer Y = 1;

    /**
     * 状态-禁用
     */
    Integer N = 0;

    /**
     * 后缀分隔符
     */
    String SUFFIX_SPLIT = ".";


    /**
     * 默认超级管理员用户名
     */
    String DEFAULT_SUPER_ADMIN = "admin";

    /**
     * 默认密码
     */
    String DEFAULT_PASSWORD = "123456";

    /**
     * 存储平台请求头标识
     */
    String X_STORAGE_PLATFORM_CONFIG_ID = "X-Storage-Platform-Config-Id";

    /**
     * 工作空间请求头标识
     */
    String X_WORKSPACE_ID = "X-Workspace-Id";

    /**
     * 验证码长度
     */
    Integer VERIFY_CODE_LENGTH = 6;

    /**
     * 头像保存本地路径
     */
    String AVATAR_SAVE_PATH = "/avatar";
}
