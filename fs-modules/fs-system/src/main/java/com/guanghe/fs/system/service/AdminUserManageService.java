package com.guanghe.fs.system.service;

import com.guanghe.fs.framework.common.domain.PageResult;
import com.guanghe.fs.system.domain.dto.AdminResetPasswordCmd;
import com.guanghe.fs.system.domain.dto.AdminUserQry;
import com.guanghe.fs.system.domain.dto.AdminUserUpdateCmd;
import com.guanghe.fs.system.domain.vo.AdminUserVO;
import com.guanghe.fs.system.domain.vo.UserDataSummaryVO;

/**
 * 系统管理员账号管理（仅系统管理员可用）
 *
 * @Author: guangheUlti
 * @Date: 2026/10/29
 */
public interface AdminUserManageService {

    /**
     * 分页查询全部账号，附带在线终端数与防暴力锁定状态
     *
     * @param qry 查询参数
     * @return 账号分页结果
     */
    PageResult<AdminUserVO> listUsers(AdminUserQry qry);

    /**
     * 修改账号资料（仅昵称）
     *
     * @param userId 用户id
     * @param cmd    资料参数
     */
    void updateProfile(String userId, AdminUserUpdateCmd cmd);

    /**
     * 启用/禁用账号；禁用后强制下线该账号全部会话
     *
     * @param userId  用户id
     * @param enabled true-启用 false-禁用
     */
    void setEnabled(String userId, boolean enabled);

    /**
     * 管理员重置密码；重置后强制下线该账号全部会话
     *
     * @param userId 用户id
     * @param cmd    密码参数
     */
    void resetPassword(String userId, AdminResetPasswordCmd cmd);

    /**
     * 统计账号残留数据（删除前展示）
     *
     * @param userId 用户id
     * @return 数据概览
     */
    UserDataSummaryVO getUserDataSummary(String userId);

    /**
     * 删除账号并彻底清除其全部数据
     *
     * @param userId 用户id
     */
    void deleteUser(String userId);
}
