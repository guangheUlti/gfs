package com.guanghe.fs.system.service;

import com.guanghe.fs.system.domain.vo.OnlineUserVO;

import java.util.List;

/**
 * 登录管理（在线会话查看与强制下线），仅系统管理员可用
 *
 * @Author: guangheUlti
 * @Date: 2026/9/5
 */
public interface SessionAdminService {

    /**
     * 列出当前所有在线账号及其终端
     *
     * @param keyword 可选过滤词，匹配用户名/昵称/登录标识/IP/浏览器
     * @return 在线账号列表，按用户名排序
     */
    List<OnlineUserVO> listOnlineSessions(String keyword);

    /**
     * 将指定账号的所有终端强制下线（含该账号自己发起的场景）
     *
     * @param loginId 登录标识（用户id）
     * @return 实际下线的会话数
     */
    int kickoutUser(String loginId);

    /**
     * 将指定账号的某一个终端强制下线
     *
     * @param loginId   登录标识（用户id）
     * @param index     终端序号，来自 {@link #listOnlineSessions(String)}
     * @param tokenTail 列表返回的 token 末 6 位，用于确认目标会话未发生变化；为空则跳过校验
     */
    void kickoutTerminal(String loginId, int index, String tokenTail);
}
