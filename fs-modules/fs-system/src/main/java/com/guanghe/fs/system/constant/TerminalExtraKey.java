package com.guanghe.fs.system.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Sa-Token 终端扩展信息（SaTerminalInfo.extraData）的键。
 * <p>
 * 登录时写入（见 AuthServiceImpl），「登录管理」据此区分同一账号下的不同设备：
 * is-share=false 时每个终端都是独立的 random-128 token，若不记录登录环境，
 * 管理员看到的会是几条完全一样的记录，无法判断该踢哪一个。
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TerminalExtraKey {

    /** 登录时的客户端 IP */
    public static final String IP = "loginIp";

    /** 登录时解析出的浏览器 */
    public static final String BROWSER = "loginBrowser";

    /** 登录时解析出的操作系统 */
    public static final String OS = "loginOs";
}
