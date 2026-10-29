package com.guanghe.fs.system.service;

import com.guanghe.fs.system.domain.vo.UserDataSummaryVO;

/**
 * 账号数据清理：由能同时访问文件、服务等模块的模块实现。
 * <p>
 * 接口放在 fs-system，管理员删除账号时通过该接口清理各模块的用户数据；
 * 具体实现位于 fs-service，避免 fs-system 反向依赖文件模块。
 *
 * @Author: guangheUlti
 * @Date: 2026/10/29
 */
public interface UserDataPurgeService {

    /**
     * 统计账号残留数据（只读）
     *
     * @param userId 用户id
     * @return 各类数据数量
     */
    UserDataSummaryVO summarize(String userId);

    /**
     * 彻底清除账号的全部内容数据（文件及物理对象、收藏、分享、收集、传输任务、OSS 密钥、传输设置）
     *
     * @param userId 用户id
     */
    void purge(String userId);
}
