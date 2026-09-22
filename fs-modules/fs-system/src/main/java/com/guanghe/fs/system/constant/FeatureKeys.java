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

    /** 全部合法的功能标识 */
    public static final Set<String> ALL = Set.of(FAVORITE, HISTORY, RECYCLE_BIN);
}
