package com.guanghe.fs.system.constant;

import java.util.Set;

/**
 * 功能开关标识定义：新增开关时在此登记，未登记的 key 一律视为关闭
 *
 * @Author: guangheUlti
 * @Date: 2026/9/12
 */
public final class FeatureKeys {

    private FeatureKeys() {
    }

    /** 收藏 */
    public static final String FAVORITE = "favorite";

    /** 历史 */
    public static final String HISTORY = "history";

    /** 回收站：关闭后删除文件直接物理删除（不可恢复），且不产生回收站记录 */
    public static final String RECYCLE_BIN = "recycleBin";

    /** 分享：关闭后文件页不出现分享入口，分享链接访问不受影响 */
    public static final String SHARE = "share";

    /** 注册：关闭后隐藏登录页注册入口并拒绝注册请求；已有账号登录不受影响 */
    public static final String REGISTER = "register";

    /** 全部合法的功能标识 */
    public static final Set<String> ALL = Set.of(FAVORITE, HISTORY, RECYCLE_BIN, SHARE, REGISTER);

    /**
     * 缺省开启的开关：其余开关未配置时按关闭处理，注册必须反过来，
     * 否则升级后所有人都会突然无法注册。
     */
    public static final Set<String> DEFAULT_ENABLED = Set.of(REGISTER);
}
